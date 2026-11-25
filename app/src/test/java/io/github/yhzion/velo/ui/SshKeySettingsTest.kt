package io.github.yhzion.velo.ui

import io.github.yhzion.velo.FakeKeys
import io.github.yhzion.velo.FakePrefs
import io.github.yhzion.velo.FakeSealer
import io.github.yhzion.velo.SshKey
import io.github.yhzion.velo.SshKeysNotSaved
import io.github.yhzion.velo.SshSecrets
import io.github.yhzion.velo.keyFile
import io.github.yhzion.velo.openSshPem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

private val phone = SshKey(SshSecrets.PHONE_ID, phone = true, publicLine = "ssh-ed25519 AAAAphone velo", fingerprint = "SHA256:phonephonephonephonephonephonephonephonephon")

private fun imported(fingerprint: String, comment: String? = null) =
    SshKey(fingerprint, phone = false, publicLine = "ssh-ed25519 AAAA${fingerprint.takeLast(6)}", fingerprint = fingerprint, comment = comment)

private val first = imported("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU")
private val second = imported("SHA256:fkwY1xcyMNEXQThk3YAYTk8QL/YjIHSGNY7hFO01Ecw", comment = "alice@mac")
private val third = imported("SHA256:GhswSPCeaDn6GU/dJmDa7Okd2newSgy5cnnUIIlFP1k")

/** Key-page models of #284 (Screens): steady key names, and each failure under its own button. */
class SshKeySettingsTest {
    @Test fun theRemotesRowSaysAMissingKeyOnlyWhenTheKeysWereRead() {
        assertEquals("", phoneKeyLine(KeyList.Loading))
        assertEquals(PHONE_KEY_UNAVAILABLE, phoneKeyLine(KeyList.Unavailable))
        assertEquals(PHONE_KEY_NOT_MADE, phoneKeyLine(KeyList.Ready(emptyList())))
        assertEquals(keySummary(phone), phoneKeyLine(KeyList.Ready(listOf(phone))))
    }


    @Test
    fun anImportedKeyIsNamedByItsCommentElseByItsFingerprint() {
        val rows = keyRows(listOf(phone, first, second, third))
        assertEquals(listOf("This phone's key", "Imported key 47DEQpj8", "alice@mac", "Imported key GhswSPCe"), rows.map { it.name })
        // A screen reader hears which key each button is for.
        assertEquals(listOf("Copy this phone's key", "Copy install command for this phone's key", "Share install command for this phone's key"),
            rows[0].actions.map { keyActionDescription(it, rows[0]) })
        assertEquals("Remove imported key 47DEQpj8", keyActionDescription(KeyAction.Remove, rows[1]))
        // A key named by its comment is spoken with the first 8 fingerprint characters too.
        assertEquals(
            listOf("Copy imported key alice@mac fkwY1xcy", "Copy install command for imported key alice@mac fkwY1xcy",
                "Share install command for imported key alice@mac fkwY1xcy", "Remove imported key alice@mac fkwY1xcy"),
            rows[2].actions.map { keyActionDescription(it, rows[2]) })
    }

    // Fix round 1 of Task 3: ssh-keygen's default comment is user@host, so keys made on one
    // machine share it. On screen each row also shows its fingerprint; a screen reader and Voice
    // Access hear the button alone, so its words tell the keys apart, always (not only when two
    // share a comment: a name must not change when another key goes).
    @Test
    fun keysThatShareACommentAreToldApartInEveryButton() {
        val rsa = imported("SHA256:GhswSPCeaDn6GU/dJmDa7Okd2newSgy5cnnUIIlFP1k", comment = "alice@mac")
        val rows = keyRows(listOf(phone, second, rsa))
        assertEquals(listOf("alice@mac", "alice@mac"), rows.drop(1).map { it.name })
        assertEquals(listOf("Remove imported key alice@mac fkwY1xcy", "Remove imported key alice@mac GhswSPCe"),
            rows.drop(1).map { keyActionDescription(KeyAction.Remove, it) })
        for (action in KeyAction.entries) assertTrue(action.name, keyActionDescription(action, rows[1]) != keyActionDescription(action, rows[2]))
        // The words stay when the other key goes.
        assertEquals("Remove imported key alice@mac GhswSPCe", keyActionDescription(KeyAction.Remove, keyRows(listOf(phone, rsa))[1]))
    }

