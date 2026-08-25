package com.example.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.example.BuildConfig
import com.example.security.EncryptedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Real Supabase Auth client for MineHost's Android OAuth callback.
 *
 * It uses the hosted Supabase Auth HTTP API with a PKCE verifier. Only the
 * publishable key is accepted. A service-role/secret key must never be placed
 * in the APK.
 */
class SupabaseAuthManager(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureStore = EncryptedPreferences(appContext, "supabase_auth")

    private val projectUrl: String = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
    private val configuredRedirectUri: Uri = Uri.parse(BuildConfig.MINEHOST_AUTH_REDIRECT_URI.trim())

    private val _state = MutableStateFlow<AuthState>(initialState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val redirectUri: String = configuredRedirectUri.toString()

    val isConfigured: Boolean
        get() = missingConfiguration().isEmpty()

    init {
        if (isConfigured) restoreSession()
    }

    /** Creates a one-time PKCE URL. The caller should open it in a browser. */
    fun createGoogleLoginUri(): Result<Uri> = runCatching {
        ensureConfigured()
        val verifier = randomBase64Url(64)
        val oauthState = randomBase64Url(32)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        secureStore.putString(KEY_PKCE_VERIFIER, verifier)
        secureStore.putString(KEY_OAUTH_STATE, oauthState)
        secureStore.putString(KEY_PKCE_CREATED_AT, System.currentTimeMillis().toString())
        _state.value = AuthState.Authorizing()

        Uri.parse("$projectUrl/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", redirectUri)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .appendQueryParameter("state", oauthState)
            .build()
    }.onFailure { error ->
        clearPendingPkce()
        _state.value = AuthState.Error(error.userMessage())
    }

    /** Opens the real Supabase/Google OAuth flow in the system browser. */
    fun createGoogleLoginIntent(): Result<Intent> = createGoogleLoginUri().map { uri ->
        Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Handles the configured OAuth callback. Returns true only for an exact URI match. */
    fun handleDeepLink(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (!isMineHostAuthCallback(uri)) return false
        val authError = uri.getQueryParameter("error_description")
            ?: uri.getQueryParameter("error")
        if (!authError.isNullOrBlank()) {
            clearPendingPkce()
            _state.value = AuthState.Error(authError)
            return true
        }
        val expectedState = secureStore.getString(KEY_OAUTH_STATE)
        val returnedState = uri.getQueryParameter("state")
        if (expectedState.isNullOrBlank() || returnedState.isNullOrBlank() || expectedState != returnedState) {
            clearPendingPkce()
            _state.value = AuthState.Error("The Google sign-in callback could not be verified. Start sign-in again.")
            return true
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            clearPendingPkce()
            _state.value = AuthState.Error("Supabase callback did not contain an authorization code.")
            return true
        }
        scope.launch { exchangeCodeForSession(code) }
        return true
    }

    fun restoreSession() {
        if (!isConfigured) {
            _state.value = initialState()
            return
        }
        scope.launch {
            val stored = secureStore.getString(KEY_SESSION)
            if (stored.isNullOrBlank()) {
                _state.value = AuthState.SignedOut
                return@launch
            }
            val session = runCatching { SupabaseSession.fromJson(JSONObject(stored)) }.getOrNull()
            if (session == null) {
                secureStore.remove(KEY_SESSION)
                _state.value = AuthState.SignedOut
                return@launch
            }
            if (session.isExpiringSoon()) {
                refreshSession(session.refreshToken)
            } else {
                _state.value = AuthState.SignedIn(session)
            }
        }
    }

    fun currentSession(): SupabaseSession? = (state.value as? AuthState.SignedIn)?.session
        ?: secureStore.getString(KEY_SESSION)?.let { raw ->
            runCatching { SupabaseSession.fromJson(JSONObject(raw)) }.getOrNull()
        }

    suspend fun requireValidSession(): Result<SupabaseSession> {
        if (!isConfigured) {
            return Result.failure(IllegalStateException("Supabase is not configured."))
        }
        val current = currentSession()
            ?: secureStore.getString(KEY_SESSION)?.let {
                runCatching { SupabaseSession.fromJson(JSONObject(it)) }.getOrNull()
            }
            ?: return Result.failure(IllegalStateException("Sign in is required."))
        return if (current.isExpiringSoon()) {
            refreshSession(current.refreshToken)
        } else {
            _state.value = AuthState.SignedIn(current)
            Result.success(current)
        }
    }

    suspend fun refreshSession(): Result<SupabaseSession> {
        val session = (state.value as? AuthState.SignedIn)?.session
            ?: return Result.failure(IllegalStateException("No session is available to refresh."))
        return refreshSession(session.refreshToken)
    }

    suspend fun signOut(): Result<Unit> {
        val current = (state.value as? AuthState.SignedIn)?.session
        val remote = if (current != null && isConfigured) {
            runCatching {
                executeJson(
                    request = Request.Builder()
                        .url("$projectUrl/auth/v1/logout")
                        .post(EMPTY_JSON_BODY)
                        .authHeaders(current.accessToken)
                        .build(),
                    allowEmpty = true,
                )
            }
        } else {
            Result.success(JSONObject())
        }
        clearLocalSession()
        return remote.map { Unit }
    }

    fun clearLocalSession() {
        secureStore.remove(KEY_SESSION)
        clearPendingPkce()
        _state.value = if (isConfigured) AuthState.SignedOut else initialState()
    }

    private suspend fun exchangeCodeForSession(code: String) {
        val verifier = secureStore.getString(KEY_PKCE_VERIFIER)
        val createdAt = secureStore.getString(KEY_PKCE_CREATED_AT)?.toLongOrNull()
        if (verifier.isNullOrBlank() || createdAt == null ||
            System.currentTimeMillis() - createdAt > PKCE_MAX_AGE_MS
        ) {
            clearPendingPkce()
            _state.value = AuthState.Error("The login request expired. Start Google sign-in again.")
            return
        }

        val body = JSONObject()
            .put("auth_code", code)
            .put("code_verifier", verifier)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val result = runCatching {
            executeJson(
                Request.Builder()
                    .url("$projectUrl/auth/v1/token?grant_type=pkce")
                    .post(body)
                    .publicHeaders()
                    .build(),
            )
        }.mapCatching { SupabaseSession.fromJson(it) }

        clearPendingPkce()
        result.onSuccess(::saveSession).onFailure { error ->
            _state.value = AuthState.Error(error.userMessage())
        }
    }

    private suspend fun refreshSession(refreshToken: String): Result<SupabaseSession> {
        if (!isConfigured) return Result.failure(IllegalStateException("Supabase is not configured."))
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        return runCatching {
            executeJson(
                Request.Builder()
                    .url("$projectUrl/auth/v1/token?grant_type=refresh_token")
                    .post(body)
                    .publicHeaders()
                    .build(),
            )
        }.mapCatching { SupabaseSession.fromJson(it) }
            .onSuccess(::saveSession)
            .onFailure { error ->
                clearLocalSession()
                _state.value = AuthState.Error(
                    message = "Your session could not be refreshed: ${error.userMessage()}",
                    recoverable = true,
                )
            }
    }

    private fun saveSession(session: SupabaseSession) {
        secureStore.putString(KEY_SESSION, session.toJson().toString())
        _state.value = AuthState.SignedIn(session)
    }

    private fun clearPendingPkce() {
        secureStore.remove(KEY_PKCE_VERIFIER)
        secureStore.remove(KEY_OAUTH_STATE)
        secureStore.remove(KEY_PKCE_CREATED_AT)
    }

    private fun isMineHostAuthCallback(uri: Uri): Boolean =
        uri.scheme.equals(configuredRedirectUri.scheme, ignoreCase = true) &&
            uri.host.equals(configuredRedirectUri.host, ignoreCase = true) &&
            uri.path == configuredRedirectUri.path

    private fun ensureConfigured() {
        val missing = missingConfiguration()
        check(missing.isEmpty()) { "Supabase configuration is missing: ${missing.joinToString()}" }
        check(!publishableKey.contains("service_role", ignoreCase = true)) {
            "A service-role key must never be embedded in MineHost."
        }
    }

    private fun initialState(): AuthState {
        val missing = missingConfiguration()
        return if (missing.isEmpty()) AuthState.SignedOut else AuthState.NotConfigured(missing)
    }

    private fun missingConfiguration(): List<String> = buildList {
        val parsedProjectUrl = Uri.parse(projectUrl)
        if (parsedProjectUrl.scheme != "https" ||
            parsedProjectUrl.host.isNullOrBlank() ||
            parsedProjectUrl.userInfo != null ||
            projectUrl.contains("your-project", ignoreCase = true)
        ) {
            add("SUPABASE_URL")
        }

        if (!isValidPublishableKey(publishableKey)) {
            add("SUPABASE_PUBLISHABLE_KEY")
        }

        val redirectScheme = configuredRedirectUri.scheme.orEmpty().lowercase()
        val validRedirect = when (redirectScheme) {
            "https" ->
                !configuredRedirectUri.host.isNullOrBlank() &&
                    !configuredRedirectUri.host.orEmpty()
                        .contains("example", ignoreCase = true) &&
                    !configuredRedirectUri.path.isNullOrBlank()

            APP_AUTH_SCHEME ->
                configuredRedirectUri.host == APP_AUTH_HOST &&
                    configuredRedirectUri.path == APP_AUTH_PATH

            else -> false
        }

        if (!validRedirect) {
            add("MINEHOST_AUTH_REDIRECT_URI")
        }
    }

    private fun isValidPublishableKey(key: String): Boolean {
        if (key.startsWith("sb_publishable_") && key.length > 24) {
            return true
        }
        if (key.count { it == '.' } != 2) {
            return false
        }

        return runCatching {
            val payload = key.split('.')[1]
            val decoded = Base64.decode(
                payload,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val role = JSONObject(String(decoded, Charsets.UTF_8))
                .optString("role")
            role == "anon"
        }.getOrDefault(false)
    }

    private fun Request.Builder.publicHeaders(): Request.Builder =
        header("apikey", publishableKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

    private fun Request.Builder.authHeaders(accessToken: String): Request.Builder =
        publicHeaders().header("Authorization", "Bearer $accessToken")

    private fun executeJson(request: Request, allowEmpty: Boolean = false): JSONObject =
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val parsed = runCatching { JSONObject(text) }.getOrNull()
                val message = parsed?.optString("msg")?.takeIf { it.isNotBlank() }
                    ?: parsed?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: parsed?.optString("error_description")?.takeIf { it.isNotBlank() }
                    ?: parsed?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Supabase request failed with HTTP ${response.code}."
                throw SupabaseAuthException(response.code, message)
            }
            when {
                text.isNotBlank() -> JSONObject(text)
                allowEmpty -> JSONObject()
                else -> throw SupabaseAuthException(response.code, "Supabase returned an empty response.")
            }
        }

    private fun randomBase64Url(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    companion object {
        private const val KEY_SESSION = "session"
        private const val KEY_PKCE_VERIFIER = "pkce_verifier"
        private const val KEY_OAUTH_STATE = "oauth_state"
        private const val KEY_PKCE_CREATED_AT = "pkce_created_at"
        private const val PKCE_MAX_AGE_MS = 10 * 60 * 1000L
        private const val APP_AUTH_SCHEME = "minehost"
        private const val APP_AUTH_HOST = "auth"
        private const val APP_AUTH_PATH = "/callback"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)
    }
}

class SupabaseAuthException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)
