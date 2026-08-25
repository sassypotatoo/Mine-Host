package com.example.ui.servercreation

import android.net.Uri
import com.example.server.template.ServerTemplate

import com.example.server.engine.WorldSeedMode
import java.security.SecureRandom

enum class WizardStep(val title: String) {
    BASICS("Basics"),
    ENGINE("Engine"),
    VERSION("Version"),
    WORLD("World"),
    PERFORMANCE("Performance"),
    NETWORK("Network"),
    REVIEW("Review")
}

enum class WorldType(val label: String, val description: String) {
    SURVIVAL("Survival", "Gather resources, build, and survive in a challenging world."),
    CREATIVE("Creative", "Unlimited blocks and flying. Build without limits."),
    ADVENTURE("Adventure", "Explore custom maps and enjoy player-created stories."),
    FLAT("Flat World", "A completely flat world, perfect for building big projects.")
}

enum class Difficulty(val label: String) {
    EASY("Easy"),
    NORMAL("Normal"),
    HARD("Hard")
}

enum class PerformanceProfile(val label: String, val description: String, val tag: String, val ramMb: Int) {
    LOW_RESOURCE("Low Resource", "Best for low-end devices.", "Lower performance", 768),
    BALANCED("Balanced", "Best balance of performance & stability.", "Recommended", 1024),
    PERFORMANCE("Performance", "Higher performance for powerful devices.", "Higher resource use", 2048)
}

enum class NetworkMode(val label: String, val description: String) {
    LOCAL("Local Only", "Only playable on your local network."),
    PUBLIC("Public Access (Coming Soon)", "Open to anyone over the internet."),
    TUNNEL("Tunnel (Preview)", "Secure access through a tunnel connection.")
}

enum class TunnelProvider(val label: String, val description: String) {
    LOCAL_TUNNEL("Local Tunnel (Preview)", "Expose your server via your machine."),
    REGION_TUNNEL("Region Tunnel (Coming Soon)", "Use a nearby relay for better latency."),
    PRIVATE_INVITE("Private Invite (Coming Soon)", "Invite-only access via a secure link.")
}

data class CreateServerDraft(
    val artworkUri: Uri? = null,
    val serverName: String = "",
    val description: String = "",
    val engine: ServerTemplate? = null,
    val engineVersionId: String? = null,
    val bedrockVersion: String? = null,
    val worldType: WorldType = WorldType.SURVIVAL,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val worldSeedMode: WorldSeedMode = WorldSeedMode.RANDOM,
    val worldSeed: Long = SecureRandom().nextLong(),
    val seedInput: String = "",
    val seed: String = "",
    val worldName: String = "",
    val memoryMb: Int = 1024,
    val maxPlayers: Int = 10,
    val performanceProfile: PerformanceProfile = PerformanceProfile.BALANCED,
    val cpuPriorityEnabled: Boolean = true,
    val autoRestartEnabled: Boolean = false,
    val startupOptimizationEnabled: Boolean = true,
    val networkMode: NetworkMode = NetworkMode.LOCAL,
    val tunnelProvider: TunnelProvider = TunnelProvider.LOCAL_TUNNEL,
    val port: Int = 19132,
    val visibility: String = "Invite Only",
    val minecraftEulaAccepted: Boolean = false,
    val manualEngineInstallAcknowledged: Boolean = false,
    val manualEngineJarUri: Uri? = null,
    val manualEngineSha256: String = ""
)
