package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BedrockWorldMutationRuleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun plannedLevelDbMutationCommitsTransactionally() {
        val serverRoot = tempFolder.newFolder("serverRoot")
        val worldDir = File(serverRoot, "worlds/myworld").apply { mkdirs() }
        File(worldDir, "level.dat").writeText("fixture")
        val dbDir = File(worldDir, "db").apply { mkdirs() }
        val testFile = File(dbDir, "data.ldb").apply { writeText("original") }
        val sourceHash = WorldFileIntegrity.fingerprint(worldDir).rootHash
        val oldHash = WorldFileIntegrity.sha256("old-value".toByteArray())
        val newHash = WorldFileIntegrity.sha256("new-value".toByteArray())

        val rule = object : BedrockWorldMutationRule {
            override val name: String = "TestRule"

            override fun plan(worldDirectory: File): Result<WorldMutationPlan> = Result.success(
                WorldMutationPlan(
                    ruleName = name,
                    sourceWorldHash = sourceHash,
                    mutations = listOf(
                        WorldRecordMutation(
                            databaseKeyHex = "01",
                            oldValueSha256 = oldHash,
                            newValueSha256 = newHash,
                            description = "Replace a synthetic fixture value",
                        ),
                    ),
                    expectedChangedRelativePaths = setOf("db/data.ldb"),
                ),
            )

            override fun apply(worldDirectory: File, plan: WorldMutationPlan): Result<Unit> = runCatching {
                File(worldDirectory, "db/data.ldb").writeText("mutated")
            }

            override fun verify(worldDirectory: File, plan: WorldMutationPlan): Result<Unit> = runCatching {
                require(File(worldDirectory, "db/data.ldb").readText() == "mutated")
            }
        }

        val result = BedrockWorldMutationRunner.runMutation(
            serverRoot = serverRoot,
            worldName = "myworld",
            worldDirectory = worldDir,
            rule = rule,
        )

        assertTrue(result.isSuccess)
        assertEquals("mutated", testFile.readText())
    }

    @Test
    fun unplannedFileChangeFailsAndRestoresDisposableCopy() {
        val serverRoot = tempFolder.newFolder("serverRootRollback")
        val worldDir = File(serverRoot, "worlds/myworld").apply { mkdirs() }
        File(worldDir, "level.dat").writeText("fixture")
        val dbDir = File(worldDir, "db").apply { mkdirs() }
        File(dbDir, "data.ldb").writeText("original")
        val sourceHash = WorldFileIntegrity.fingerprint(worldDir).rootHash

        val rule = object : BedrockWorldMutationRule {
            override val name: String = "UnsafeFixtureRule"

            override fun plan(worldDirectory: File): Result<WorldMutationPlan> = Result.success(
                WorldMutationPlan(
                    ruleName = name,
                    sourceWorldHash = sourceHash,
                    mutations = listOf(
                        WorldRecordMutation(
                            databaseKeyHex = "02",
                            oldValueSha256 = WorldFileIntegrity.sha256("a".toByteArray()),
                            newValueSha256 = WorldFileIntegrity.sha256("b".toByteArray()),
                            description = "Synthetic unsafe fixture",
                        ),
                    ),
                    expectedChangedRelativePaths = setOf("db/data.ldb"),
                ),
            )

            override fun apply(worldDirectory: File, plan: WorldMutationPlan): Result<Unit> = runCatching {
                File(worldDirectory, "db/data.ldb").writeText("mutated")
                File(worldDirectory, "level.dat").writeText("unexpected")
            }

            override fun verify(worldDirectory: File, plan: WorldMutationPlan): Result<Unit> = Result.success(Unit)
        }

        val result = BedrockWorldMutationRunner.runMutation(
            serverRoot = serverRoot,
            worldName = "myworld",
            worldDirectory = worldDir,
            rule = rule,
        )

        assertTrue(result.isFailure)
        assertEquals("original", File(worldDir, "db/data.ldb").readText())
        assertEquals("fixture", File(worldDir, "level.dat").readText())
    }
}
