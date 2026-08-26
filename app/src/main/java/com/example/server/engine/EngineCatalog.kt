package com.example.server.engine

enum class TerminalMode {
    DEFAULT,
    DUMB
}

data class EngineBootstrapRule(
    val id: String,
    val promptPatterns: List<String>,
    val response: String,
    val maximumResponses: Int = 1
)

data class EngineLaunchSpec(
    val id: String,
    val family: String,
    val displayName: String,
    val jarName: String,
    val defaultPort: Int = 19132,
    val stopCommand: String = "stop",
    val successPatterns: List<String>,
    val jvmArguments: List<String> = emptyList(),
    val applicationArguments: List<String> = emptyList(),
    val terminalMode: TerminalMode = TerminalMode.DEFAULT,
    val bootstrapRules: List<EngineBootstrapRule> = emptyList(),
    val fatalErrorPatterns: List<String> = listOf(
        "FAILED to bind to",
        "Error occurred during initialization of VM",
        "UnsupportedClassVersionError",
        "NoClassDefFoundError",
        "Could not find or load main class",
        "Address already in use",
        "OutOfMemoryError"
    )
)

object EngineCatalog {
    private val NUKKIT_BOOTSTRAP = listOf(
        EngineBootstrapRule(
            id = "language-eng",
            promptPatterns = listOf(
                "Please choose a language first",
                "eng => English",
                "Welcome! Please choose a language first!",
                "Enter a language code from the list below"
            ),
            response = "eng",
            maximumResponses = 1
        ),
        EngineBootstrapRule(
            id = "pnx-license-fallback",
            promptPatterns = listOf(
                "You MUST accept this license to continue",
                "Do you accept the license?",
                "Type 'yes' to accept the license"
            ),
            response = "yes",
            maximumResponses = 1
        )
    )

    val POWER_NUKKIT_X_2_0_0 = EngineLaunchSpec(
        id = "bedrock_power_nukkit_x",
        family = "PowerNukkitX",
        displayName = "PowerNukkitX 2.0.0",
        jarName = "powernukkitx.jar",
        terminalMode = TerminalMode.DUMB,
        bootstrapRules = NUKKIT_BOOTSTRAP,
        successPatterns = listOf("PowerNukkitX started successfully", "Done ("),
        jvmArguments = listOf(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED"
        ),
        applicationArguments = listOf("--skip-setup", "--accept-license", "--language", "eng")
    )

    val POWER_NUKKIT_X_EXPERIMENTAL = EngineLaunchSpec(
        id = "bedrock_power_nukkit_x_experimental",
        family = "PowerNukkitX",
        displayName = "PowerNukkitX Experimental",
        jarName = "powernukkitx-experimental.jar",
        terminalMode = TerminalMode.DUMB,
        bootstrapRules = NUKKIT_BOOTSTRAP,
        successPatterns = listOf("PowerNukkitX started successfully", "Done ("),
        jvmArguments = listOf(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED"
        ),
        applicationArguments = listOf("--skip-setup", "--accept-license", "--language", "eng")
    )

    val POWER_NUKKIT_1_5_2 = EngineLaunchSpec(
        id = "bedrock_power_nukkit",
        family = "PowerNukkit",
        displayName = "PowerNukkit 1.5.2.x",
        jarName = "powernukkit.jar",
        terminalMode = TerminalMode.DUMB,
        bootstrapRules = NUKKIT_BOOTSTRAP,
        successPatterns = listOf("Done (")
    )

    val PM1E = EngineLaunchSpec(
        id = "bedrock_nukkit",
        family = "PM1E",
        displayName = "PM1E",
        jarName = "nukkit-pm1e.jar",
        terminalMode = TerminalMode.DUMB,
        bootstrapRules = NUKKIT_BOOTSTRAP,
        successPatterns = listOf("Done (")
    )

    val CLOUDBURST = EngineLaunchSpec(
        id = "bedrock_cloudburst_nukkit",
        family = "Cloudburst",
        displayName = "Cloudburst Nukkit",
        jarName = "nukkit.jar",
        terminalMode = TerminalMode.DUMB,
        bootstrapRules = NUKKIT_BOOTSTRAP,
        successPatterns = listOf("Done (")
    )

    val NUKKIT_MOT = EngineLaunchSpec(
        id = "nukkit-mot",
        family = "Nukkit-MOT",
        displayName = "Nukkit-MOT",
        jarName = "nukkit-mot.jar",
        terminalMode = TerminalMode.DUMB,
        bootstrapRules = NUKKIT_BOOTSTRAP,
        successPatterns = listOf("Done (")
    )

    val JAVA_PAPER = EngineLaunchSpec(
        id = "java_paper",
        family = "Paper",
        displayName = "PaperMC",
        jarName = "paper.jar",
        defaultPort = 25565,
        stopCommand = "stop",
        successPatterns = listOf("Done (", "For help, type \"help\""),
        applicationArguments = listOf("--nogui")
    )

    val JAVA_VANILLA = EngineLaunchSpec(
        id = "java_vanilla",
        family = "Vanilla",
        displayName = "Vanilla",
        jarName = "server.jar",
        defaultPort = 25565,
        stopCommand = "stop",
        successPatterns = listOf("Done (", "For help, type \"help\""),
        applicationArguments = listOf("--nogui")
    )

    val JAVA_FABRIC = EngineLaunchSpec(
        id = "java_fabric",
        family = "Fabric",
        displayName = "Fabric",
        jarName = "fabric-server-launch.jar",
        defaultPort = 25565,
        stopCommand = "stop",
        successPatterns = listOf("Done (", "For help, type \"help\""),
        applicationArguments = listOf("--nogui")
    )

    val ALL_ENGINES = listOf(
        POWER_NUKKIT_X_2_0_0,
        POWER_NUKKIT_X_EXPERIMENTAL,
        POWER_NUKKIT_1_5_2,
        PM1E,
        CLOUDBURST,
        NUKKIT_MOT,
        JAVA_PAPER,
        JAVA_VANILLA,
        JAVA_FABRIC
    )

    fun getSpec(id: String): EngineLaunchSpec? {
        return ALL_ENGINES.find { it.id == id }
    }
}
