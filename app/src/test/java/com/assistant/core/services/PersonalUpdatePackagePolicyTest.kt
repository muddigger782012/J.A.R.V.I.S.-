package com.assistant.core.services

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalUpdatePackagePolicyTest {
    private val packageId = "com.assistant.core"

    @Test fun acceptsNewerBuildWithMatchingSigner() {
        assertNull(PersonalUpdatePackagePolicy.rejection(
            packageId, 529, setOf("production-cert"),
            packageId, 580, setOf("production-cert")
        ))
    }

    @Test fun rejectsWrongAndroidPackageBeforeOpeningInstaller() {
        val error = PersonalUpdatePackagePolicy.rejection(
            packageId, 529, setOf("production-cert"),
            "com.assistant.core.beta", 580, setOf("production-cert")
        ).orEmpty()
        assertTrue(error.contains("com.assistant.core.beta"))
    }

    @Test fun refusesRepeatedOrOlderInstallAttempts() {
        for (version in listOf(529L, 528L)) {
            val error = PersonalUpdatePackagePolicy.rejection(
                packageId, 529, setOf("production-cert"),
                packageId, version, setOf("production-cert")
            ).orEmpty()
            assertTrue(error.contains("No newer update"))
        }
    }

    @Test fun explainsSignatureConflictWithoutUninstalling() {
        val error = PersonalUpdatePackagePolicy.rejection(
            packageId, 529, setOf("older-signing-key"),
            packageId, 580, setOf("different-key")
        ).orEmpty()
        assertTrue(error.contains("signing certificate"))
        assertTrue(error.contains("data"))
    }

    @Test fun letsAndroidVerifyWhenArchiveCertificateCannotBeRead() {
        assertNull(PersonalUpdatePackagePolicy.rejection(
            packageId, 529, setOf("production-cert"),
            packageId, 580, emptySet()
        ))
    }
}
