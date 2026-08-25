package com.example.server

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JavaRuntimeManagerLauncherTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun contextWithNativeDirectory(directory: File): Context {
        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = directory.absolutePath
        }
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(baseContext) {
            override fun getApplicationInfo(): ApplicationInfo = appInfo
            override fun getFilesDir(): File = temporaryFolder.root
            override fun getNoBackupFilesDir(): File = temporaryFolder.root
        }
    }

    @Test
    fun packagedLauncherComesOnlyFromNativeLibraryDirectory() {
        val nativeDirectory = temporaryFolder.newFolder("native")
        val launcher = File(
            nativeDirectory,
            "libminehost_jvm_launcher.so",
        )
        launcher.writeBytes(
            byteArrayOf(0x7F, 0x45, 0x4C, 0x46) + ByteArray(128)
        )
        assertTrue(launcher.setExecutable(true, false))

        val resolved = JavaRuntimeManager.getPackagedLauncher(
            contextWithNativeDirectory(nativeDirectory)
        )

        assertEquals(launcher.canonicalFile, resolved?.canonicalFile)
        assertTrue(resolved?.canExecute() == true)
    }

    @Test
    fun missingPackagedLauncherFailsClosedWithoutPrivateCopy() {
        val nativeDirectory = temporaryFolder.newFolder("native-missing")
        val context = contextWithNativeDirectory(nativeDirectory)

        val resolved = JavaRuntimeManager.getPackagedLauncher(context)
        assertEquals(null, resolved)

        val requireResult = runCatching {
            JavaRuntimeManager.requireLauncher(context, temporaryFolder.newFolder("runtime-empty"))
        }
        assertTrue(requireResult.isFailure)
        assertFalse(
            File(temporaryFolder.root, "minehost_bin").exists()
        )
    }
}
