package io.github.yhzion.velo.ui

import io.github.yhzion.velo.SshKey
import org.junit.Assert.assertEquals
import org.junit.Test

class KeySummaryTest {
    private fun key(line: String, fingerprint: String) = SshKey("phone", true, line, fingerprint)

    @Test fun aKeyIsItsTypeAndTheEndsOfItsFingerprint() {
        assertEquals("ed25519 · SHA256:9fQ2…k3Lw", keySummary(key("ssh-ed25519 AAAAC3Nza phone", "SHA256:9fQ2abcdefghijk3Lw")))
    }

    @Test fun theTypeDropsItsPrefixAndItsCurve() {
        assertEquals("rsa · SHA256:abcd…wxyz", keySummary(key("ssh-rsa AAAAB3Nza", "SHA256:abcdefghijklmnopqrstuvwxyz")))
        assertEquals("ecdsa · SHA256:abcd…wxyz", keySummary(key("ecdsa-sha2-nistp256 AAAAE2Vj", "SHA256:abcdefghijklmnopqrstuvwxyz")))
    }

    @Test fun aShortOrMissingFingerprintStaysAsItIs() {
        assertEquals("ed25519 · SHA256:abcdefgh", keySummary(key("ssh-ed25519 AAAA", "SHA256:abcdefgh")))
        assertEquals("ed25519", keySummary(key("ssh-ed25519 AAAA", "")))
    }

    @Test fun theKeyCardShowsTheFirstTwelveAndTheLastFiveOfTheFingerprint() {
        assertEquals("SHA256:9fQ2pXv7mR1d…Lk3Lw", cardFingerprint(key("ssh-ed25519 AAAA", "SHA256:9fQ2pXv7mR1dabcdefghijklmnopqrstuvwLk3Lw")))
        assertEquals("SHA256:abcdefgh", cardFingerprint(key("ssh-ed25519 AAAA", "SHA256:abcdefgh")))
        assertEquals("", cardFingerprint(key("ssh-ed25519 AAAA", "")))
    }
}
