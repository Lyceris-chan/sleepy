package dev.sleepy.app

import dev.sleepy.app.engine.ApkSignerHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The keystore password is generated per installation rather than compiled in.
 *
 * It used to be the literal `sleepy_password_2026` in `ApkSignerHelper`: the same string in
 * every installation and in the repository itself, so it was one published value that opens
 * every sleepy keystore ever made — MASVS CRYPTO-2, key material that is not secret. These
 * tests pin the two properties the fix has to have: each installation's password comes from its
 * own randomness, and it is the same password the next time that installation signs, because
 * that is what reopens the keystore it was stored with.
 *
 * None of this protects anything against an attacker who can read the app's private storage.
 * The password is written beside the keystore it unlocks, and whoever reads one reads the other.
 * The boundary is the storage, not the string; [ApkSignerHelper.keyStorePassword] says so in
 * full, and these tests are not evidence to the contrary.
 */
class ApkSignerKeyPasswordTest {

    /**
     * Two installations must not share a password.
     *
     * This is the property the hardcoded literal could not have: whichever installation leaked
     * it, it opened all of them. Two directories are two installations — `filesDir` is what the
     * signer passes in.
     */
    @Test
    fun twoInstallationsDoNotShareAKeyPassword() {
        val first = Files.createTempDirectory("sleepy-install-a").toFile()
        val second = Files.createTempDirectory("sleepy-install-b").toFile()
        try {
            val a = ApkSignerHelper.keyStorePassword(first)
            val b = ApkSignerHelper.keyStorePassword(second)

            assertFalse(
                "each installation must get its own password, or one leak opens every install",
                a.contentEquals(b)
            )
            assertTrue("a password that is not there opens nothing", a.isNotEmpty() && b.isNotEmpty())
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    /**
     * The password has to survive between signings: the keystore on disk was written under it,
     * and PKCS#12's integrity check fails the load under any other.
     */
    @Test
    fun anInstallationsKeyPasswordSurvivesBetweenSignings() {
        val directory = Files.createTempDirectory("sleepy-install-stable").toFile()
        try {
            val first = ApkSignerHelper.keyStorePassword(directory)
            val second = ApkSignerHelper.keyStorePassword(directory)

            assertTrue(
                "reading the stored password back must give the same one the keystore was written with",
                first.contentEquals(second)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * It is stored in the directory the keystore lives in — app-private files — and not held in
     * memory, which is what makes it survive a process restart.
     *
     * The value is also long enough to have come from randomness rather than a phrase: 32 random
     * bytes in Base64 are 44 characters, and no hardcoded string in a source file is.
     */
    @Test
    fun theKeyPasswordIsStoredBesideTheKeystore() {
        val directory = Files.createTempDirectory("sleepy-install-stored").toFile()
        try {
            val password = ApkSignerHelper.keyStorePassword(directory)

            val stored = directory.listFiles().orEmpty()
            assertEquals("the password must be written into the certificate's own directory", 1, stored.size)
            assertEquals(
                "the file must hold the password itself, not a placeholder for it",
                String(password),
                stored.single().readText(Charsets.UTF_8).trim()
            )
            assertTrue(
                "a 32-byte random password is 44 Base64 characters; ${password.size} is not one",
                password.size >= 43
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