    // #292: a comment that reads like a name the page gives itself is not the key's name: the
    // phone key's, or a fingerprint name (which may be another key's). The key is then named by
    // its own fingerprint, on screen and for a screen reader.
    @Test
    fun aCommentThatReadsLikeOneOfThePagesOwnNamesIsNotTheKeysName() {
        for (comment in listOf("This phone's key", "this phone's key", "This  phone\u2019s  KEY", "Imported key 47DEQpj8", "imported key",
            "Imported  Key GhswSPCe (new)",
            // Review of #294: what draws nothing, or draws like a space or an apostrophe, hides no difference.
            "This phone'\uFE0Fs key", "This phone\uFE0F's key", "Imported\uFE0E key 47DEQpj8", "This\u2003phone's\u00A0key", "Imported\u3000key\u202F47DEQpj8",
            "This phone\u02BCs key", "This phone\u2018s key", "This phone\uFF07s key")) {
            val row = keyRows(listOf(phone, first, imported(second.fingerprint, comment)))[2]
            assertEquals(comment, "Imported key fkwY1xcy", row.name)
            assertEquals(comment, "Imported key fkwY1xcy", keyLabel(row))
            assertEquals(comment, "Remove imported key fkwY1xcy", keyActionDescription(KeyAction.Remove, row))
        }
        // A comment that only shares words with them stays the key's name.
        for (comment in listOf("This phone's key (old)", "my phone's key", "Imported keys", "work imported key")) {
            val row = keyRows(listOf(phone, imported(second.fingerprint, comment)))[1]
            assertEquals(comment, row.name)
            assertEquals("$comment fkwY1xcy", keyLabel(row))
        }
    }

    // Remove cannot be undone: its question names the key it removes, as the button did.
    @Test
    fun theRemoveQuestionNamesTheKeyItRemoves() {
        val rsa = imported("SHA256:GhswSPCeaDn6GU/dJmDa7Okd2newSgy5cnnUIIlFP1k", comment = "alice@mac")
        val rows = keyRows(listOf(phone, first, second, rsa))
        assertEquals(KeyQuestion("Remove this key?", "alice@mac fkwY1xcy\nHosts that only accept it will ask for a password.", "Remove"),
            removeKeyQuestion(rows[2]))
        assertEquals(KeyQuestion("Remove this key?", "alice@mac GhswSPCe\nHosts that only accept it will ask for a password.", "Remove"),
            removeKeyQuestion(rows[3]))
        // A key named by its fingerprint already says those characters, once.
        assertEquals(KeyQuestion("Remove this key?", "Imported key 47DEQpj8\nHosts that only accept it will ask for a password.", "Remove"),
            removeKeyQuestion(rows[1]))
        // The question, the body and the button are the ones Remove always had.
        for (row in rows.drop(1)) {
            val q = removeKeyQuestion(row)
            assertEquals(REMOVE_KEY_QUESTION.title, q.title)
            assertEquals(REMOVE_KEY_QUESTION.confirm, q.confirm)
            assertTrue(q.text, q.text.endsWith(REMOVE_KEY_QUESTION.text))
        }
    }

    // #284: "Imported key 2" became "Imported key 1" once the first was removed.
    @Test
    fun aKeysNameStaysWhenAnotherKeyIsRemoved() {
        val all = listOf(phone, first, second, third)
        val before = keyRows(all).associate { it.key.id to it.name }
        for (gone in listOf(first, second, third)) {
            assertEquals(gone.id, before - gone.id, keyRows(all - gone).associate { it.key.id to it.name })
        }
    }

