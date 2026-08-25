package com.example.server

import java.io.File

/**
 * Read-only compatibility file browser retained for the legacy screen.
 * New MineHost file actions use LocalServerDataService.
 */
class FileManager(private val rootDir: File) {

    private fun resolve(relativePath: String): File? = runCatching {
        val root = rootDir.canonicalFile
        val target = File(root, relativePath).canonicalFile
        if (target.path == root.path || target.path.startsWith(root.path + File.separator)) target else null
    }.getOrNull()

    fun listFiles(relativePath: String = ""): List<File> {
        val targetDir = resolve(relativePath) ?: return emptyList()
        if (!targetDir.exists() || !targetDir.isDirectory) return emptyList()
        return targetDir.listFiles()?.toList()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    fun getFileContent(relativePath: String): String? {
        val file = resolve(relativePath) ?: return null
        if (!file.exists() || file.isDirectory) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun getFileInfo(relativePath: String): FileInfo? {
        val file = resolve(relativePath) ?: return null
        if (!file.exists()) return null
        return FileInfo(
            name = file.name,
            size = if (file.isDirectory) 0 else file.length(),
            isDirectory = file.isDirectory,
            lastModified = file.lastModified(),
            relativePath = relativePath
        )
    }
}

data class FileInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long,
    val relativePath: String
)
