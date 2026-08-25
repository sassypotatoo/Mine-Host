package com.example.auth

import com.example.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** PostgREST/RPC client that always sends the signed-in user's JWT so RLS is enforced. */
class SupabaseRestClient(
    private val authManager: SupabaseAuthManager,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val projectUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()

    suspend fun get(
        table: String,
        query: Map<String, String> = emptyMap(),
    ): Result<String> = authenticatedRequest(retryOnUnauthorized = true) { token ->
        val url = "$projectUrl/rest/v1/${sanitizeResource(table)}".toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        Request.Builder().url(url).get().headers(token).build()
    }

    suspend fun insert(
        table: String,
        body: JSONObject,
        upsert: Boolean = false,
        onConflict: String? = null,
    ): Result<String> = authenticatedRequest(retryOnUnauthorized = true) { token ->
        val url = "$projectUrl/rest/v1/${sanitizeResource(table)}".toHttpUrl().newBuilder().apply {
            onConflict?.let { addQueryParameter("on_conflict", it) }
        }.build()
        Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .headers(token)
            .header("Prefer", if (upsert) "resolution=merge-duplicates,return=representation" else "return=representation")
            .build()
    }

    suspend fun update(
        table: String,
        filters: Map<String, String>,
        body: JSONObject,
    ): Result<String> = authenticatedRequest(retryOnUnauthorized = true) { token ->
        val url = "$projectUrl/rest/v1/${sanitizeResource(table)}".toHttpUrl().newBuilder().apply {
            filters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        Request.Builder()
            .url(url)
            .patch(body.toString().toRequestBody(JSON))
            .headers(token)
            .header("Prefer", "return=representation")
            .build()
    }

    suspend fun delete(
        table: String,
        filters: Map<String, String>,
    ): Result<String> = authenticatedRequest(retryOnUnauthorized = true) { token ->
        val url = "$projectUrl/rest/v1/${sanitizeResource(table)}".toHttpUrl().newBuilder().apply {
            filters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        Request.Builder()
            .url(url)
            .delete()
            .headers(token)
            .header("Prefer", "return=representation")
            .build()
    }

    suspend fun rpc(function: String, body: JSONObject): Result<String> =
        authenticatedRequest(retryOnUnauthorized = true) { token ->
            Request.Builder()
                .url("$projectUrl/rest/v1/rpc/${sanitizeResource(function)}")
                .post(body.toString().toRequestBody(JSON))
                .headers(token)
                .build()
        }

    fun parseArray(text: String): JSONArray = if (text.isBlank()) JSONArray() else JSONArray(text)

    fun parseFirstObject(text: String): JSONObject? {
        if (text.isBlank()) return null
        return when (text.trimStart().firstOrNull()) {
            '[' -> parseArray(text).optJSONObject(0)
            '{' -> JSONObject(text)
            else -> null
        }
    }

    private suspend fun authenticatedRequest(
        retryOnUnauthorized: Boolean,
        requestFactory: (String) -> Request,
    ): Result<String> {
        val session = authManager.requireValidSession().getOrElse { return Result.failure(it) }
        val first = execute(requestFactory(session.accessToken))
        if (first.exceptionOrNull() is SupabaseRestException &&
            (first.exceptionOrNull() as SupabaseRestException).statusCode == 401 &&
            retryOnUnauthorized
        ) {
            val refreshed = authManager.refreshSession().getOrElse { return Result.failure(it) }
            return execute(requestFactory(refreshed.accessToken))
        }
        return first
    }

    private fun execute(request: Request): Result<String> = runCatching {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = parseError(text) ?: "Supabase request failed with HTTP ${response.code}."
                throw SupabaseRestException(response.code, message)
            }
            text
        }
    }

    private fun Request.Builder.headers(accessToken: String): Request.Builder =
        header("apikey", publishableKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

    private fun sanitizeResource(value: String): String {
        require(value.matches(Regex("[a-zA-Z0-9_]+"))) { "Invalid Supabase resource name." }
        return value
    }

    private fun parseError(text: String): String? = runCatching {
        val json = JSONObject(text)
        json.optString("message").takeIf { it.isNotBlank() }
            ?: json.optString("hint").takeIf { it.isNotBlank() }
            ?: json.optString("details").takeIf { it.isNotBlank() }
            ?: json.optString("code").takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class SupabaseRestException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)
