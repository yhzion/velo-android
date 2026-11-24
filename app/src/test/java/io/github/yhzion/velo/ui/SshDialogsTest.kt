package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ConnectionFailure
import io.github.yhzion.velo.SshKey
import io.github.yhzion.velo.SshKeyProblem
import io.github.yhzion.velo.SshQuestion
import io.github.yhzion.velo.SshSecrets
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.sshHostPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val target = "ssh://demo_user@203.0.113.10"
private const val fingerprint = "SHA256:3qK8vPq1mZr0d6Xy2LwTn5uB4aJcE7hR9sVfQgK1oWc"
private const val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHx0qk2Lw velo"
private const val declined = "Velo ended the connection attempt without an answer to the SSH prompt."

private fun hostKey(keyType: String = "ssh-ed25519", login: String = target, hostPort: String = "203.0.113.10:22") =
    SshQuestion(7, "hostkey", login, hostPort, keyType = keyType, fingerprint = fingerprint)

private fun password(retry: Boolean = false) =
    SshQuestion(8, "password", target, "Enter the SSH password for demo_user on 203.0.113.10.", retry = retry)

private fun prompt(echo: Boolean, retry: Boolean = false) =
    SshQuestion(9, "prompt", target, "Verification code:", echo = echo, retry = retry)

class SshDialogsTest {

    @Test
    fun theHostKeyDialogNamesTheLoginTheKeyAndHowToCompareIt() {
        val text = hostKeyText(hostKey())

        assertEquals("Check the host key", text.title)
        assertEquals("demo_user@203.0.113.10 is new to this phone. Its ED25519 key fingerprint is", text.intro)
        assertEquals(fingerprint, text.fingerprint)
        assertEquals("Compare it on the host:", text.compare)
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", text.command)
        assertEquals("Copy command", text.copy)
        assertEquals("Cancel", text.cancel)
        assertEquals("Trust and connect", text.confirm)
    }

    @Test
    fun theCompareCommandReadsTheHostsKeyOfTheTypeItOffered() {
        // The pool's host_key_changed fix picks the file the same way (errors.go).
        val ecdsa = hostKeyText(hostKey("ecdsa-sha2-nistp256"))
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub", ecdsa.command)
        assertTrue(ecdsa.intro, ecdsa.intro.endsWith("Its ECDSA key fingerprint is"))
        val rsa = hostKeyText(hostKey("ssh-rsa"))
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_rsa_key.pub", rsa.command)
        assertTrue(rsa.intro, rsa.intro.endsWith("Its RSA key fingerprint is"))
        // A login on another port says so: its key is another host:port's.
        val port = hostKeyText(hostKey(login = "ssh://alice@studio.example:2222", hostPort = "studio.example:2222"))
        assertTrue(port.intro, port.intro.startsWith("alice@studio.example:2222 is new to this phone."))
    }

    @Test
    fun thePasswordDialogSaysWhoAsksAndWhatRexKeeps() {
        val text = answerText(password())

        assertEquals("Password", text.title)
        assertEquals(password().text, text.sentence)
        assertEquals("Velo saves it on this phone, encrypted.", text.note)
        assertEquals("Use this phone's key from now on", text.keyOption)
        assertTrue(text.masked)
        assertNull(text.refused)
        assertEquals("Cancel", text.cancel)
        assertEquals("Log in", text.confirm)
        // Only an SSH retry carries the password rejection notice.
        val refusal = answerText(password(retry = true)).refused.orEmpty()
        assertTrue(refusal.contains("rejected", ignoreCase = true) && refusal.contains("demo_user") && refusal.contains("203.0.113.10"))
    }

    @Test
    fun aPromptIsAnsweredAsItAsksAndNeverKept() {
        val hidden = answerText(prompt(echo = false))
        assertEquals("demo_user@203.0.113.10", hidden.title)
        assertEquals("Verification code:", hidden.sentence)
        assertTrue(hidden.masked)
        assertNull(hidden.note)
        assertNull(hidden.keyOption)
        assertNull(hidden.refused)
        assertFalse(answerText(prompt(echo = true)).masked)
        assertTrue(answerText(prompt(echo = true, retry = true)).refused.orEmpty().contains("another answer"))
    }

