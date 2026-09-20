suspend fun downloadServerJar(
        context: Context,
        version: EngineVersion,
        destination: File,
        operationId: String? = null,
        minecraftVersion: String? = null,
        onProgress: (String) -> Unit,
    ): ServerJarDownloadResult {
        val isPaper = supportsOfficialPaperFallback(version)

        if (isPaper) {
            val mcVer = minecraftVersion?.trim()?.takeIf { it.isNotBlank() && !it.equals("AUTO", ignoreCase = true) }
                ?: throw IllegalStateException("Paper requires an exact supported Minecraft version.")

            destination.parentFile?.mkdirs()
            val cacheFile = getCacheFile(
                context,
                version.engineId,
                version.id,
                version.jarFileName,
            )

            if (cacheFile.exists()) {
                val verifiedCache = verifyCachedEngineArtifact(
                    context = context,
                    version = version,
                    invalidateInvalid = true,
                    selectedMinecraftVersion = mcVer,
                )

                if (verifiedCache != null) {
                    val cachedIdentity = verifiedCache.resolvedIdentity
                    onProgress(
                        if (cachedIdentity != null) {
                            "[Engine] Reusing verified resolved Paper cache build #${cachedIdentity.resolvedBuildNumber}."
                        } else {
                            "[Engine] Reusing checksum-verified cached Paper artifact: ${cacheFile.name}"
                        }
                    )
                    if (
                        !copyVerifiedArtifact(
                            source = cacheFile,
                            destination = destination,
                            expectedSha256 = verifiedCache.sha256,
                            sourceSha256 = verifiedCache.sha256,
                        )
                    ) {
                        return ServerJarDownloadResult.Failure(
                            "Failed to copy verified Paper artifact to destination"
                        )
                    }
                    onProgress("[Engine] Using cached Paper artifact")
                    return ServerJarDownloadResult.Success(verifiedCache.resolvedIdentity)
                }
            }

            // Download Paper artifact
            val downloadResult = downloadFile(
                url = version.downloadUrl,
                destination = destination,
                operationId = operationId,
                onProgress = { msg -> onProgress("[Engine] $msg") },
                name = version.jarFileName,
                isJar = true,
                expectedJarNameInsideZip = null,
            )
            when (downloadResult) {
                is ArtifactDownloadResult.Success -> {
                    onProgress("[Engine] Paper artifact downloaded")
                    return ServerJarDownloadResult.Success(null)
                }
                is ArtifactDownloadResult.Failure -> {
                    return ServerJarDownloadResult.Failure(downloadResult.message)
                }
                is ArtifactDownloadResult.Cancelled -> {
                    return ServerJarDownloadResult.Cancelled
                }
                else -> {
                    return ServerJarDownloadResult.Failure("Unknown download result")
                }
            }
        } else {
            // Non-Paper path (Bedrock, Nukkit, etc.)
            destination.parentFile?.mkdirs()
            val cacheFile = getCacheFile(
                context,
                version.engineId,
                version.id,
                version.jarFileName,
            )

            if (cacheFile.exists()) {
                val verifiedCache = verifyCachedEngineArtifact(
                    context = context,
                    version = version,
                    invalidateInvalid = true,
                    selectedMinecraftVersion = null,
                )

                if (verifiedCache != null) {
                    val cachedIdentity = verifiedCache.resolvedIdentity
                    onProgress(
                        if (cachedIdentity != null) {
                            "[Engine] Reusing verified resolved cache build #${cachedIdentity.resolvedBuildNumber}."
                        } else {
                            "[Engine] Reusing checksum-verified cached artifact: ${cacheFile.name}"
                        }
                    )
                    if (
                        !copyVerifiedArtifact(
                            source = cacheFile,
                            destination = destination,
                            expectedSha256 = verifiedCache.sha256,
                            sourceSha256 = verifiedCache.sha256,
                        )
                    ) {
                        return ServerJarDownloadResult.Failure(
                            "Failed to copy verified artifact to destination"
                        )
                    }
                    onProgress("[Engine] Using cached artifact")
                    return ServerJarDownloadResult.Success(verifiedCache.resolvedIdentity)
                }
            }

            // Download non-Paper artifact
            val downloadResult = downloadFile(
                url = version.downloadUrl,
                destination = destination,
                operationId = operationId,
                onProgress = { msg -> onProgress("[Engine] $msg") },
                name = version.jarFileName,
                isJar = true,
                expectedJarNameInsideZip = null,
            )
            when (downloadResult) {
                is ArtifactDownloadResult.Success -> {
                    onProgress("[Engine] Artifact downloaded")
                    return ServerJarDownloadResult.Success(null)
                }
                is ArtifactDownloadResult.Failure -> {
                    return ServerJarDownloadResult.Failure(downloadResult.message)
                }
                is ArtifactDownloadResult.Cancelled -> {
                    return ServerJarDownloadResult.Cancelled
                }
                else -> {
                    return ServerJarDownloadResult.Failure("Unknown download result")
                }
            }
        }
    }