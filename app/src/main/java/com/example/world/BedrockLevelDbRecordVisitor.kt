package com.example.world

/** Progress emitted while reconstructing a read-only LevelDB snapshot. */
data class BedrockLevelDbProgress(
    val filesProcessed: Int,
    val totalFiles: Int,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val recordsProcessed: Long,
    val currentFile: String?,
)

fun interface BedrockLevelDbRecordVisitor {
    /**
     * Called for each current live LevelDB record.
     * Return true to continue visiting, false to abort iteration early.
     */
    fun visit(record: BedrockLevelDbReader.Record): Boolean

    /** Called periodically without retaining a second in-memory snapshot. */
    fun onProgress(progress: BedrockLevelDbProgress) = Unit
}
