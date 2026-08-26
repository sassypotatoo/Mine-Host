package com.example.javaedition

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

data class PaperBuildInfo(
    val minecraftVersion: String,
    val buildNumber: Int,
    val channel: String,
    val downloadUrl: String,
    val downloadName: String,
    val sha256: String,
    val requiredJavaMajor: Int,
    val fileSize: Long,
)

object PaperResolver {

    private const val DEFAULT_PAPER_PROJECT_URL =
        "https://fill.papermc.io/v3/projects/paper"

    var projectUrl: String = DEFAULT_PAPER_PROJECT_URL
        internal set

    private val SHA256 =
        Regex("^[0-9a-fA-F]{64}$")

    private val VERSION =
        Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?$""")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun userAgent(): String =
        PaperUserAgentProvider.value()

    fun determineRequiredJavaMajor(
        minecraftVersion: String,
    ): Int {
        val match =
            VERSION.matchEntire(minecraftVersion.trim())
                ?: throw IllegalArgumentException(
                    "Unsupported Paper Minecraft version format: $minecraftVersion"
                )

        val major = match.groupValues[1].toInt()
        val minor =
            match.groupValues[2]
                .takeIf(String::isNotBlank)
                ?.toInt()
                ?: 0
        val patch =
            match.groupValues[3]
                .takeIf(String::isNotBlank)
                ?.toInt()
                ?: 0

        return when {
            major == 26 -> 25

            major == 1 && minor in 17..19 -> 17

            major == 1 && minor == 20 -> 21

            major == 1 && minor == 21 && patch <= 11 -> 21

            else ->
                throw IllegalArgumentException(
                    "MineHost has no verified Java runtime mapping for Paper Minecraft $minecraftVersion"
                )
        }
    }

    internal fun compareVersions(
        left: String,
        right: String,
    ): Int {
        fun parts(value: String): List<Int> {
            val match =
                VERSION.matchEntire(value.trim())
                    ?: return emptyList()

            return match.groupValues
                .drop(1)
                .map { it.toIntOrNull() ?: 0 }
        }

        val a = parts(left)
        val b = parts(right)

        if (a.isEmpty() && b.isEmpty()) return left.compareTo(right)
        if (a.isEmpty()) return -1
        if (b.isEmpty()) return 1

        val size = maxOf(a.size, b.size)

        for (index in 0 until size) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }

            if (av != bv) {
                return av.compareTo(bv)
            }
        }

        return 0
    }

    internal fun parseAvailableVersions(
        body: String,
    ): List<String> {
        val root = JSONObject(body)

        if (root.optBoolean("ok", true) == false) {
            throw IOException(
                root.optString(
                    "message",
                    "PaperMC returned an error"
                )
            )
        }

        val versions =
            root.optJSONObject("versions")
                ?: throw IOException(
                    "PaperMC project response has no versions object"
                )

        val result = linkedSetOf<String>()
        val groupNames = versions.keys()

        while (groupNames.hasNext()) {
            val group = groupNames.next()
            val values = versions.optJSONArray(group) ?: continue

            for (index in 0 until values.length()) {
                val value =
                    values.optString(index)
                        .trim()

                if (
                    value.isNotBlank() &&
                    VERSION.matches(value)
                ) {
                    result += value
                }
            }
        }

        if (result.isEmpty()) {
            throw IOException(
                "PaperMC returned no usable Minecraft versions"
            )
        }

        val supported = result.filter { version ->
            runCatching { determineRequiredJavaMajor(version) }.isSuccess
        }

        if (supported.isEmpty()) {
            throw IOException(
                "PaperMC returned no MineHost-supported Paper versions"
            )
        }

        return supported
            .sortedWith { a, b ->
                compareVersions(b, a)
            }
    }

    internal fun parseLatestStableBuild(
        minecraftVersion: String,
        body: String,
    ): PaperBuildInfo {
        val parsed = JSONTokener(body).nextValue()

        val builds = if (parsed is JSONObject) {
            if (parsed.optBoolean("ok", true) == false) {
                throw IOException(
                    parsed.optString(
                        "message",
                        "PaperMC builds query failed"
                    )
                )
            }
            parsed.optJSONArray("builds")
                ?: throw IOException(
                    "Unexpected PaperMC builds response object (no 'builds' array)"
                )
        } else {
            parsed as? JSONArray
                ?: throw IOException(
                    "PaperMC builds response is not an array"
                )
        }

        var best: PaperBuildInfo? = null

        for (index in 0 until builds.length()) {
            val build =
                builds.optJSONObject(index)
                    ?: continue

            if (
                !build
                    .optString("channel")
                    .equals("STABLE", ignoreCase = false)
            ) {
                continue
            }

            val buildId =
                build.optInt("id", -1)

            if (buildId <= 0) {
                continue
            }

            val downloads =
                build.optJSONObject("downloads")
                    ?: continue

            val artifact =
                downloads.optJSONObject("server:default")
                    ?: continue

            val name =
                artifact
                    .optString("name")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?: continue

            val url =
                artifact
                    .optString("url")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?: continue

            val checksums =
                artifact.optJSONObject("checksums")
                    ?: continue

            val sha =
                checksums
                    .optString("sha256")
                    .trim()
                    .lowercase()
                    .takeIf(SHA256::matches)
                    ?: continue

            val size =
                artifact.optLong("size", -1L)

            if (size <= 1024L) {
                continue
            }

            validatePaperArtifactUrl(url)

            val candidate =
                PaperBuildInfo(
                    minecraftVersion = minecraftVersion,
                    buildNumber = buildId,
                    channel = "STABLE",
                    downloadUrl = url,
                    downloadName = name,
                    sha256 = sha,
                    requiredJavaMajor =
                        determineRequiredJavaMajor(
                            minecraftVersion
                        ),
                    fileSize = size,
                )

            if (
                best == null ||
                candidate.buildNumber >
                    requireNotNull(best).buildNumber
            ) {
                best = candidate
            }
        }

        return best
            ?: throw IOException(
                "No STABLE Paper build is available for Minecraft $minecraftVersion"
            )
    }

    internal fun validatePaperArtifactUrl(
        value: String,
    ) {
        val url =
            runCatching { value.toHttpUrl() }
                .getOrElse {
                    throw IOException(
                        "Paper artifact URL is invalid"
                    )
                }

        val host = url.host.lowercase()

        // Loopback mirrors isTrustedPaperHost's test allowance; hermetic
        // MockWebServer tests speak plain HTTP on localhost.
        require(
            url.scheme == "https" ||
                host == "localhost" ||
                host == "127.0.0.1"
        ) {
            "Paper artifact URL must use HTTPS"
        }

        require(
            PaperUserAgentProvider.isTrustedPaperHost(host)
        ) {
            "Paper artifact host is not trusted: $host"
        }
    }

    suspend fun getAvailableVersions():
        Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request.Builder()
                        .url(projectUrl)
                        .header(
                            "User-Agent",
                            userAgent()
                        )
                        .header(
                            "Accept",
                            "application/json"
                        )
                        .get()
                        .build()

                val call = client.newCall(request)
                val handle = coroutineContext.job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        call.cancel()
                    }
                }

                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException(
                                "PaperMC project query failed: HTTP ${response.code}"
                            )
                        }

                        val body =
                            response.body?.string()
                                ?: throw IOException(
                                    "PaperMC project response is empty"
                                )

                        Result.success(parseAvailableVersions(body))
                    }
                } finally {
                    handle.dispose()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    suspend fun resolveLatestStableBuild(
        minecraftVersion: String,
    ): Result<PaperBuildInfo> =
        withContext(Dispatchers.IO) {
            try {
                val selected =
                    minecraftVersion.trim()

                require(
                    selected.isNotBlank() &&
                        !selected.equals(
                            "AUTO",
                            ignoreCase = true
                        )
                ) {
                    "An exact Minecraft version is required for Paper"
                }

                determineRequiredJavaMajor(selected)

                val request =
                    Request.Builder()
                        .url(
                            "$projectUrl/versions/$selected/builds"
                        )
                        .header(
                            "User-Agent",
                            userAgent()
                        )
                        .header(
                            "Accept",
                            "application/json"
                        )
                        .get()
                        .build()

                val call = client.newCall(request)
                val handle = coroutineContext.job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        call.cancel()
                    }
                }

                try {
                    call.execute().use { response ->
                        val body =
                            response.body?.string()
                                ?: throw IOException(
                                    "PaperMC builds response is empty"
                                )

                        if (!response.isSuccessful) {
                            val message =
                                runCatching {
                                    JSONObject(body)
                                        .optString("message")
                                }
                                    .getOrNull()
                                    ?.takeIf(String::isNotBlank)

                            throw IOException(
                                message
                                    ?: "PaperMC builds query failed: HTTP ${response.code}"
                            )
                        }

                        Result.success(
                            parseLatestStableBuild(
                                selected,
                                body,
                            )
                        )
                    }
                } finally {
                    handle.dispose()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
}
