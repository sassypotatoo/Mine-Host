package com.example.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.example.server.destroyForciblyCompat
import com.example.server.waitForCompat
import java.io.File
import java.util.concurrent.TimeUnit

object JavaRuntimeManager {
    private const val TAG = "JavaRuntimeManager"
    private const val VALIDATION_TIMEOUT_SECONDS = 45L

    private const val ENV_RUNTIME_HOME = "MINEHOST_RUNTIME_HOME"
    private const val ENV_JAVA_MAJOR = "MINEHOST_JAVA_MAJOR"
    private const val ENV_ARG_COUNT = "MINEHOST_ARG_COUNT"
    private const val ENV_ARG_PREFIX = "MINEHOST_ARG_"
    private const val ENV_LIBJLI_PATH = "MINEHOST_LIBJLI_PATH"

    private val installationMutexes = mutableMapOf<Int, Mutex>()

    val SUPPORTED_RUNTIME_MAJORS = setOf(17, 21, 25)

    fun supportedRuntimeMajors(): Set<Int> = SUPPORTED_RUNTIME_MAJORS

    private fun getMutexFor(javaMajor: Int): Mutex {
        return synchronized(installationMutexes) {
            installationMutexes.getOrPut(javaMajor) { Mutex() }
        }
    }

    fun getRuntimeHome(context: Context, javaMajor: Int): File {
        return File(context.noBackupFilesDir, "minehost-runtimes/java$javaMajor")
    }

    @Suppress("UNUSED_PARAMETER")
    fun getPackagedLauncher(
        context: Context,
        runtimeHome: File? = null,
    ): File? {
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
        val launcher = File(
            nativeDirectory,
            "libminehost_jvm_launcher.so",
        )

        Log.d(
            TAG,
            "[Launcher] Expected packaged launcher: " +
                launcher.absolutePath,
        )

        if (!launcher.isFile || launcher.length() <= 0L || !launcher.canExecute()) {
            Log.w(TAG, "Native JVM launcher is missing or not executable. Falling back to java binary if available.")
            return null
        }

        return launcher
    }

    fun requireLauncher(context: Context, runtimeHome: File): File {
        return getPackagedLauncher(context, runtimeHome)
            ?: findJavaExecutable(runtimeHome)
            ?: throw java.io.IOException("No Java launcher or binary found for Java runtime at ${runtimeHome.absolutePath}")
    }

    suspend fun verifyRuntimeIntegrity(
        context: Context,
        javaMajor: Int
    ): RuntimeIntegrityResult = withContext(Dispatchers.IO) {
        if (javaMajor !in SUPPORTED_RUNTIME_MAJORS) {
            return@withContext RuntimeIntegrityResult.Invalid(
                "Java $javaMajor is not supported."
            )
        }

        val runtimeHome = getRuntimeHome(context, javaMajor)
        if (!runtimeHome.isDirectory) {
            return@withContext RuntimeIntegrityResult.Invalid(
                "Runtime directory does not exist."
            )
        }

        val metadataFile = File(runtimeHome, "minehost-runtime-metadata.json")
        if (!metadataFile.isFile) {
            return@withContext RuntimeIntegrityResult.Invalid(
                "Metadata file is missing."
            )
        }

        try {
            val metadata = JSONObject(metadataFile.readText())

            if (metadata.optInt("javaMajor", -1) != javaMajor) {
                return@withContext RuntimeIntegrityResult.Invalid(
                    "Metadata Java version mismatch."
                )
            }

            val releaseFile = File(runtimeHome, "release")
            if (!releaseFile.isFile) {
                return@withContext RuntimeIntegrityResult.Invalid(
                    "Runtime release file is missing."
                )
            }

            val releaseMajor = parseJavaMajorFromRelease(releaseFile)
            if (releaseMajor != javaMajor) {
                return@withContext RuntimeIntegrityResult.Invalid(
                    "Runtime release version mismatch: expected $javaMajor, found $releaseMajor."
                )
            }

            val abi = metadata.optString("abi", "")
            if (abi != "arm64-v8a" || !android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                return@withContext RuntimeIntegrityResult.Invalid(
                    "Runtime ABI $abi is not compatible with this device."
                )
            }

            val requiredFiles = metadata.optJSONArray("requiredFiles")
                ?: return@withContext RuntimeIntegrityResult.Invalid(
                    "Runtime metadata has no requiredFiles list."
                )

            for (index in 0 until requiredFiles.length()) {
                val relativePath = requiredFiles.optString(index)
                if (relativePath.isBlank()) {
                    return@withContext RuntimeIntegrityResult.Invalid(
                        "Runtime metadata contains an empty required file path."
                    )
                }

                var requiredFile = File(runtimeHome, relativePath)
                if (!requiredFile.exists() && relativePath.endsWith("libjli.so")) {
                    requiredFile = findLibJli(runtimeHome)
                        ?: return@withContext RuntimeIntegrityResult.Invalid(
                            "Required file missing: $relativePath"
                        )
                }

                if (!requiredFile.isFile || requiredFile.length() <= 0L) {
                    return@withContext RuntimeIntegrityResult.Invalid(
                        "Required file is missing or empty: ${requiredFile.relativeTo(runtimeHome).path}"
                    )
                }

                if (
                    relativePath == "bin/java" ||
                    relativePath.startsWith("bin/")
                ) {
                    if (!requiredFile.canExecute() &&
                        !requiredFile.setExecutable(true, false)
                    ) {
                        return@withContext RuntimeIntegrityResult.Invalid(
                            "Required executable is not executable: $relativePath"
                        )
                    }
                }
            }

            val fingerprint = metadata.optString("runtimeFingerprint", "")
            if (fingerprint.isBlank()) {
                return@withContext RuntimeIntegrityResult.Invalid(
                    "Runtime fingerprint is missing."
                )
            }

            RuntimeIntegrityResult.Valid(
                runtimeHome = runtimeHome,
                javaMajor = javaMajor,
                runtimeFingerprint = fingerprint
            )
        } catch (e: Exception) {
            RuntimeIntegrityResult.Invalid(
                "Failed to validate runtime metadata: ${e.message}"
            )
        }
    }