    @Test
    fun eachDialogAnswersThePoolAsTheQuestionsKindWants() {
        // Trust stores the key and answers "trust" (SshQuestions); the text is not used.
        assertEquals(SshAnswer(7, "", ok = true, savePassword = false, installKey = false), sshAnswer(hostKey(), "", ok = true, useKey = true))
        // A password is saved once it logs in; the phone key follows it when the option is on (convenience A).
        assertEquals(SshAnswer(8, "s3cret", ok = true, savePassword = true, installKey = true), sshAnswer(password(), "s3cret", ok = true, useKey = true))
        assertEquals(SshAnswer(8, "s3cret", ok = true, savePassword = true, installKey = false), sshAnswer(password(), "s3cret", ok = true, useKey = false))
        // A prompt's answer is never kept and installs nothing.
        assertEquals(SshAnswer(9, "123456", ok = true, savePassword = false, installKey = false), sshAnswer(prompt(false), "123456", ok = true, useKey = true))
        // Cancel declines whatever was typed: nothing goes to the host.
        for (q in listOf(hostKey(), password(), prompt(false))) {
            assertEquals(SshAnswer(q.id, "", ok = false, savePassword = false, installKey = false), sshAnswer(q, "typed", ok = false, useKey = true))
        }
    }

    // Ruling P10: a tap beside the dialog or Back is no answer; the question stays on screen.
    @Test
    fun onlyCancelDeclinesAQuestion() {
        for (q in listOf(hostKey(), password(), prompt(false))) {
            assertEquals(SshAnswer(q.id, "", ok = false, savePassword = false, installKey = false), exitAnswer(q, QuestionExit.Cancel))
            assertNull(exitAnswer(q, QuestionExit.Back))
            assertNull(exitAnswer(q, QuestionExit.TapOutside))
        }
    }

    // F12 and ruling P14: keys typed for the terminal do not reach a question until the user
    // touches it; an accessibility service clicks without touching, so with one on the
    // dialog takes keys at once (TalkBack's double tap focuses the password field).
    @Test
    fun aQuestionTakesKeysOnceTouchedOrWithAnAccessibilityService() {
        assertFalse(questionTakesKeys(touched = false, accessibilityOn = false))
        assertTrue(questionTakesKeys(touched = true, accessibilityOn = false))
        assertTrue(questionTakesKeys(touched = false, accessibilityOn = true))
        assertTrue(questionTakesKeys(touched = true, accessibilityOn = true))
    }

    // An import adds a key and breaks no login, so it asks nothing (ruling P11 retired); replacing
    // the phone key and removing an imported key each ask first.
    @Test
    fun replacingTheKeyAndRemovingOneAskFirstButImportingDoesNot() {
        assertEquals(KeyQuestion("Replace this phone's key?", "Hosts that know only the current key ask for a password again.", "Replace key"),
            keyPageQuestion(KeyPageAction.Replace))
        assertNull(keyPageQuestion(KeyPageAction.Import))
        assertEquals(KeyQuestion("Remove this key?", "Hosts that only accept it will ask for a password.", "Remove"), REMOVE_KEY_QUESTION)
        assertEquals(listOf("Import a key…", "Replace key…"), KeyPageAction.entries.map { it.label })
    }

    @Test
    fun theKeyPageListsEveryKeyInTheOrderLoginsOfferThem() {
        val phone = SshKey(SshSecrets.PHONE_ID, phone = true, publicLine = line, fingerprint = fingerprint)
        val imported = (1..6).map { SshKey("SHA256:id$it", phone = false, publicLine = "ssh-ed25519 AAAA$it", fingerprint = "SHA256:fp$it") }
        val rows = keyRows(listOf(phone) + imported.take(2))
        // An imported key without a comment is named by its fingerprint (#284: the names no longer renumber).
        assertEquals(listOf("This phone's key", "Imported key fp1", "Imported key fp2"), rows.map { it.name })
        assertEquals(listOf(phone) + imported.take(2), rows.map { it.key })
        // The phone key is never removed; an imported key is.
        assertEquals(listOf(KeyAction.Copy, KeyAction.CopyInstallCommand, KeyAction.Share), rows[0].actions)
        assertEquals(listOf(KeyAction.Copy, KeyAction.CopyInstallCommand, KeyAction.Share, KeyAction.Remove), rows[1].actions)
        assertEquals(listOf("Copy", "Copy install command", "Share", "Remove"), KeyAction.entries.map { it.label })
        // A screen reader tells the rows' buttons apart.
        assertEquals(listOf("Copy this phone's key", "Copy install command for this phone's key", "Share install command for this phone's key"),
            rows[0].actions.map { keyActionDescription(it, rows[0]) })
        assertEquals("Remove imported key fp2", keyActionDescription(KeyAction.Remove, rows[2]))
        // Each row's buttons copy and share that row's key (convenience B for the phone key's row).
        assertEquals(line, keyActionText(KeyAction.Copy, phone))
        assertEquals(sshInstallCommand(line), keyActionText(KeyAction.CopyInstallCommand, phone))
        assertEquals(sshInstallCommand(line), keyActionText(KeyAction.Share, phone))
        assertEquals(sshInstallCommand("ssh-ed25519 AAAA1"), keyActionText(KeyAction.Share, imported[0]))
        assertNull(keyActionText(KeyAction.Remove, imported[0]))
        // Logins offer five keys: the page says so once there are more.
        assertNull(keysNote(5))
        assertEquals("Velo offers the first 5 keys to a host.", keysNote(6))
        assertEquals(keysNote(6), keysNote(keyRows(listOf(phone) + imported).size))
    }

