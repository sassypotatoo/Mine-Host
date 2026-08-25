package com.example.world

import org.json.JSONObject

data class EngineProfile(
    val engine: String,
    val build: String,
    val artifactSha256: String,
    val profileSchema: Int,
    val warningClass: String,
    val warningMethod: String,
    val lookupKeyNamespace: RuntimeLookupNamespace,
    val bytecodeSourceEvidence: String,
    val registrationOrder: String,
    val duplicatePolicy: String,
    val dataBits: Int,
    val dataMask: Int,
    val resourceHashes: Map<String, String>,
    val supportedProtocols: List<Int>,
    val generatorVersion: String,
) {
    companion object {
        fun fromJson(json: JSONObject): EngineProfile {
            val hashes = mutableMapOf<String, String>()
            val hashesJson = json.optJSONObject("resourceHashes")
            if (hashesJson != null) {
                hashesJson.keys().forEach { key ->
                    hashes[key] = hashesJson.getString(key)
                }
            }
            
            val protocols = mutableListOf<Int>()
            val protocolsJson = json.optJSONArray("supportedProtocols")
            if (protocolsJson != null) {
                for (i in 0 until protocolsJson.length()) {
                    protocols.add(protocolsJson.getInt(i))
                }
            }
            
            return EngineProfile(
                engine = json.getString("engine"),
                build = json.getString("build"),
                artifactSha256 = json.getString("artifactSha256"),
                profileSchema = json.getInt("profileSchema"),
                warningClass = json.getString("warningClass"),
                warningMethod = json.getString("warningMethod"),
                lookupKeyNamespace = RuntimeLookupNamespace.valueOf(json.getString("lookupKeyNamespace")),
                bytecodeSourceEvidence = json.getString("bytecodeSourceEvidence"),
                registrationOrder = json.getString("registrationOrder"),
                duplicatePolicy = json.getString("duplicatePolicy"),
                dataBits = json.getInt("dataBits"),
                dataMask = json.getInt("dataMask"),
                resourceHashes = hashes,
                supportedProtocols = protocols,
                generatorVersion = json.getString("generatorVersion")
            )
        }
    }
}

object NukkitMotBuild1361Profile {
    // We still keep this as a default fallback, but it could be loaded from JSON if available.
    val PROFILE = EngineProfile(
        engine = "Nukkit-MOT",
        build = "1361",
        artifactSha256 = "8fdf47cdc0b639dd1e18f8fd79375bcab1f210ade122fe60629aa485026e5ada",
        profileSchema = 1,
        warningClass = "cn.nukkit.level.format.leveldb.BlockStateMapping",
        warningMethod = "getLegacyId/getLegacyData",
        lookupKeyNamespace = RuntimeLookupNamespace.PERSISTENT_PALETTE,
        bytecodeSourceEvidence = "BlockStateMapping.getLegacyId(int) invokes StringConcatFactory with 'Can not find legacyId! No runtime2legacy mapping for \\u0001', passing a runtimeId. The runtimeId is traced to LevelDB chunk decoding. In NukkitLegacyMapper.registerStates, loadBlockPalette() reads leveldb_palette.nbt and assigns list index as runtime ID.",
        registrationOrder = "Two-pass (normal, then overload) via BlockStateMapping.registerState",
        duplicatePolicy = "Retains first registered canonical state (putIfAbsent)",
        dataBits = 6,
        dataMask = 63,
        resourceHashes = mapOf(
            "leveldb_palette.nbt" to "ad8deb4c2429e85d5a07c7fc71ad494b42a3db73e935f14e24a1bfb2f86c44d9",
            "runtime_block_states_1001.dat" to "3e3441149edc9c5eac8c75dc45a292253f14427cb16ae23e3f74cb68a1c8d065"
        ),
        supportedProtocols = listOf(388, 389, 407, 419, 428, 440, 448, 465, 471, 486, 503, 527, 544, 560, 567, 575, 582, 589, 594, 618, 622, 630, 649, 662, 671, 685, 712, 729, 748, 766, 776, 786, 800, 818, 827, 844, 944, 975, 1001),
        generatorVersion = "MineHost 1.0"
    )
}