    suspend fun ensureRuntimeReady(
        context: Context,
        javaMajor: Int,
        onProgress: (String) -> Unit
    ): RuntimePreparationResult {
        val mutex = getMutexFor(javaMajor)
        if (mutex.isLocked) {
            onProgress("[Runtime] Java $javaMajor preparation is already in progress.")
        }

        return mutex.withLock {
            if (javaMajor !in SUPPORTED_RUNTIME_MAJORS) {
                return@withLock RuntimePreparationResult.Unsupported(
                    requiredJavaMajor = javaMajor,
                    message = "Java $javaMajor is not supported."
                )
            }

            val integrity = verifyRuntimeIntegrity(context, javaMajor)
            if (integrity is RuntimeIntegrityResult.Valid) {
                val launcher = getPackagedLauncher(context) ?: findJavaExecutable(integrity.runtimeHome)
                
                if (launcher != null) {
                    onProgress("[Runtime] Validating Java $javaMajor execution...")

                    val validation = runDetailedValidation(
                        launcherFile = launcher,
                        runtimeHome = integrity.runtimeHome,
                        javaMajor = javaMajor,
                        context = context
                    )

                    if (validation.success) {
                        val firstLine = validation.output
                            .lineSequence()
                            .firstOrNull { it.isNotBlank() }
                            ?: "version check passed"
                        onProgress("[Runtime] Java $javaMajor verified: $firstLine")

                        return@withLock RuntimePreparationResult.Ready(
                            runtimeHome = integrity.runtimeHome,
                            launcherFile = launcher,
                            javaMajor = javaMajor,
                            runtimeFingerprint = integrity.runtimeFingerprint
                        )
                    }

                    onProgress(
                        "[Runtime] Existing Java $javaMajor failed execution validation. Reinstalling.\n" +
                            validation.output
                    )
                } else {
                    onProgress("[Runtime] No launcher or java binary found for Java $javaMajor. Reinstalling.")
                }
            }

            onProgress("Java runtime (v$javaMajor) not found or broken. Downloading...")
            JavaRuntimeInstaller.installRuntime(
                context = context,
                javaMajor = javaMajor,
                onProgress = onProgress
            )
        }
    }

    fun findJavaExecutable(runtimeHome: File): File? {
        val javaFile = File(runtimeHome, "bin/java")
        return javaFile.takeIf {
            it.isFile && it.length() > 0L &&
                (it.canExecute() || it.setExecutable(true, false))
        }
    }

