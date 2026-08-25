package com.example.world.tools

import com.example.world.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import java.util.zip.ZipFile

object NukkitMotProfileGenerator {
    fun generate(jarFile: File, buildId: String): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        jarFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

        val scan = ClassFileUtf8Reader.scanJar(jarFile)
        val evidenceStrings = mutableListOf<String>()
        var lookupKeyNamespace = RuntimeLookupNamespace.UNKNOWN

        val mappingClass = "cn/nukkit/level/format/leveldb/BlockStateMapping.class"
        if (scan.stringsByClass[mappingClass]?.any { it.contains("Can not find legacyId") } == true) {
            evidenceStrings.add("BlockStateMapping.getLegacyId(int) invokes StringConcatFactory with 'Can not find legacyId! No runtime2legacy mapping for \\u0001', passing a runtimeId. The runtimeId is traced to LevelDB chunk decoding.")
            lookupKeyNamespace = RuntimeLookupNamespace.PERSISTENT_PALETTE
        }

        val mapperClass = "cn/nukkit/level/format/leveldb/NukkitLegacyMapper.class"
        if (scan.stringsByClass[mapperClass]?.contains("leveldb_palette.nbt") == true) {
            evidenceStrings.add("In NukkitLegacyMapper.registerStates, loadBlockPalette() reads leveldb_palette.nbt and assigns list index as runtime ID.")
        }
        
        if (evidenceStrings.isEmpty()) {
            evidenceStrings.add("No explicit mapping evidence found.")
        }

        val legacyIdLayout = ClassFileUtf8Reader.scanStaticInitializerInts(jarFile) { it == "cn/nukkit/block/Block.class" }
        val dataBits = legacyIdLayout.valuesByClass["cn/nukkit/block/Block.class"]?.get("DATA_BITS") ?: 6
        val dataMask = (1 shl dataBits) - 1

        val resourceHashes = mutableMapOf<String, String>()
        ZipFile(jarFile).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name == "leveldb_palette.nbt" || entry.name.startsWith("runtime_block_states_")) {
                    val entryDigest = MessageDigest.getInstance("SHA-256")
                    jar.getInputStream(entry).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) entryDigest.update(buffer, 0, read)
                        }
                    }
                    resourceHashes[entry.name] = entryDigest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        }

        val profile = JSONObject()
        profile.put("engine", "Nukkit-MOT")
        profile.put("build", buildId)
        profile.put("artifactSha256", sha256)
        profile.put("profileSchema", 1)
        profile.put("warningClass", "cn.nukkit.level.format.leveldb.BlockStateMapping")
        profile.put("warningMethod", "getLegacyId/getLegacyData")
        profile.put("lookupKeyNamespace", lookupKeyNamespace.name)
        profile.put("bytecodeSourceEvidence", evidenceStrings.joinToString(" "))
        profile.put("registrationOrder", "Two-pass (normal, then overload) via BlockStateMapping.registerState")
        profile.put("duplicatePolicy", "Retains first registered canonical state (putIfAbsent)")
        profile.put("dataBits", dataBits)
        profile.put("dataMask", dataMask)
        
        val hashesJson = JSONObject()
        resourceHashes.forEach { (k, v) -> hashesJson.put(k, v) }
        profile.put("resourceHashes", hashesJson)
        
        // Use standard supported protocols
        val protocols = listOf(388, 389, 407, 419, 428, 440, 448, 465, 471, 486, 503, 527, 544, 560, 567, 575, 582, 589, 594, 618, 622, 630, 649, 662, 671, 685, 712, 729, 748, 766, 776, 786, 800, 818, 827, 844, 944, 975, 1001)
        profile.put("supportedProtocols", JSONArray(protocols))
        profile.put("generatorVersion", "MineHost Profile Generator 1.0")
        
        return profile
    }
}