    @Test
    fun thePassphraseDialogCannotBeCanceledWhileItsImportRuns() {
        assertEquals(PassphraseControls(confirm = false, cancel = false), passphraseControls(busy = true, passphrase = "open sesame"))
        assertEquals(PassphraseControls(confirm = false, cancel = true), passphraseControls(busy = false, passphrase = ""))
        assertEquals(PassphraseControls(confirm = true, cancel = true), passphraseControls(busy = false, passphrase = "open sesame"))
    }

    @Test
    fun anEmptyPasswordIsNotSentButAnEmptyPromptAnswerIs() {
        assertFalse(canLogIn(password(), ""))
        assertTrue(canLogIn(password(), "s3cret"))
        assertTrue(canLogIn(prompt(echo = true), ""))
    }

    @Test
    fun theInstallCommandAppendsTheLineToAuthorizedKeys() {
        assertEquals(
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHx0qk2Lw velo' >> ~/.ssh/authorized_keys",
            sshInstallCommand(line),
        )
        // A quote in an imported key's comment cannot end the quoted line early.
        assertEquals("mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo 'ssh-ed25519 AAAA it'\\''s' >> ~/.ssh/authorized_keys",
            sshInstallCommand("ssh-ed25519 AAAA it's"))
    }

    // keys() throws while the Keystore cannot open the phone key: the page says so, and offers no
    // import (importKey would throw); a new phone key can still be made.
    @Test
    fun theKeyPageListsTheKeysOnlyWhenThePhoneKeyOpens() {
        val keys = listOf(SshKey(SshSecrets.PHONE_ID, phone = true, publicLine = line, fingerprint = fingerprint))
        assertEquals(KeyList.Ready(keys), readKeys { keys })
        assertEquals(KeyList.Unavailable, readKeys { throw IllegalStateException("The phone key cannot be opened now.") })
        assertEquals(listOf(KeyPageAction.Import, KeyPageAction.Replace), keyPageActions(KeyList.Ready(keys)))
        assertEquals(listOf(KeyPageAction.Replace), keyPageActions(KeyList.Unavailable))
        assertTrue(keyPageActions(KeyList.Loading).isEmpty())
        assertEquals("Velo can't read the keys right now. Logins ask for a password until it can.", KEYS_UNAVAILABLE)
        assertEquals("This phone's key", PHONE_KEY_HEADER)
        assertEquals("Imported keys", IMPORTED_KEYS_HEADER)
    }

    @Test
    fun anImportAsksForThePassphraseUntilItOpensTheKey() {
        assertEquals(ImportStep.Done, importStep(Result.success(Unit)))
        val locked = SshKeyProblem(SshKeyProblem.Kind.NeedsPassphrase, "Enter the passphrase to import this key.")
        assertEquals(ImportStep.AskPassphrase(null), importStep(Result.failure(locked)))
        val wrong = SshKeyProblem(SshKeyProblem.Kind.WrongPassphrase, "Incorrect passphrase. Try again to import the key.")
        assertEquals(ImportStep.AskPassphrase(wrong.message), importStep(Result.failure(wrong)))
        val notAKey = SshKeyProblem(SshKeyProblem.Kind.NotAKey, "Velo can't use this file as a key. Import an Ed25519, ECDSA or RSA private key.")
        assertEquals(ImportStep.Failed(notAKey.message!!), importStep(Result.failure(notAKey)))
        // The Keystore refused to seal it: the key in use stays, and the user is told, without the exception's words.
        assertEquals(ImportStep.Failed("Velo couldn't save the key on this phone."), importStep(Result.failure(java.security.KeyStoreException("busy"))))
    }

