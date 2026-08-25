package com.example.server

import android.content.Context
import android.system.Os
import android.util.Log
import com.example.server.termux.TermuxPackage
import com.example.server.termux.TermuxPackageResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayDeque

object JavaRuntimeInstaller {
    private const val TAG = "JavaRuntimeInstaller"
    private val client = OkHttpClient()

    suspend fun installRuntime(
        context: Context,
        javaMajor: Int,
        onProgress: (String) -> Unit
    ): RuntimePreparationResult = withContext(Dispatchers.IO) {
        if (!android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            return@withContext RuntimePreparationResult.Failure(
                stage = "abi",
                message = "This device does not support arm64-v8a."
            )
        }

        val finalDir = JavaRuntimeManager.getRuntimeHome(context, javaMajor)
        val backupDir = File(finalDir.parentFile, "${finalDir.name}.bak")
        val stagingDir = File(context.cacheDir, "java_staging_$javaMajor")
        val cacheDir = File(context.noBackupFilesDir, "termux_deb_cache")
        JavaRuntimeManager.getPackagedLauncher(context)

        // Part 21: Disk space check
        val requiredBytes = 500L * 1024 * 1024 // 500MB safety buffer for extraction and staging
        val availableBytes = context.filesDir.usableSpace
        if (availableBytes < requiredBytes) {
            return@withContext RuntimePreparationResult.Failure(
                stage = "storage",
                message = "Insufficient storage to install Java. Need at least 500MB, but only ${availableBytes / (1024 * 1024)}MB is available."
            )
        }

        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            return@withContext RuntimePreparationResult.Failure(
                stage = "staging",
                message = "Could not create Java staging directory: ${stagingDir.absolutePath}"
            )
        }
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return@withContext RuntimePreparationResult.Failure(
                stage = "cache",
                message = "Could not create Java package cache: ${cacheDir.absolutePath}"
            )
        }

        try {
            val resolver = TermuxPackageResolver(client)
            val rootPackageName = "openjdk-$javaMajor"
            onProgress("[Runtime] Resolving dependencies for $rootPackageName...")
            val packages = resolver.resolveDependencies(rootPackageName)
            if (packages.isEmpty()) {
                throw IOException("Dependency resolver returned no packages for $rootPackageName.")
            }

            onProgress("[Runtime] Downloading and verifying ${packages.size} packages...")
            for (pkg in packages) {
                val safeName = sanitizeFileComponent(pkg.name)
                val safeVersion = sanitizeFileComponent(pkg.version)
                val debFile = File(cacheDir, "${safeName}_${safeVersion}_aarch64.deb")

                if (!isValidPackage(debFile, pkg)) {
                    debFile.delete()
                    val url = resolver.getFullDownloadUrl(pkg)
                    onProgress("[Runtime] Downloading ${pkg.name} (${pkg.version})...")
                    downloadAndVerify(url, debFile, pkg)
                } else {
                    onProgress("[Runtime] Using cached ${pkg.name} (${pkg.version})")
                }

                onProgress("[Runtime] Extracting ${pkg.name}...")
                extractDeb(debFile, stagingDir)
            }

            onProgress("[Runtime] Locating Java $javaMajor home...")
            val jvmRoot = findJavaHome(stagingDir, javaMajor, onProgress)
                ?: throw IOException(
                    "Could not find a valid Java $javaMajor home in extracted files."
                )

            onProgress("[Runtime] Normalizing layout for Java $javaMajor...")
            val normalizedDir = File(stagingDir, "final_normalized")
            normalizedDir.deleteRecursively()
            if (!normalizedDir.mkdirs()) {
                throw IOException(
                    "Could not create normalized runtime directory: ${normalizedDir.absolutePath}"
                )
            }

            if (!jvmRoot.copyRecursively(normalizedDir, overwrite = true)) {
                throw IOException("Failed to copy the discovered Java home into the normalized runtime.")
            }
            ensureRuntimeExecutables(normalizedDir)

            val nativeLibraryDirs = linkedSetOf<String>()
            val termuxPrefix = findTermuxPrefix(jvmRoot)
            if (termuxPrefix != null) {
                nativeLibraryDirs += harvestNativeLibraries(
                    prefix = termuxPrefix,
                    normalizedRoot = normalizedDir,
                    jvmRoot = jvmRoot
                )
            } else {
                onProgress(
                    "[Runtime] Warning: Termux prefix could not be derived from " +
                        jvmRoot.absolutePath
                )
            }

            val runtimeFingerprint = "dynamic-v$javaMajor-${System.currentTimeMillis()}"
            val metadata = createMetadata(
                javaMajor = javaMajor,
                root = normalizedDir,
                packages = packages,
                runtimeFingerprint = runtimeFingerprint,
                nativeLibraryDirs = nativeLibraryDirs
            )
            File(normalizedDir, "minehost-runtime-metadata.json")
                .writeText(metadata.toString(2))

            onProgress("[Runtime] Validating structural integrity...")
            validateNormalizedRuntime(normalizedDir, javaMajor)

            onProgress("[Runtime] Running Java $javaMajor version check from staging...")
            val stagingLauncher = JavaRuntimeManager.requireLauncher(context, normalizedDir)
            val stagingValidation = JavaRuntimeManager.runDetailedValidation(
                launcherFile = stagingLauncher,
                runtimeHome = normalizedDir,
                javaMajor = javaMajor,
                context = context
            )
            if (!stagingValidation.success) {
                onProgress(
                    buildValidationFailureLog(javaMajor, stagingValidation)
                )
                throw IOException(
                    "Java execution validation failed (exit ${stagingValidation.exitCode})"
                )
            }

            onProgress("[Runtime] Committing Java $javaMajor runtime...")
            commitRuntime(
                candidateDir = normalizedDir,
                finalDir = finalDir,
                backupDir = backupDir
            )

            val finalLauncher = JavaRuntimeManager.requireLauncher(context, finalDir)
            try {
                validateNormalizedRuntime(finalDir, javaMajor)
                val finalValidation = JavaRuntimeManager.runDetailedValidation(
                    launcherFile = finalLauncher,
                    runtimeHome = finalDir,
                    javaMajor = javaMajor,
                    context = context
                )
                if (!finalValidation.success) {
                    throw IOException(
                        "Final Java runtime failed execution validation: " +
                            finalValidation.output
                    )
                }
            } catch (commitFailure: Exception) {
                restoreBackup(finalDir, backupDir)
                throw commitFailure
            }

            backupDir.deleteRecursively()
            stagingDir.deleteRecursively()
            onProgress("[Runtime] Java $javaMajor installed and validated successfully.")

            RuntimePreparationResult.Ready(
                runtimeHome = finalDir,
                launcherFile = finalLauncher,
                javaMajor = javaMajor,
                runtimeFingerprint = runtimeFingerprint
            )
        } catch (cancelled: CancellationException) {
            stagingDir.deleteRecursively()
            throw cancelled
        } catch (e: Exception) {
            if (!finalDir.exists() && backupDir.exists()) {
                runCatching { restoreBackup(finalDir, backupDir) }
                    .onFailure { restoreError ->
                        Log.e(TAG, "Failed to restore previous runtime", restoreError)
                    }
            }
            stagingDir.deleteRecursively()
            Log.e(TAG, "Installation failed", e)
            RuntimePreparationResult.Failure(
                stage = "install",
                message = "Java runtime setup failed: ${e.message}",
                cause = e
            )
        }
    }

    private fun sanitizeFileComponent(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._+-]"), "_")
    }

    private fun findTermuxPrefix(jvmRoot: File): File? {
        var current: File? = jvmRoot
        while (current != null) {
            if (current.name == "usr") return current
            current = current.parentFile
        }
        return null
    }

    private fun findLibJli(javaHome: File): File? {
        return listOf(
            File(javaHome, "lib/libjli.so"),
            File(javaHome, "lib/jli/libjli.so")
        ).firstOrNull { it.isFile && it.length() > 0L }
    }

    /**
     * Copies the Termux dependency libraries without flattening their directory
     * structure. Symlinks are materialised as real files so absolute Termux
     * prefix links cannot remain dangling inside MineHost's private runtime.
     */
    private fun harvestNativeLibraries(
        prefix: File,
        normalizedRoot: File,
        jvmRoot: File
    ): Set<String> {
        val sourceLibRoot = File(prefix, "lib")
        if (!sourceLibRoot.isDirectory) return emptySet()

        val targetRoot = File(normalizedRoot, "lib/termux/lib")
        if (!targetRoot.exists() && !targetRoot.mkdirs()) {
            throw IOException("Could not create native dependency directory: ${targetRoot.absolutePath}")
        }

        val sourceRootPath = sourceLibRoot.toPath().toAbsolutePath().normalize()
        val jvmRootPath = jvmRoot.toPath().toAbsolutePath().normalize()
        val nativeDirectories = linkedSetOf<String>()

        sourceLibRoot.walkTopDown()
            .onEnter { directory ->
                val directoryPath = directory.toPath().toAbsolutePath().normalize()
                !Files.isSymbolicLink(directory.toPath()) &&
                    !directoryPath.startsWith(jvmRootPath)
            }
            .forEach { source ->
                val sourcePath = source.toPath().toAbsolutePath().normalize()
                if (sourcePath.startsWith(jvmRootPath)) return@forEach
                if (source == sourceLibRoot) return@forEach

                val isSymbolicLink = Files.isSymbolicLink(source.toPath())
                val libraryName = source.name
                val isLibrary = libraryName.endsWith(".so") || libraryName.contains(".so.")
                if (!isLibrary || (!source.isFile && !isSymbolicLink)) return@forEach

                val relative = sourceRootPath.relativize(sourcePath)
                val destination = targetRoot.toPath().resolve(relative).normalize().toFile()
                assertInside(targetRoot, destination, "native library ${source.absolutePath}")
                destination.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IOException("Could not create native library directory: ${parent.absolutePath}")
                    }
                }

                val realSource = if (isSymbolicLink) source.canonicalFile else source
                val realSourcePath = realSource.toPath().toAbsolutePath().normalize()
                if (realSourcePath.startsWith(jvmRootPath)) return@forEach
                if (!realSource.isFile || realSource.length() <= 0L) {
                    throw IOException(
                        "Native library source is missing or empty: ${source.absolutePath}"
                    )
                }

                if (destination.exists()) {
                    val existingSha = calculateSha256(destination)
                    val incomingSha = calculateSha256(realSource)
                    if (existingSha != incomingSha) {
                        throw IOException(
                            "Native library collision at ${destination.absolutePath}"
                        )
                    }
                } else {
                    Files.copy(
                        realSource.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }

                val parent = destination.parentFile ?: throw IllegalStateException("Destination has no parent")
                nativeDirectories += parent.relativeTo(normalizedRoot).path
            }

        return nativeDirectories
    }

    private fun isValidPackage(debFile: File, pkg: TermuxPackage): Boolean {
        if (!debFile.isFile || debFile.length() != pkg.size) return false
        return calculateSha256(debFile).equals(pkg.sha256, ignoreCase = true)
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun findJavaHome(
        root: File,
        javaMajor: Int,
        onProgress: (String) -> Unit
    ): File? {
        val queue = ArrayDeque<File>()
        val candidates = mutableListOf<File>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val directory = queue.removeFirst()
            if (File(directory, "bin/java").exists()) {
                candidates += directory
                if (isValidJavaHome(directory, javaMajor)) return directory
            }
            directory.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach(queue::addLast)
        }

        if (candidates.isNotEmpty()) {
            onProgress("[Runtime] Java-home detection diagnostics:")
            candidates.forEach { directory ->
                val javaBin = File(directory, "bin/java")
                val modules = File(directory, "lib/modules")
                val libJli = findLibJli(directory)
                val libJvm = File(directory, "lib/server/libjvm.so")
                val release = File(directory, "release")
                val major = if (release.isFile) {
                    JavaRuntimeManager.parseJavaMajorFromRelease(release)
                } else {
                    -1
                }

                onProgress(
                    """
                    Candidate: ${directory.absolutePath}
                    - bin/java: ${describeFile(javaBin)}
                    - lib/modules: ${describeFile(modules)}
                    - libjli.so: ${libJli?.relativeTo(directory)?.path ?: "missing"}
                    - lib/server/libjvm.so: ${describeFile(libJvm)}
                    - release: ${if (release.isFile) "present (major: $major)" else "missing"}
                    - Rejection reason: ${getRejectionReason(directory, javaMajor)}
                    """.trimIndent()
                )
            }
        }

        return null
    }

    private fun describeFile(file: File): String {
        return if (file.isFile) "present (${file.length()} bytes)" else "missing"
    }

    private fun getRejectionReason(directory: File, javaMajor: Int): String {
        val javaBin = File(directory, "bin/java")
        val modules = File(directory, "lib/modules")
        val libJvm = File(directory, "lib/server/libjvm.so")
        val release = File(directory, "release")

        if (!javaBin.isFile || javaBin.length() <= 0L) return "bin/java missing or empty"
        if (!modules.isFile || modules.length() <= 0L) return "lib/modules missing or empty"
        if (findLibJli(directory) == null) return "libjli.so missing"
        if (!libJvm.isFile || libJvm.length() <= 0L) return "lib/server/libjvm.so missing or empty"
        if (!release.isFile) return "release file missing"

        val detected = JavaRuntimeManager.parseJavaMajorFromRelease(release)
        if (detected != javaMajor) {
            return "Java version mismatch (expected $javaMajor, got $detected)"
        }
        return "No structural rejection reason"
    }

    private fun isValidJavaHome(directory: File, javaMajor: Int): Boolean {
        val javaBin = File(directory, "bin/java")
        val modules = File(directory, "lib/modules")
        val libJvm = File(directory, "lib/server/libjvm.so")
        val release = File(directory, "release")

        return javaBin.isFile && javaBin.length() > 0L &&
            modules.isFile && modules.length() > 0L &&
            findLibJli(directory) != null &&
            libJvm.isFile && libJvm.length() > 0L &&
            release.isFile &&
            JavaRuntimeManager.parseJavaMajorFromRelease(release) == javaMajor
    }

    private suspend fun downloadAndVerify(
        url: String,
        destination: File,
        pkg: TermuxPackage
    ) {
        val request = Request.Builder().url(url).get().build()
        val partFile = File("${destination.absolutePath}.part")
        var lastFailure: Exception? = null

        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                partFile.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} for ${pkg.name}")
                    }
                    val body = response.body ?: throw IOException("Empty body for ${pkg.name}")
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var count: Int
                            while (input.read(buffer).also { count = it } != -1) {
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                downloaded += count
                            }
                        }
                    }
                }

                if (downloaded != pkg.size) {
                    throw IOException(
                        "Package size mismatch for ${pkg.name}: expected ${pkg.size}, got $downloaded"
                    )
                }

                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!sha.equals(pkg.sha256, ignoreCase = true)) {
                    throw IOException("Checksum mismatch for ${pkg.name}")
                }

                if (!partFile.renameTo(destination)) {
                    Files.move(
                        partFile.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
                return
            } catch (cancelled: CancellationException) {
                partFile.delete()
                throw cancelled
            } catch (e: Exception) {
                lastFailure = e
                partFile.delete()
                if (attempt < 2) {
                    delay(1_000L * (attempt + 1))
                }
            }
        }

        throw lastFailure ?: IOException("Failed to download ${pkg.name}")
    }

    private fun extractDeb(archive: File, destination: File) {
        var foundDataArchive = false
        FileInputStream(archive).use { input ->
            ArArchiveInputStream(input).use { ar ->
                var entry = ar.nextArEntry
                while (entry != null) {
                    val name = entry.name
                        .trim()
                        .removePrefix("./")
                        .removeSuffix("/")

                    if (name.startsWith("data.tar")) {
                        foundDataArchive = true
                        val tar = when {
                            name.endsWith(".xz") -> TarArchiveInputStream(
                                XZCompressorInputStream(ar)
                            )
                            name.endsWith(".gz") -> TarArchiveInputStream(
                                GzipCompressorInputStream(ar)
                            )
                            else -> throw IOException("Unsupported data archive format: $name")
                        }
                        tar.use { unpackTar(it, destination) }
                        break
                    }
                    entry = ar.nextArEntry
                }
            }
        }

        if (!foundDataArchive) {
            throw IOException("Debian package has no data.tar payload: ${archive.name}")
        }
    }

    private fun unpackTar(tar: TarArchiveInputStream, destination: File) {
        val hardLinks = mutableListOf<Pair<File, String>>()
        var entry = tar.nextTarEntry

        while (entry != null) {
            val output = File(destination, entry.name)
            assertInside(destination, output, "archive entry ${entry.name}")

            when {
                entry.isDirectory -> {
                    if (output.exists() && !output.isDirectory) {
                        Files.deleteIfExists(output.toPath())
                    }
                    if (!output.exists() && !output.mkdirs()) {
                        throw IOException("Could not create archive directory: ${output.absolutePath}")
                    }
                }

                entry.isSymbolicLink -> {
                    output.parentFile?.mkdirs()
                    val safeTarget = safeSymbolicLinkTarget(
                        destination = destination,
                        linkFile = output,
                        archiveTarget = entry.linkName
                    )
                    Files.deleteIfExists(output.toPath())
                    try {
                        Os.symlink(safeTarget, output.absolutePath)
                    } catch (e: Exception) {
                        throw IOException(
                            "Could not create symbolic link ${entry.name} -> ${entry.linkName}",
                            e
                        )
                    }
                }

                entry.isLink -> hardLinks += output to entry.linkName

                entry.isFile -> {
                    output.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IOException("Could not create archive parent: ${parent.absolutePath}")
                        }
                    }
                    Files.deleteIfExists(output.toPath())
                    FileOutputStream(output).use { tar.copyTo(it) }
                    if ((entry.mode and 0x49) != 0 &&
                        !output.setExecutable(true, false)
                    ) {
                        throw IOException("Could not set executable mode: ${output.absolutePath}")
                    }
                }

                else -> Log.d(TAG, "Ignoring unsupported TAR entry: ${entry.name}")
            }

            entry = tar.nextTarEntry
        }

        hardLinks.forEach { (output, archiveTarget) ->
            val target = resolveHardLinkTarget(destination, archiveTarget)
            if (!target.isFile) {
                throw IOException(
                    "Hard-link target is missing: $archiveTarget for ${output.absolutePath}"
                )
            }
            output.parentFile?.mkdirs()
            Files.deleteIfExists(output.toPath())
            try {
                Os.link(target.absolutePath, output.absolutePath)
            } catch (e: Exception) {
                throw IOException(
                    "Could not create hard link ${output.absolutePath} -> ${target.absolutePath}",
                    e
                )
            }
        }
    }

    private fun safeSymbolicLinkTarget(
        destination: File,
        linkFile: File,
        archiveTarget: String
    ): String {
        if (archiveTarget.isBlank()) {
            throw IOException("Symbolic link has an empty target: ${linkFile.absolutePath}")
        }

        val rootPath = destination.canonicalFile.toPath()
        val parent = linkFile.parentFile ?: throw IllegalStateException("Link file has no parent")
        val linkParentPath = parent.canonicalFile.toPath()
        val resolved = if (archiveTarget.startsWith('/')) {
            rootPath.resolve(archiveTarget.removePrefix("/")).normalize()
        } else {
            linkParentPath.resolve(archiveTarget).normalize()
        }

        if (!resolved.startsWith(rootPath)) {
            throw IOException(
                "Symbolic-link target escapes extraction root: ${linkFile.name} -> $archiveTarget"
            )
        }

        return linkParentPath.relativize(resolved).toString()
    }

    private fun resolveHardLinkTarget(destination: File, archiveTarget: String): File {
        val normalizedTarget = archiveTarget.removePrefix("/")
        val target = File(destination, normalizedTarget)
        assertInside(destination, target, "hard-link target $archiveTarget")
        return target
    }

    private fun assertInside(root: File, candidate: File, description: String) {
        val canonicalRoot = root.canonicalFile.toPath()
        val canonicalCandidate = candidate.canonicalFile.toPath()
        if (canonicalCandidate != canonicalRoot &&
            !canonicalCandidate.startsWith(canonicalRoot)
        ) {
            throw IOException("Path traversal blocked for $description")
        }
    }

    private fun ensureRuntimeExecutables(runtimeRoot: File) {
        val binDir = File(runtimeRoot, "bin")
        if (!binDir.isDirectory) return

        binDir.walkTopDown()
            .filter { it.isFile }
            .forEach { executable ->
                if (!executable.canExecute() &&
                    !executable.setExecutable(true, false)
                ) {
                    throw IOException(
                        "Could not make runtime executable: ${executable.absolutePath}"
                    )
                }
            }
    }

    private fun validateNormalizedRuntime(root: File, javaMajor: Int) {
        val required = listOf(
            "bin/java",
            "release",
            "lib/modules",
            "lib/libjava.so",
            "lib/verify.so",
            "lib/server/libjvm.so",
            "lib/jvm.cfg"
        )
        for (relPath in required) {
            val file = File(root, relPath)
            if (relPath == "lib/verify.so") {
                // lib/verify.so might be in lib/libverify.so
                if (!file.exists() && !File(root, "lib/libverify.so").exists()) {
                    throw IOException("Missing required runtime file: $relPath")
                }
                continue
            }
            if (!file.exists()) {
                throw IOException("Missing required runtime file: $relPath")
            }
        }

        if (!isValidJavaHome(root, javaMajor)) {
            throw IOException("Normalized runtime is missing required components.")
        }
        ensureRuntimeExecutables(root)
    }

    private fun createMetadata(
        javaMajor: Int,
        root: File,
        packages: List<TermuxPackage>,
        runtimeFingerprint: String,
        nativeLibraryDirs: Set<String>
    ): JSONObject {
        val libJli = findLibJli(root)
            ?: throw IOException("Normalized runtime is missing libjli.so.")

        return JSONObject().apply {
            put("javaMajor", javaMajor)
            put("abi", "arm64-v8a")
            put("runtimeFingerprint", runtimeFingerprint)
            put("libJliPath", libJli.relativeTo(root).path)

            put("packages", JSONArray().apply {
                packages.forEach { pkg ->
                    put(JSONObject().apply {
                        put("name", pkg.name)
                        put("version", pkg.version)
                        put("sha256", pkg.sha256)
                    })
                }
            })

            put("requiredFiles", JSONArray().apply {
                put("bin/java")
                put("lib/modules")
                put(libJli.relativeTo(root).path)
                put("lib/server/libjvm.so")
                put("release")
            })

            put("nativeLibraryDirs", JSONArray().apply {
                nativeLibraryDirs
                    .filter { it.isNotBlank() }
                    .forEach(::put)
            })
        }
    }

    private fun commitRuntime(
        candidateDir: File,
        finalDir: File,
        backupDir: File
    ) {
        finalDir.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Could not create runtime parent: ${parent.absolutePath}")
            }
        }

        backupDir.deleteRecursively()
        if (finalDir.exists()) {
            moveDirectory(finalDir, backupDir)
        }

        try {
            moveDirectory(candidateDir, finalDir)
        } catch (e: Exception) {
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (backupDir.exists()) moveDirectory(backupDir, finalDir)
            throw e
        }
    }

    private fun restoreBackup(finalDir: File, backupDir: File) {
        if (finalDir.exists()) finalDir.deleteRecursively()
        if (backupDir.exists()) {
            moveDirectory(backupDir, finalDir)
        }
    }

    private fun moveDirectory(source: File, destination: File) {
        if (!source.exists()) {
            throw IOException("Source directory does not exist: ${source.absolutePath}")
        }
        if (destination.exists() && !destination.deleteRecursively()) {
            throw IOException("Could not clear destination: ${destination.absolutePath}")
        }

        if (source.renameTo(destination)) return

        if (!source.copyRecursively(destination, overwrite = true)) {
            destination.deleteRecursively()
            throw IOException(
                "Could not copy ${source.absolutePath} to ${destination.absolutePath}"
            )
        }
        ensureRuntimeExecutables(destination)
        if (!source.deleteRecursively()) {
            Log.w(TAG, "Could not remove source after copy: ${source.absolutePath}")
        }
    }

    private fun buildValidationFailureLog(
        javaMajor: Int,
        validation: JavaValidationResult
    ): String {
        return """
            ERROR: Java $javaMajor execution validation failed.
            Launcher: ${validation.launcherPath}
            Runtime: ${validation.runtimePath}
            Exit code: ${validation.exitCode}
            Output:
            ${validation.output}
        """.trimIndent()
    }
}
