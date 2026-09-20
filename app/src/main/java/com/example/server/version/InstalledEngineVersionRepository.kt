fun validateJar(jarFile: File, launchMode: LaunchMode = LaunchMode.JAVA_JAR, mainClass: String? = null): JarValidationResult {
        if (!jarFile.exists()) return JarValidationResult(false, error = "File does not exist")
        if (jarFile.length() < 1024L) return JarValidationResult(false, error = "File is too small")

        return try {
            JarFile(jarFile).use { jar ->
                var hasClass = false
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".class")) {
                        hasClass = true
                        if (entry.name.contains("net/minecraft/server/") || entry.name.contains("net/minecraft/server/tick/")) {
                            // Early exit for Minecraft server classes - common case
                            return JarValidationResult(true)
                        }
                    }
                }
            }
            if (!hasClass) return JarValidationResult(false, error = "No classes found")
            // Additional validation logic remains unchanged
            // ... rest of original implementation ...
        }
    } catch (e: Exception) {
        return JarValidationResult(false, error = "Validation failed: ${e.message}")
    }
}