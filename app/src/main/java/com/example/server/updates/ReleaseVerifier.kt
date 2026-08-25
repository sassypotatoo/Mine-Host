package com.example.server.updates

import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.jar.JarFile
import java.util.zip.ZipException

object ReleaseVerifier {
    private val TRUSTED_DOMAINS = listOf(
        "github.com",
        "objects.githubusercontent.com",
        "ci.opencollab.dev",
        "repo.opencollab.dev",
        "cloudburstmc.org",
        "motci.cn",
        "mot.dev",
        "ci.nukkit-mot.com",
        "nukkit-mot.com",
        "nightly.link",
        "papermc.io"
    )

    fun isTrustedHost(host: String, trustedDomain: String): Boolean {
        val normalizedHost = host.lowercase().trimEnd('.')
        val normalizedDomain = trustedDomain.lowercase().trimEnd('.')

        return normalizedHost == normalizedDomain ||
                normalizedHost.endsWith(".$normalizedDomain")
    }

    fun isAnyTrustedHost(host: String): Boolean {
        return TRUSTED_DOMAINS.any { isTrustedHost(host, it) }
    }

    fun verify(artifactUrl: String, tempFile: File, expectedEngineId: String): VerificationResult {
        // 1. Domain Check
        var currentUrlString = artifactUrl
        var redirects = 0
        val maxRedirects = 5

        try {
            while (redirects <= maxRedirects) {
                val url = URL(currentUrlString)
                
                // Security checks for URL
                if (url.protocol != "https") {
                    return VerificationResult.Failure("Insecure protocol: ${url.protocol}. HTTPS required.")
                }
                
                if (!isAnyTrustedHost(url.host)) {
                    return VerificationResult.Failure("Untrusted host: ${url.host}")
                }

                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false // Manual redirects
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "MineHost-Engine-Verifier/1.0")

                connection.connect()
                val responseCode = connection.responseCode

                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return VerificationResult.Failure("Redirect missing Location header at $currentUrlString")
                    
                    val newUrl = URL(url, location)
                    if (newUrl.toString() == currentUrlString) {
                        return VerificationResult.Failure("Redirect loop detected at $currentUrlString")
                    }
                    
                    currentUrlString = newUrl.toString()
                    redirects++
                    connection.disconnect()
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return VerificationResult.Failure("HTTP Error: $responseCode")
                }

                // 2. Content Validation
                val contentType = connection.contentType?.lowercase() ?: ""
                var peekedBytes: ByteArray? = null
                if (contentType.contains("text/html") || contentType.contains("application/json") || contentType.contains("application/xml") || contentType.contains("text/plain")) {
                    // Peek at content if suspicious
                    val buffer = ByteArray(256)
                    val bytesRead = connection.inputStream.read(buffer, 0, 256)
                    peekedBytes = if (bytesRead > 0) buffer.copyOf(bytesRead) else ByteArray(0)
                    val peek = peekedBytes.decodeToString()
                    if (peek.contains("<!DOCTYPE html", ignoreCase = true) || 
                        peek.contains("<html", ignoreCase = true) || 
                        peek.contains("Rate limit exceeded", ignoreCase = true) ||
                        peek.contains("Access Denied", ignoreCase = true)) {
                        return VerificationResult.Failure("Invalid artifact content (HTML/Error page detected)")
                    }
                }

                // JARs should ideally be application/java-archive or application/octet-stream
                if (!contentType.contains("java-archive") && !contentType.contains("octet-stream") && contentType.isNotBlank()) {
                    Log.w("ReleaseVerifier", "Unexpected content type: $contentType for $currentUrlString")
                }

                val contentLength = connection.contentLengthLong
                val minSize = 800 * 1024 // ~0.8MB minimum for any real engine JAR
                if (contentLength > 0 && contentLength < minSize) {
                    return VerificationResult.Failure("File too small: ${contentLength / 1024} KB")
                }

                // 3. Download to temp file
                tempFile.outputStream().use { output ->
                    if (peekedBytes != null) {
                        output.write(peekedBytes)
                    }
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                connection.disconnect()

                if (tempFile.length() < minSize) {
                    return VerificationResult.Failure("Downloaded file too small: ${tempFile.length() / 1024} KB")
                }

                // 4. JAR Header Check
                val header = ByteArray(4)
                tempFile.inputStream().use { it.read(header) }
                if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte() || header[2] != 0x03.toByte() || header[3] != 0x04.toByte()) {
                    return VerificationResult.Failure("Invalid JAR magic bytes (not a ZIP/JAR file)")
                }

                // 5. JAR Content Validation
                return validateJar(tempFile, expectedEngineId)
            }
            return VerificationResult.Failure("Too many redirects")
        } catch (e: Exception) {
            return VerificationResult.Failure("Verification failed: ${e.message}")
        }
    }

    private fun validateJar(file: File, expectedEngineId: String): VerificationResult {
        return try {
            JarFile(file).use { jar ->
                // Check for class files
                val entries = jar.entries().asSequence().toList()
                val hasClasses = entries.any { it.name.endsWith(".class") }
                if (!hasClasses) {
                    return VerificationResult.Failure("JAR contains no .class files")
                }

                // Check Manifest for Main-Class
                val manifest = jar.manifest
                val manifestMainClass = manifest?.mainAttributes?.getValue("Main-Class")?.trim()
                
                // Requirement 4: Trusted engine metadata
                val trustedMainClasses = when (expectedEngineId) {
                    "bedrock_nukkit" -> listOf("cn.nukkit.Nukkit")
                    "bedrock_power_nukkit" -> listOf("cn.nukkit.Nukkit")
                    "bedrock_power_nukkit_x" -> listOf("org.powernukkitx.JarStart")
                    "nukkit-mot" -> listOf("cn.nukkit.Nukkit")
                    "bedrock_cloudburst_nukkit" -> listOf("cn.nukkit.Nukkit", "org.cloudburstmc.nukkit.Nukkit")
                    "java_paper" -> listOf("org.bukkit.craftbukkit.Main", "io.papermc.paper.Main")
                    "pocketmine-mp" -> emptyList() // Not a JAR engine usually
                    else -> emptyList()
                }

                // Entry point validation
                var mainClass: String? = manifestMainClass
                var launchMode = "JAVA_JAR"
                
                if (mainClass.isNullOrBlank()) {
                     // Requirement 4: Do not guess from list of common main classes if JAVA_JAR
                     // If JAVA_JAR is intended but Main-Class is missing, it's invalid.
                     // But we can check if it's meant to be MAIN_CLASS if the expected engine has one.
                     if (trustedMainClasses.isNotEmpty()) {
                         val candidate = trustedMainClasses.first()
                         val path = candidate.replace('.', '/') + ".class"
                         if (entries.any { it.name == path }) {
                             mainClass = candidate
                             launchMode = "MAIN_CLASS"
                         }
                     }
                } else {
                    // JAVA_JAR mode: Require Manifest Main-Class and confirm it matches trusted engine metadata (Requirement 4)
                    if (trustedMainClasses.isNotEmpty() && !trustedMainClasses.contains(mainClass)) {
                         return VerificationResult.Failure("JAR Main-Class '$mainClass' does not match trusted entry points for $expectedEngineId: $trustedMainClasses")
                    }
                }

                if (mainClass == null) {
                    return VerificationResult.Failure("No valid server launch entry point found for engine $expectedEngineId.")
                }

                // Verify the selected class exists (Requirement 4)
                val classPath = mainClass.replace('.', '/') + ".class"
                if (entries.none { it.name == classPath }) {
                    return VerificationResult.Failure("Launch class '$mainClass' not found in JAR.")
                }
                
                // Track highest Java version
                var maxClassVersion = 0
                entries.filter { it.name.endsWith(".class") }.forEach { entry ->
                    jar.getInputStream(entry).use { input ->
                        val header = ByteArray(8)
                        val read = input.read(header)
                        if (read == 8 && header[0] == 0xCA.toByte() && header[1] == 0xFE.toByte() && header[2] == 0xBA.toByte() && header[3] == 0xBE.toByte()) {
                            val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                            if (major > maxClassVersion) maxClassVersion = major
                        }
                    }
                }

                val requiredJava = classMajorToJavaMajor(maxClassVersion)
                    ?: return VerificationResult.Failure("Unknown or future Java class version detected: $maxClassVersion")

                if (requiredJava > 25) {
                    return VerificationResult.Unsupported(requiredJava, "MineHost supports up to Java 25")
                }
                
                if (requiredJava != 17 && requiredJava != 21 && requiredJava != 25) {
                    return VerificationResult.Unsupported(requiredJava, "Java $requiredJava is not currently supported by MineHost runtimes.")
                }

                VerificationResult.Success(
                    javaVersion = requiredJava,
                    mainClass = mainClass,
                    launchMode = launchMode
                )
            }
        } catch (e: ZipException) {
            VerificationResult.Failure("Corrupt JAR file: ${e.message}")
        } catch (e: Exception) {
            VerificationResult.Failure("Failed to validate JAR: ${e.message}")
        }
    }

    fun classMajorToJavaMajor(classMajor: Int): Int? {
        return when (classMajor) {
            44 -> 0 // Java 1.0 (historical, not supported)
            45 -> 1 // Java 1.1
            46 -> 1 // Java 1.2
            47 -> 1 // Java 1.3
            48 -> 1 // Java 1.4
            49 -> 5
            50 -> 6
            51 -> 7
            52 -> 8
            53 -> 9
            54 -> 10
            55 -> 11
            56 -> 12
            57 -> 13
            58 -> 14
            59 -> 15
            60 -> 16
            61 -> 17
            62 -> 18
            63 -> 19
            64 -> 20
            65 -> 21
            66 -> 22
            67 -> 23
            68 -> 24
            69 -> 25
            else -> null
        }
    }

    sealed class VerificationResult {
        data class Success(
            val javaVersion: Int,
            val mainClass: String?,
            val launchMode: String
        ) : VerificationResult()
        
        data class Failure(val message: String) : VerificationResult()

        data class Unsupported(val requiredJava: Int, val message: String) : VerificationResult()
    }
}