    private fun findLibJli(runtimeHome: File): File? {
        val metadataPath = readMetadataLibJliPath(runtimeHome)
        if (!metadataPath.isNullOrBlank()) {
            val metadataFile = File(runtimeHome, metadataPath)
            if (metadataFile.isFile && metadataFile.length() > 0L) {
                return metadataFile
            }
        }

        return listOf(
            File(runtimeHome, "lib/libjli.so"),
            File(runtimeHome, "lib/jli/libjli.so")
        ).firstOrNull { it.isFile && it.length() > 0L }
    }

    private fun readMetadataLibJliPath(runtimeHome: File): String? {
        return try {
            val metadataFile = File(runtimeHome, "minehost-runtime-metadata.json")
            if (!metadataFile.isFile) return null
            JSONObject(metadataFile.readText())
                .optString("libJliPath", "")
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun nativeLibraryDirectories(runtimeHome: File): Set<File> {
        val directories = linkedSetOf<File>()

        // 1. JVM-critical directories must come first to prevent JLI re-execution
        directories.add(File(runtimeHome, "lib/server"))
        directories.add(File(runtimeHome, "lib"))
        directories.add(File(runtimeHome, "lib/jli"))

        // 2. Termux dependency root
        directories.add(File(runtimeHome, "lib/termux/lib"))

        try {
            val metadataFile = File(runtimeHome, "minehost-runtime-metadata.json")
            if (metadataFile.isFile) {
                val array = JSONObject(metadataFile.readText())
                    .optJSONArray("nativeLibraryDirs")

                if (array != null) {
                    for (index in 0 until array.length()) {
                        val relativePath = array.optString(index)
                        if (relativePath.isNotBlank()) {
                            directories.add(File(runtimeHome, relativePath))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read native library directories", e)
        }

        return directories
    }

    fun getLdLibraryPath(runtimeHome: File, javaMajor: Int, context: Context? = null): String {
        val dirs = nativeLibraryDirectories(runtimeHome).map { it.absolutePath }.toMutableList()
        if (context != null) {
            val appNativeDir = context.applicationInfo.nativeLibraryDir
            if (!appNativeDir.isNullOrBlank()) {
                dirs.add(appNativeDir)
            }
        }
        return dirs.distinct().joinToString(File.pathSeparator)
    }

    fun buildJavaEnvironment(
        runtimeHome: File,
        javaMajor: Int,
        workingDir: File,
        context: Context
    ): Map<String, String> {
        val tmpDir = File(context.cacheDir, "server_tmp").apply {
            if (!exists()) mkdirs()
        }

        val currentPath = System.getenv("PATH")
            ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"

        return mapOf(
            "JAVA_HOME" to runtimeHome.absolutePath,
            "HOME" to workingDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "LD_LIBRARY_PATH" to getLdLibraryPath(runtimeHome, javaMajor, context),
            "PATH" to "${File(runtimeHome, "bin").absolutePath}:$currentPath"
        )
    }

    /**
     * Builds the process builder for launching Java. Supports both the native launcher
     * so file and direct java binary execution.
     */
    fun createLauncherProcessBuilder(
        context: Context,
        launcherFile: File,
        runtimeHome: File,
        javaMajor: Int,
        javaArguments: List<String>,
        workingDir: File
    ): ProcessBuilder {
        require(javaMajor in SUPPORTED_RUNTIME_MAJORS) {
            "Unsupported Java major: $javaMajor"
        }
        require(javaArguments.isNotEmpty()) {
            "At least one Java argument is required."
        }
        require(javaArguments.size <= 256) {
            "Too many Java launcher arguments: ${javaArguments.size}"
        }
        require(javaArguments.none { it.indexOf('\u0000') >= 0 }) {
            "Java arguments must not contain NUL characters."
        }

        val isNativeLauncher = launcherFile.name == "libminehost_jvm_launcher.so"
        val processBuilder = if (isNativeLauncher) {
            ProcessBuilder(launcherFile.absolutePath)
        } else {
            ProcessBuilder(listOf(launcherFile.absolutePath) + javaArguments)
        }

        processBuilder.directory(workingDir)
        processBuilder.redirectErrorStream(true)

        val environment = processBuilder.environment()
        environment.remove("JAVA_TOOL_OPTIONS")
        environment.remove("_JAVA_OPTIONS")
        environment.remove("CLASSPATH")

        environment.keys
            .filter {
                it == ENV_ARG_COUNT ||
                    it.startsWith(ENV_ARG_PREFIX) ||
                    it == ENV_RUNTIME_HOME ||
                    it == ENV_JAVA_MAJOR ||
                    it == ENV_LIBJLI_PATH
            }
            .toList()
            .forEach { environment.remove(it) }

        environment.putAll(
            buildJavaEnvironment(
                runtimeHome = runtimeHome,
                javaMajor = javaMajor,
                workingDir = workingDir,
                context = context
            )
        )

        if (isNativeLauncher) {
            environment[ENV_RUNTIME_HOME] = runtimeHome.absolutePath
            environment[ENV_JAVA_MAJOR] = javaMajor.toString()
            environment[ENV_ARG_COUNT] = javaArguments.size.toString()

            javaArguments.forEachIndexed { index, argument ->
                environment["$ENV_ARG_PREFIX$index"] = argument
            }

            findLibJli(runtimeHome)?.let {
                environment[ENV_LIBJLI_PATH] = it.absolutePath
            }
        }

        return processBuilder
    }

    fun parseJavaMajorFromRelease(release: File): Int {
        return try {
            val properties = java.util.Properties()
            release.inputStream().use { properties.load(it) }

            val version = properties
                .getProperty("JAVA_VERSION")
                ?.removeSurrounding("\"")
                .orEmpty()

            if (version.startsWith("1.")) {
                version.substring(2)
                    .substringBefore(".")
                    .toIntOrNull()
                    ?: -1
            } else {
                version.substringBefore(".")
                    .toIntOrNull()
                    ?: -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun parseJavaMajorFromVersionOutput(output: String): Int? {
        val match = Regex("""version\s+"(?:1\.)?(\d+)""")
            .find(output)
            ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    suspend fun runDetailedValidation(
        launcherFile: File,
        runtimeHome: File,
        javaMajor: Int,
        context: Context
    ): JavaValidationResult = withContext(Dispatchers.IO) {
        val javaArguments = listOf("-version")
        val isNativeLauncher = launcherFile.name == "libminehost_jvm_launcher.so"
        val conceptualCommand = if (isNativeLauncher) {
            buildString {
                append(launcherFile.absolutePath)
                append(" --runtime-home ")
                append(runtimeHome.absolutePath)
                append(" --java-major ")
                append(javaMajor)
                append(" -- -version")
            }
        } else {
            "${launcherFile.absolutePath} -version"
        }

        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        Log.d(TAG, "[Launcher] Path: ${launcherFile.absolutePath}")
        Log.d(TAG, "[Launcher] nativeLibraryDir: $nativeLibraryDir")
        Log.d(TAG, "[Launcher] Exists: ${launcherFile.exists()}")
        Log.d(TAG, "[Launcher] Is file: ${launcherFile.isFile}")
        Log.d(TAG, "[Launcher] Size: ${launcherFile.length()}")
        Log.d(TAG, "[Launcher] Executable: ${launcherFile.canExecute()}")

        if (!launcherFile.isFile || launcherFile.length() <= 0L) {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val applicationInfo = context.applicationInfo
            
            return@withContext JavaValidationResult(
                success = false,
                exitCode = -1,
                output = buildString {
                    appendLine("ERROR: Native JVM launcher was not extracted correctly.")
                    appendLine("Package: ${context.packageName}")
                    appendLine("Version Code: ${if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode}")
                    appendLine("Source Dir: ${applicationInfo.sourceDir}")
                    appendLine("Native Library Dir: ${applicationInfo.nativeLibraryDir}")
                    appendLine("Expected Launcher Path: ${launcherFile.absolutePath}")
                    appendLine("File Exists: ${launcherFile.exists()}")
                    appendLine("Is Real File: ${launcherFile.isFile}")
                    appendLine("File Size: ${launcherFile.length()}")
                    appendLine("Supported ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                    
                    val libDir = File(applicationInfo.nativeLibraryDir)
                    if (libDir.exists() && libDir.isDirectory) {
                        appendLine("Contents of nativeLibraryDir:")
                        libDir.listFiles()?.forEach { 
                            appendLine("  - ${it.name} (${it.length()} bytes)")
                        } ?: appendLine("  (directory is empty or inaccessible)")
                    } else {
                        appendLine("Native Library Dir does not exist or is not a directory.")
                    }
                    
                    append("APK packaging must use extractNativeLibs=true and useLegacyPackaging=true.")
                },
                command = conceptualCommand,
                runtimePath = runtimeHome.absolutePath,
                launcherPath = launcherFile.absolutePath
            )
        }

        if (!launcherFile.canExecute()) {
            return@withContext JavaValidationResult(
                success = false,
                exitCode = -1,
                output = "ERROR: Native JVM launcher is not executable.\nPath: ${launcherFile.absolutePath}",
                command = conceptualCommand,
                runtimePath = runtimeHome.absolutePath,
                launcherPath = launcherFile.absolutePath
            )
        }

        try {
            val processBuilder = createLauncherProcessBuilder(
                context = context,
                launcherFile = launcherFile,
                runtimeHome = runtimeHome,
                javaMajor = javaMajor,
                javaArguments = javaArguments,
                workingDir = context.filesDir
            )

            Log.d(
                TAG,
                "[Launcher] Transport: environment, argumentCount=${javaArguments.size}"
            )

            val process = processBuilder.start()
            val completed = process.waitForCompat(
                VALIDATION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            if (!completed) {
                process.destroy()
                if (!process.waitForCompat(2, TimeUnit.SECONDS)) {
                    process.destroyForciblyCompat()
                    process.waitForCompat(2, TimeUnit.SECONDS)
                }

                val output = runCatching {
                    process.inputStream.bufferedReader().readText()
                }.getOrDefault("")

                return@withContext JavaValidationResult(
                    success = false,
                    exitCode = -2,
                    output = buildString {
                        appendLine("ERROR: Java $javaMajor version check timed out.")
                        appendLine("Timeout: $VALIDATION_TIMEOUT_SECONDS seconds")
                        if (output.isNotBlank()) {
                            appendLine("Output:")
                            append(output)
                        }
                    },
                    command = conceptualCommand,
                    runtimePath = runtimeHome.absolutePath,
                    launcherPath = launcherFile.absolutePath
                )
            }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.exitValue()
            val detectedMajor = parseJavaMajorFromVersionOutput(output)
            val success = exitCode == 0 && detectedMajor == javaMajor

            JavaValidationResult(
                success = success,
                exitCode = exitCode,
                output = if (success) {
                    output
                } else {
                    buildString {
                        appendLine(output.trimEnd())
                        appendLine()
                        appendLine("Expected Java major: $javaMajor")
                        appendLine("Detected Java major: ${detectedMajor ?: "unknown"}")
                    }.trim()
                },
                command = conceptualCommand,
                runtimePath = runtimeHome.absolutePath,
                launcherPath = launcherFile.absolutePath
            )
        } catch (e: Exception) {
            JavaValidationResult(
                success = false,
                exitCode = -1,
                output = """
                    ERROR: Native launcher process creation failed.
                    Exception: ${e.javaClass.name}
                    Message: ${e.message}
                    Launcher: ${launcherFile.absolutePath}
                    Exists: ${launcherFile.exists()}
                    Executable: ${launcherFile.canExecute()}
                    Size: ${launcherFile.length()}
                    Runtime: ${runtimeHome.absolutePath}
                    Command: $conceptualCommand
                    Native library directory: $nativeLibraryDir
                """.trimIndent(),
                command = conceptualCommand,
                runtimePath = runtimeHome.absolutePath,
                launcherPath = launcherFile.absolutePath
            )
        }
    }

    suspend fun validateJavaBinary(
        javaExecutable: File,
        runtimeHome: File,
        javaMajor: Int,
        context: Context
    ): Boolean {
        return runDetailedValidation(
            launcherFile = javaExecutable,
            runtimeHome = runtimeHome,
            javaMajor = javaMajor,
            context = context
        ).success
    }

    fun buildJavaCommand(
        context: Context,
        javaMajor: Int,
        javaArguments: List<String>
    ): RuntimeCommandResult {
        val runtimeHome = getRuntimeHome(context, javaMajor)
        val launcher = getPackagedLauncher(context) ?: findJavaExecutable(runtimeHome)
            ?: throw IllegalStateException("No Java launcher or binary found for Java $javaMajor")

        // This list is a diagnostic representation. The real launcher transport
        // is configured by createLauncherProcessBuilder().
        val command = if (launcher.name == "libminehost_jvm_launcher.so") {
            buildList {
                add(launcher.absolutePath)
                add("--runtime-home")
                add(runtimeHome.absolutePath)
                add("--java-major")
                add(javaMajor.toString())
                add("--")
                addAll(javaArguments)
            }
        } else {
            buildList {
                add(launcher.absolutePath)
                addAll(javaArguments)
            }
        }

        return RuntimeCommandResult(
            launcherFile = launcher,
            commandArgs = command
        )
    }
}