    @Test
    fun copyCommandIsOfferedOnlyForAFailureWithAFix() {
        val install = "rex server install ssh://me@old-box"
        val notInstalled = ConnectionFailure("not_installed", "Velo couldn't find Rex on old-box.", install)
        assertEquals(FailureNotice(notInstalled.text, install), failureNotice(notInstalled, needsAnswer = false, remote = "old-box"))
        val unreachable = ConnectionFailure("unreachable", "Velo couldn't reach old-box on port 22.", "")
        assertEquals(FailureNotice(unreachable.text, null), failureNotice(unreachable, needsAnswer = false, remote = "old-box"))
        assertNull(failureNotice(null, needsAnswer = false, remote = "old-box"))

        val down = UiState(status = "error")
        assertEquals(listOf(EmptyAction.Reconnect, EmptyAction.CopyCommand, EmptyAction.ShowRemotes),
            emptyActions(down, removed = false, sidebar = false, copyCommand = true))
        assertEquals(listOf(EmptyAction.Reconnect, EmptyAction.ShowRemotes), emptyActions(down, removed = false, sidebar = false, copyCommand = false))
        assertEquals("Copy command", EmptyAction.CopyCommand.label)
        // A connected remote has nothing to fix; a removed one keeps no failure.
        assertEquals(listOf(EmptyAction.NewSession), emptyActions(UiState(status = "connected"), removed = false, sidebar = false, copyCommand = true))
        assertEquals(listOf(EmptyAction.ShowRemotes), emptyActions(down, removed = true, sidebar = false, copyCommand = true))
    }

    @Test
    fun aLoginWaitingForTheUserNeedsAnAnswerNotACancel() {
        // The pool gave up after 120 s and says "declined", though the user canceled nothing.
        val timedOut = ConnectionFailure("declined", declined, "")
        assertEquals(FailureNotice("workstation needs your answer to connect.", null), failureNotice(timedOut, needsAnswer = true, remote = "workstation"))
        assertEquals("workstation needs your answer to connect.", errorNotice(declined, timedOut, needsAnswer = true, remote = "workstation"))
        // While the question is still on screen the remote is connecting, with no failure yet.
        assertEquals(FailureNotice("workstation needs your answer to connect.", null), failureNotice(null, needsAnswer = true, remote = "workstation"))
        // The user really canceled: the sentence is true, and it stays.
        assertEquals(FailureNotice(declined, null), failureNotice(timedOut, needsAnswer = false, remote = "workstation"))
        assertEquals(declined, errorNotice(declined, timedOut, needsAnswer = false, remote = "workstation"))
        // Another notice (a command's) is not the login's.
        assertEquals("The pane is not visible yet. Try again.", errorNotice("The pane is not visible yet. Try again.", timedOut, needsAnswer = true, remote = "workstation"))

        assertEquals("needs your answer", remoteRowNote("error", removed = false, needsAnswer = true))
        assertEquals("needs your answer", remoteRowNote("connecting", removed = false, needsAnswer = true))
        assertNull(remoteRowNote("connected", removed = false, needsAnswer = true))
        assertEquals("Removed", remoteRowNote("error", removed = true, needsAnswer = true))
        assertEquals("connection failed", remoteRowNote("error", removed = false, needsAnswer = false))
        // Reconnect stays: it is what asks again (SshQuestions.retry).
        assertEquals(EmptyAction.Reconnect, emptyActions(UiState(status = "error"), removed = false, sidebar = true, copyCommand = false).single())
    }

    @Test
    fun aHostKeyIsForgottenUnderTheHostAndPortThePoolKeepsItUnder() {
        // Go's net.JoinHostPort: the port written out, 22 too, and an IPv6 address in brackets.
        assertEquals("203.0.113.10:22", sshHostPort(target))
        assertEquals("studio.example:2222", sshHostPort("ssh://alice@studio.example:2222"))
        assertEquals("studio.example:22", sshHostPort("SSH://Me@Studio.Example:22"))
        assertEquals("[2001:db8::1]:22", sshHostPort("ssh://me@[2001:db8::1]"))
        assertEquals("[2001:db8::1]:2200", sshHostPort("ssh://me@[2001:DB8::1]:2200"))
        assertNull(sshHostPort("https://workstation.tailnet.example"))
        assertNull(sshHostPort("/data/user/0/io.github.yhzion.velo/files/rex.sock"))
        assertNull(sshHostPort("ssh://studio.example"))
        // The name the hostkey question gives for the same login.
        assertEquals(hostKey().text, sshHostPort(hostKey().target))
    }
}