    @Test
    fun theNamesOfTheKeysKeptStayAfterARemove() {
        val secrets = SshSecrets(FakePrefs(), FakeSealer(), FakeKeys())
        secrets.importKey(keyFile(1), null).getOrThrow()
        secrets.importKey(openSshPem("ssh-ed25519", ByteArray(32) { 9 }, comment = "build@ci").first, null).getOrThrow()
        secrets.importKey(keyFile(3), null).getOrThrow()
        val names = keyRows(secrets.keys()).map { it.name }
        assertEquals("build@ci", names[2])
        assertTrue(names[1], names[1].matches(Regex("Imported key [A-Za-z0-9+/]{8}")))
        assertTrue(names[3], names[3].matches(Regex("Imported key [A-Za-z0-9+/]{8}")))
        assertTrue(secrets.removeKey(secrets.keys()[1].id))
        assertEquals(names - names[1], keyRows(secrets.keys()).map { it.name })
    }

    // #284: a Replace that failed said so under "Imported keys", away from its button.
    @Test
    fun aReplaceFailureShowsUnderThePhoneKeyWhereReplaceIs() {
        val ready = KeyList.Ready(listOf(phone, first))
        val replace = keyChangeProblem(KeyChange.Replace, SshKeysNotSaved())
        assertEquals(KeyPageProblem("Velo couldn't save the key change.", KeyChange.Replace), replace)
        assertEquals("Velo couldn't save the key change.", problemUnder(KeyPageGroup.PhoneKey, replace, ready))
        assertEquals("", problemUnder(KeyPageGroup.ImportedKeys, replace, ready))
        // Import and Remove say theirs with the imported keys, where their buttons are.
        for (change in listOf(KeyChange.Import, KeyChange.Remove)) {
            val problem = keyChangeProblem(change, SshKeysNotSaved())
            assertEquals(change.name, "", problemUnder(KeyPageGroup.PhoneKey, problem, ready))
            assertEquals(change.name, "Velo couldn't save the key change.", problemUnder(KeyPageGroup.ImportedKeys, problem, ready))
        }
        // While the keys cannot be listed only the phone key's group is there: it says every problem.
        val picker = KeyPageProblem("No document picker is available.", KeyChange.Import)
        for (list in listOf(KeyList.Unavailable, KeyList.Loading)) {
            assertEquals("No document picker is available.", problemUnder(KeyPageGroup.PhoneKey, picker, list))
            assertEquals("Velo couldn't save the key change.", problemUnder(KeyPageGroup.PhoneKey, replace, list))
        }
        // No problem: both lines are empty.
        assertEquals("", problemUnder(KeyPageGroup.PhoneKey, null, ready))
        assertEquals("", problemUnder(KeyPageGroup.ImportedKeys, null, ready))
    }

    // Task 2 of #284: a change the prefs file did not take is undone and says so.
    @Test
    fun aKeyChangeTheFileDidNotTakeSaysSo() {
        for (change in KeyChange.entries) assertEquals(change.name, "Velo couldn't save the key change.", keyChangeProblem(change, SshKeysNotSaved()).text)
        assertEquals(ImportStep.Failed("Velo couldn't save the key change."), importStep(Result.failure(SshKeysNotSaved())))
        // Other failures keep their sentences, without the exception's own words.
        assertEquals("Velo couldn't make a new key on this phone.", keyChangeProblem(KeyChange.Replace, KeyStoreException("busy")).text)
        assertEquals("Velo couldn't remove the key.", keyChangeProblem(KeyChange.Remove, IllegalStateException("busy")).text)
        assertEquals("Velo couldn't save the key on this phone.", keyChangeProblem(KeyChange.Import, KeyStoreException("busy")).text)
        assertEquals(ImportStep.Failed("Velo couldn't save the key on this phone."), importStep(Result.failure(KeyStoreException("busy"))))
        assertEquals(ImportStep.Failed("Velo couldn't save the key on this phone."), importStep(Result.failure(UnsatisfiedLinkError("no library"))))
    }
}
