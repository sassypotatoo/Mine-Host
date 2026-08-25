package com.example.server.termux

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPackageResolverTest {

    private val resolver = TermuxPackageResolver(OkHttpClient())

    @Test
    fun validAarch64PackageIsParsedWithExactSha() {
        val sha = "a".repeat(64)
        val content = """
            Package: openjdk-21
            Version: 21.0.1-1
            Architecture: aarch64
            Depends: libc++
            Filename: pool/main/o/openjdk-21/openjdk-21_21.0.1-1_aarch64.deb
            Size: 123456
            SHA256: $sha
        """.trimIndent()

        val packages = resolver.parsePackages(content)

        assertEquals(1, packages.size)
        val pkg = packages.getValue("openjdk-21")
        assertEquals(123456L, pkg.size)
        assertEquals(sha, pkg.sha256)
        assertTrue(resolver.getFullDownloadUrl(pkg).startsWith("https://"))
    }


    @Test
    fun virtualDependencyResolvesThroughSingleProvider() {
        val sha = "c".repeat(64)
        val content = """
            Package: openjdk-21
            Version: 21
            Architecture: aarch64
            Depends: java-runtime
            Filename: pool/main/o/openjdk-21.deb
            Size: 100
            SHA256: $sha

            Package: java-runtime-provider
            Version: 1
            Architecture: aarch64
            Provides: java-runtime (= 1)
            Filename: pool/main/j/java-runtime-provider.deb
            Size: 50
            SHA256: $sha
        """.trimIndent()
        val packages = resolver.parsePackages(content)

        val resolved = resolver.resolveDependenciesFromIndex(
            rootPackage = "openjdk-21",
            allPackages = packages,
        )

        assertEquals(
            listOf("openjdk-21", "java-runtime-provider"),
            resolved.map(TermuxPackage::name),
        )
    }

    @Test
    fun missingDependencyFailsClosed() {
        val sha = "d".repeat(64)
        val content = """
            Package: openjdk-17
            Version: 17
            Architecture: aarch64
            Depends: missing-native-library
            Filename: pool/main/o/openjdk-17.deb
            Size: 100
            SHA256: $sha
        """.trimIndent()
        val packages = resolver.parsePackages(content)

        var rejected = false
        try {
            resolver.resolveDependenciesFromIndex(
                rootPackage = "openjdk-17",
                allPackages = packages,
            )
        } catch (_: java.io.IOException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun ambiguousVirtualDependencyFailsClosed() {
        val sha = "e".repeat(64)
        val content = """
            Package: openjdk-25
            Version: 25
            Architecture: aarch64
            Depends: java-runtime
            Filename: pool/main/o/openjdk-25.deb
            Size: 100
            SHA256: $sha

            Package: provider-a
            Version: 1
            Architecture: aarch64
            Provides: java-runtime
            Filename: pool/main/p/provider-a.deb
            Size: 50
            SHA256: $sha

            Package: provider-b
            Version: 1
            Architecture: aarch64
            Provides: java-runtime
            Filename: pool/main/p/provider-b.deb
            Size: 50
            SHA256: $sha
        """.trimIndent()
        val packages = resolver.parsePackages(content)

        var rejected = false
        try {
            resolver.resolveDependenciesFromIndex(
                rootPackage = "openjdk-25",
                allPackages = packages,
            )
        } catch (_: java.io.IOException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun unsafePathInvalidShaAndWrongArchitectureAreRejected() {
        val validSha = "b".repeat(64)
        val content = """
            Package: unsafe-path
            Version: 1
            Architecture: aarch64
            Filename: ../../outside.deb
            Size: 10
            SHA256: $validSha

            Package: invalid-sha
            Version: 1
            Architecture: aarch64
            Filename: pool/main/i/invalid-sha.deb
            Size: 10
            SHA256: not-a-sha

            Package: wrong-architecture
            Version: 1
            Architecture: x86_64
            Filename: pool/main/w/wrong.deb
            Size: 10
            SHA256: $validSha
        """.trimIndent()

        val packages = resolver.parsePackages(content)

        assertTrue(packages.isEmpty())
        assertFalse(packages.containsKey("unsafe-path"))
    }
}
