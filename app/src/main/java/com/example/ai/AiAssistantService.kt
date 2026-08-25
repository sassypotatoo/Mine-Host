package com.example.ai

import com.example.BuildConfig
import com.example.auth.SupabaseAuthManager
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only AI analysis transport. It never mutates server files or settings.
 * Changes must be represented as recommendations and applied by a separate,
 * confirmed transaction after a backup.
 */
class AiAssistantService(
    private val credentialStore: AiCredentialStore,
    private val authManager: SupabaseAuthManager,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val proxyUrl = BuildConfig.MINEHOST_AI_PROXY_URL.trim()

    fun providerMode(): AiProviderMode = when {
        proxyUrl.startsWith("https://") && authManager.currentSession() != null -> AiProviderMode.AUTHENTICATED_PROXY
        credentialStore.hasUserGeminiKey() -> AiProviderMode.USER_GEMINI_KEY
        else -> AiProviderMode.NOT_CONFIGURED
    }

    private fun externalContext(raw: AiServerContext): AiServerContext {
        val safeSettings = setOf(
            "gamemode", "difficulty", "view-distance", "max-players", "online-mode",
            "spawn-protection", "allow-flight", "hardcore", "pvp",
        )
        return raw.copy(
            serverUuid = null,
            recentLogs = AiContextRedactor.redactLines(raw.recentLogs),
            recentCrashes = AiContextRedactor.redactLines(raw.recentCrashes, 40),
            installedPlugins = raw.installedPlugins.take(300).mapIndexed { index, _ -> "plugin_${index + 1}" },
            currentSettings = raw.currentSettings.entries
                .filter { it.key.lowercase() in safeSettings }
                .take(100)
                .associate { it.key.take(128) to AiContextRedactor.redactLine(it.value) },
        )
    }

    suspend fun ask(userPrompt: String, rawContext: AiServerContext): Result<AiReply> = runCatching {
        val prompt = userPrompt.trim()
        require(prompt.isNotBlank()) { "Ask a non-empty question." }
        require(prompt.length <= 8_000) { "The question is too long." }
        val context = externalContext(rawContext)
        when (providerMode()) {
            AiProviderMode.AUTHENTICATED_PROXY -> askProxy(prompt, context)
            AiProviderMode.USER_GEMINI_KEY -> askGemini(prompt, context)
            AiProviderMode.NOT_CONFIGURED -> throw AiNotConfiguredException(
                "AI is not configured. Add your own Gemini API key or configure MineHost's authenticated AI proxy."
            )
        }
    }

    suspend fun askForPlan(userPrompt: String, rawContext: AiServerContext): Result<AiChangePlan> = runCatching {
        val serverUuid = rawContext.serverUuid ?: throw IllegalArgumentException("Select an exact server before requesting an AI change plan.")
        val context = externalContext(rawContext)
        val planPrompt = buildString {
            appendLine("Create a conservative machine-readable MineHost change plan for this exact server.")
            appendLine("Question: ${userPrompt.trim().take(8_000)}")
            appendLine("Server reference: selected_server")
            appendLine("Use only SET_SERVER_PROPERTY or DISABLE_PLUGIN changes. Never invent old values.")
            appendLine("Return JSON only with: plan_id, server_uuid, diagnosis, evidence[], changes[{type,key,old_value,new_value,reason}], risks[], restart_required, rollback_guidance.")
        }
        val json = when (providerMode()) {
            AiProviderMode.AUTHENTICATED_PROXY -> {
                val session = authManager.requireValidSession().getOrElse { throw it }
                val body = JSONObject()
                    .put("question", planPrompt)
                    .put("server_context", context.toJson())
                    .put("response_format", "minehost_change_plan_v1")
                    .put("policy", POLICY)
                    .toString().toRequestBody(JSON)
                executeJson(Request.Builder().url(proxyUrl)
                    .header("Authorization", "Bearer ${session.accessToken}")
                    .header("Content-Type", "application/json").post(body).build())
                    .optJSONObject("plan") ?: throw IllegalStateException("The AI proxy returned no structured plan.")
            }
            AiProviderMode.USER_GEMINI_KEY -> askGeminiPlan(planPrompt, context)
            AiProviderMode.NOT_CONFIGURED -> throw AiNotConfiguredException(
                "AI is not configured. Add your own Gemini API key or configure MineHost's authenticated AI proxy."
            )
        }
        val normalized = JSONObject(json.toString()).put("server_uuid", serverUuid)
        AiChangePlan.fromJson(normalized).also { plan ->
            require(plan.planId.matches(Regex("[A-Za-z0-9_.-]{8,128}"))) { "AI plan ID is invalid." }
            require(plan.serverUuid == serverUuid) { "AI plan belongs to a different server." }
        }
    }

    private fun askGeminiPlan(prompt: String, context: AiServerContext): JSONObject {
        val key = credentialStore.readUserGeminiKey() ?: throw AiNotConfiguredException("A user Gemini API key is required.")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", POLICY))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt + "\n\nREAL REDACTED CONTEXT:\n" + context.toJson().toString(2))))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.1)
                .put("maxOutputTokens", 2_048)
                .put("responseMimeType", "application/json"))
            .toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:generateContent")
            .header("x-goog-api-key", key)
            .header("Content-Type", "application/json")
            .post(body).build()
        return JSONObject(extractGeminiText(executeJson(request)))
    }

    private suspend fun askProxy(prompt: String, context: AiServerContext): AiReply {
        val session = authManager.requireValidSession().getOrElse { throw it }
        val body = JSONObject()
            .put("question", prompt)
            .put("server_context", context.toJson())
            .put("policy", POLICY)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(proxyUrl)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val responseJson = executeJson(request)
        val text = responseJson.optString("text")
            .ifBlank { responseJson.optString("output_text") }
            .ifBlank { throw IllegalStateException("The AI proxy returned no response text.") }
        return AiReply(text.take(MAX_REPLY_CHARS), AiProviderMode.AUTHENTICATED_PROXY, responseJson.optString("model").ifBlank { null })
    }

    private fun askGemini(prompt: String, context: AiServerContext): AiReply {
        val key = credentialStore.readUserGeminiKey() ?: throw AiNotConfiguredException("A user Gemini API key is required.")
        val userText = buildString {
            appendLine("USER QUESTION:")
            appendLine(prompt)
            appendLine()
            appendLine("REDACTED REAL SERVER CONTEXT:")
            append(context.toJson().toString(2))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", POLICY))))
            .put("contents", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userText)))
            ))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("maxOutputTokens", 2_048))
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:generateContent")
            .header("x-goog-api-key", key)
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val json = executeJson(request)
        val text = extractGeminiText(json)
        return AiReply(text.take(MAX_REPLY_CHARS), AiProviderMode.USER_GEMINI_KEY, DEFAULT_MODEL)
    }

    private fun executeJson(request: Request): JSONObject = httpClient.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = runCatching {
                val json = JSONObject(text)
                json.optJSONObject("error")?.optString("message")
                    ?: json.optString("message")
            }.getOrNull().orEmpty().ifBlank { "AI request failed with HTTP ${response.code}." }
            throw IllegalStateException(message.take(1_000))
        }
        JSONObject(text)
    }

    private fun extractGeminiText(json: JSONObject): String {
        val candidates = json.optJSONArray("candidates") ?: throw IllegalStateException("The AI service returned no candidates.")
        for (candidateIndex in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(candidateIndex)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            val output = buildString {
                for (partIndex in 0 until parts.length()) {
                    val text = parts.optJSONObject(partIndex)?.optString("text").orEmpty()
                    if (text.isNotBlank()) appendLine(text)
                }
            }.trim()
            if (output.isNotBlank()) return output
        }
        throw IllegalStateException("The AI service returned an empty response.")
    }

    companion object {
        private const val DEFAULT_MODEL = "gemini-1.5-flash"
        private const val MAX_REPLY_CHARS = 24_000
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val POLICY = """
            You are MineHost's read-only Minecraft server diagnostic assistant.
            Use only the supplied real server context. Do not invent metrics, logs, players, plugin states, or successful fixes.
            Clearly distinguish evidence from inference. Never claim a change was applied.
            Return: diagnosis, evidence, recommendations, exact proposed changes, risks, whether restart is required, and rollback guidance.
            Do not expose tokens, keys, absolute device paths, or private account data.
            Any proposed file/settings/plugin action requires explicit user confirmation, a verified backup, isolated apply, real restart verification, and rollback on failure.
        """.trimIndent()
    }
}

class AiNotConfiguredException(message: String) : IllegalStateException(message)
