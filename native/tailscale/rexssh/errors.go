package rexssh

import (
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/crypto/ssh"
)

// Error is a failure Velo displays as is: a code for the UI, a message, and
// Fix, a copyable one-line command when a fix is available (convenience C).
//
// Codes:
//   - malformed: the locator is not ssh://USER@HOST[:PORT] (also a user or host that
//     starts with "-", or holds anything but letters, digits, ".", "_" and "-").
//   - no_user: the locator names no user.
//   - unreachable: nothing answered on the port; also a connection or channel that
//     failed after the login (Fix "AllowStreamLocalForwarding yes" when the host
//     refused the forward and the dial-stdio fallback failed too), a channel open the
//     host left unanswered, and a probe that ran out of time on every candidate.
//   - not_ssh: something answered, but not an SSH server.
//   - closed_before_login: the host hung up during the handshake or login, the connection
//     dropped (also after an answer without a reply: the host did not refuse it; the second
//     such login after a password is password_refused, see answerCloses), or the host left
//     an answer without a reply for 45 s (the next login's questions carry AfterStall).
//     Also the first two logins that the host turned away after a right password or a key
//     it took, with login_refused's sentence (see there).
//   - no_common_algorithm: the host offers only key exchanges, ciphers, MACs or host key
//     types the pool refuses (DSA or SHA-1 RSA host keys, CBC ciphers and the like).
//   - host_key_changed: the phone knows another key for this host:port. Fix: the
//     ssh-keygen command that prints the host's fingerprint.
//   - password_refused: the host refused a password or prompt answer; also a host that
//     closed the connection after a password, without a reply, in two logins
//     (errAnswersClosed).
//   - login_refused: the host turned the login away after the answer, or after a key it
//     took, and said why, as sshd's PAM account step does (pam_nologin while the host boots
//     or shuts down, an expired account). The sentence blames neither the password nor the
//     key; the host's first line is under it (hostWords). A banner and then a close, with
//     no refusal, as the password method and a key login get them, mean the password or the
//     key was right: the first two such logins are closed_before_login with the same
//     sentence, which the core retries, turnAwayPause apart (loginRefusals). How long they
//     are counted differs, since sshd logs one after a password as a failed password and
//     one after a key as a failed key (see turnAwayCount). Words in a keyboard-interactive round without
//     questions, which a refusal follows, stop at once: a wrong password can look the same
//     there. Such a round can also be the first, before any question (PAM's auth step
//     turning a locked account away): the same stop, and no other method is tried. None of
//     these is carried as a refused password, so a saved password stays.
//   - key_refused: the host took none of the keys the login offered and asks no password;
//     also a host that hung up while the keys went out, before any answer, with a
//     disconnect that says no tries are left, as sshd does once the refused keys reach its
//     MaxAuthTries (errKeysHungUp), and a host that closed the connection during the key
//     stage three logins in a row (errKeysClosed).
//     Fix: convenience B's command with the public line of the first key offered, as the
//     key page shows it (no fix, and another sentence, when the phone has no key to offer).
//     The first key is the phone key when Kotlin could open it (SetIdentities puts it
//     first); Go cannot tell, it only has the order it was given.
//   - declined: a question was declined or went unanswered for 120 s.
//   - not_installed: no rex answered the probe. Fix: rex server install TARGET.
//
// Every fix is a command the user pastes into a shell: what it takes from the target or the
// host goes in as one quoted word, so it cannot run or expand anything.
type Error struct {
	Code     string `json:"code"`
	Sentence string `json:"sentence"`
	Fix      string `json:"fix"`
}

func (e *Error) Error() string { return e.Sentence }

func errMalformed() *Error {
	return &Error{Code: "malformed", Sentence: "Invalid SSH address. Use ssh://user@host."}
}

func errMoreThanTarget() *Error {
	return &Error{Code: "malformed", Sentence: "Use only an SSH username, host, and optional port."}
}

func errNoHost() *Error {
	return &Error{Code: "malformed", Sentence: "The SSH address needs a host. Use ssh://user@host."}
}

func errHasPassword() *Error {
	return &Error{Code: "malformed", Sentence: "Remove the password from the SSH address. Velo uses a separate prompt if it needs a password."}
}

func errNoUser() *Error {
	return &Error{Code: "no_user", Sentence: "The SSH address needs a username. Use ssh://user@host."}
}

func errUnreachable(t Target) *Error {
	return &Error{Code: "unreachable", Sentence: "Velo couldn't reach " + t.Host + " on port " + strconv.Itoa(t.port()) + "."}
}

func errNotSSH(t Target) *Error {
	return &Error{Code: "not_ssh", Sentence: "Velo reached " + t.Host + ", but the port doesn't appear to be serving SSH."}
}

func errClosedBeforeLogin(t Target) *Error {
	return &Error{Code: "closed_before_login", Sentence: "The connection to " + t.Host + " ended before SSH sign-in completed."}
}

// errNoCommonAlgorithm is a host the pool cannot agree with on an algorithm: trying again
// meets the same offer.
func errNoCommonAlgorithm(t Target) *Error {
	return &Error{Code: "no_common_algorithm", Sentence: t.Host + " uses only SSH algorithms that Velo doesn't accept."}
}

func errPasswordRefused(t Target) *Error {
	return &Error{Code: "password_refused", Sentence: "Password rejected for " + t.User + " on " + t.Host + "."}
}

// errAnswersClosed is the password_refused of a host that closed the connection after a
// password, without a reply to it, in answerCloses logins (Dropbear hangs up on a wrong
// password before its refusal goes out). The core stops; the next login asks the user for
// the password (AfterStall), which the sentence says.
func errAnswersClosed(t Target) *Error {
	return &Error{Code: "password_refused", Sentence: t.Host + " kept closing the connection after Velo sent the password for " + t.User +
		". Reconnect to enter the password again."}
}

// errLoginRefused is a host that turned the login away after the answer or the key, saying why: said is
// its first line, cleaned (hostWords), and "" leaves the second line out. It is the host's
// text, so it goes under a label of its own and never into the first line.
func errLoginRefused(t Target, said string) *Error {
	sentence := t.Host + " didn't let " + t.User + " log in."
	if said != "" {
		sentence += "\n" + t.Host + " says: " + said
	}
	return &Error{Code: "login_refused", Sentence: sentence}
}

// maxHostWords is the most characters of the host's words a sentence shows (hostWords).
// pam_nologin's message while systemd boots is 141.
const maxHostWords = 160

// maxMarks is how many combining marks in a row the host's words keep on a character: Thai
// puts two on a letter, Hebrew's points three; more only pile up over the line.
const maxMarks = 3

// hostWords is the first line of text the host sent (a banner, PAM's words) as a sentence may
// show it: text from the host, held to what a key's comment may be (Kotlin's keyComment),
// but cleaned where that one is refused whole, since any language's words must stay
// readable. Only letters, marks, digits, punctuation, symbols and spaces stay: control and
// format characters (which hide or turn text round), line and paragraph separators,
// private-use and unassigned code points, bytes that are not UTF-8 and characters that draw
// nothing (blank) go; a terminal's escape sequences (those ESC begins) go whole; a combining
// mark stays only on a character before it, at most maxMarks in a row (a key's comment may
// have none). Spaces
// fold into one, and the line is cut to maxHostWords characters, at a space when one is near,
// ending in "…". "" when nothing visible is left.
func hostWords(text string) string {
	text = strings.ToValidUTF8(text, "")
	for line := range strings.FieldsFuncSeq(text, func(r rune) bool {
		return r == '\n' || r == '\r' || r == '\v' || r == '\f' || r == '\u0085' || r == '\u2028' || r == '\u2029'
	}) {
		if words := cleanLine(line); words != "" {
			return cutWords(words)
		}
	}
	return ""
}

// cleanLine keeps what hostWords shows of one line, its spaces folded.
func cleanLine(line string) string {
	var b strings.Builder
	space, marks, based := false, 0, false // based: the last character kept can carry a mark
	for i := 0; i < len(line); {
		r, size := utf8.DecodeRuneInString(line[i:])
		i += size
		switch {
		case r == 0x1b:
			i = pastEscape(line, i)
			continue
		case r == '\t' || unicode.Is(unicode.Zs, r):
			space, marks, based = b.Len() > 0, 0, false
			continue
		case blank(r):
			continue
		case unicode.In(r, unicode.Mn, unicode.Me):
			if marks++; !based || marks > maxMarks {
				continue
			}
		case unicode.Is(unicode.Mc, r): // a spacing mark: it joins its letter, and piles up nothing
			if !based {
				continue
			}
			marks = 0
		case unicode.In(r, unicode.L, unicode.N, unicode.P, unicode.S):
			marks, based = 0, true
		default:
			continue
		}
		if space {
			b.WriteByte(' ')
			space = false
		}
		b.WriteRune(r)
	}
	return b.String()
}

// pastEscape is where the terminal escape sequence that began with the ESC before at ends
// (ECMA-48), the line's end when it never does: a control sequence (ESC [, up to its final
// byte), a string (ESC ], P, X, ^ or _, up to BEL or ESC \), or ESC with intermediate
// bytes and a final one (ESC ( B, ESC =).
func pastEscape(line string, at int) int {
	if at >= len(line) {
		return at
	}
	switch c := line[at]; {
	case c == '[':
		for at++; at < len(line); at++ {
			if line[at] >= 0x40 && line[at] <= 0x7e {
				return at + 1
			}
		}
	case c == ']' || c == 'P' || c == 'X' || c == '^' || c == '_':
		for at++; at < len(line); at++ {
			if line[at] == 0x07 {
				return at + 1
			}
			if line[at] == 0x1b {
				if at+1 < len(line) && line[at+1] == '\\' {
					return at + 2
				}
				return at // no end of this one: the escape begins another sequence
			}
		}
	default:
		for ; at < len(line); at++ {
			if line[at] < 0x20 || line[at] > 0x2f { // the final byte, or one that is no part of a sequence
				if line[at] >= 0x30 && line[at] <= 0x7e {
					return at + 1
				}
				return at
			}
		}
	}
	return len(line)
}

// blank reports letters, symbols and marks that draw nothing: the Hangul fillers, the empty
// Braille pattern and the combining grapheme joiner.
func blank(r rune) bool {
	return r == 0x115f || r == 0x1160 || r == 0x3164 || r == 0xffa0 || r == 0x2800 || r == 0x034f
}

// cutWords cuts words to maxHostWords characters, "…" last: at the last space in the second
// half of what fits, else within the word.
func cutWords(words string) string {
	runes := []rune(words)
	if len(runes) <= maxHostWords {
		return words
	}
	cut := runes[:maxHostWords-1]
	for i := len(cut) - 1; i >= len(cut)/2; i-- {
		if cut[i] == ' ' {
			cut = cut[:i]
			break
		}
	}
	return strings.TrimSpace(string(cut)) + "…"
}

// errKeyRefused offers convenience B's command with the public line of the first key the
// login offered (the phone key, as Kotlin orders them, when it could open it), ending in
// comment as the key page shows it (Kotlin's sshInstallCommand, which quotes it the same
// way). Without a key nothing was refused: the phone had none to offer.
func errKeyRefused(t Target, signer ssh.Signer, comment string) *Error {
	if signer == nil {
		return &Error{Code: "key_refused", Sentence: t.Host + " asks for a key, and this phone has none to offer yet. Open Settings → SSH key."}
	}
	line := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey())))
	if comment != "" && !strings.ContainsFunc(comment, isControl) { // a newline would end the command
		line += " " + comment
	}
	return &Error{
		Code: "key_refused",
		Sentence: "SSH key rejected for " + t.User + " on " + t.Host + ". " +
			"On that host, append the public key to " + t.User + "'s ~/.ssh/authorized_keys file.",
		Fix: "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '" + strings.ReplaceAll(line, "'", `'\''`) + "' >> ~/.ssh/authorized_keys",
	}
}

// errKeysHungUp is a host that hung up while the login offered its keys, before any
// answer: its MaxAuthTries is no more than the keys offered. key_refused, which stops the
// core, with the same fix (the first key offered); with more than one key, fewer keys
// leave room for the password too.
func errKeysHungUp(t Target, ids []identity) *Error {
	e, keys, add := keyStageStop(t, ids)
	e.Sentence = t.Host + " hung up while Velo offered " + keys + " for " + t.User + ". " +
		"It may allow only a few login tries (MaxAuthTries). " + add
	return e
}

// errKeysClosed is a host that closed the connection while the login offered its keys,
// keyStageCloses logins in a row (a TCP close without an SSH disconnect, as sshd's exit on
// SIGTERM, or a disconnect that is not about the tries): key_refused, which stops the
// core's retries every few seconds, with the first key's fix: a key the host takes first
// gets the login past the key stage.
func errKeysClosed(t Target, ids []identity) *Error {
	e, keys, add := keyStageStop(t, ids)
	e.Sentence = t.Host + " kept closing the connection while Velo offered " + keys + " for " + t.User + ". " + add
	return e
}

// keyStageStop is the key_refused of a login the host ended during the key stage, with the
// first key's fix, and the parts of its sentence: the keys offered, and what to do.
func keyStageStop(t Target, ids []identity) (e *Error, keys, add string) {
	e = &Error{Code: "key_refused"}
	if len(ids) > 0 {
		e.Fix = errKeyRefused(t, ids[0].signer, ids[0].comment).Fix
	}
	keys, add = "this phone's key", "On that host, append the public key to "+t.User+"'s ~/.ssh/authorized_keys file."
	if len(ids) > 1 {
		keys = "this phone's " + strconv.Itoa(len(ids)) + " keys"
		add = "On that host, append the first public key to " + t.User + "'s ~/.ssh/authorized_keys file, or remove imported keys in Settings → SSH key."
	}
	return e, keys, add
}

// errHostKeyChanged's fix is the command that prints the host's own fingerprint of the
// key type it offered, to compare with the one the phone remembers.
func errHostKeyChanged(keyType string) *Error {
	file := "ed25519"
	switch {
	case strings.HasPrefix(keyType, "ecdsa-"):
		file = "ecdsa"
	case keyType == "ssh-rsa":
		file = "rsa"
	}
	return &Error{
		Code:     "host_key_changed",
		Sentence: "Velo blocked the connection because the SSH host key differs from the saved key. Verify the new fingerprint with the host administrator before trusting it.",
		Fix:      "ssh-keygen -lf /etc/ssh/ssh_host_" + file + "_key.pub",
	}
}

func errDeclined() *Error {
	return &Error{Code: "declined", Sentence: "Velo ended the connection attempt without an answer to the SSH prompt."}
}

// errNotInstalled names the target in its canonical form in the install command, so a
// host on another port gets a command that reaches it. The target goes in as one shell
// word (quoted when it has to be): the user pastes the command into a shell.
func errNotInstalled(t Target) *Error {
	install := "rex server install " + shellWord(t.String())
	return &Error{
		Code: "not_installed",
		Sentence: "Velo couldn't find Rex on " + t.Host + ". It checked the non-interactive shell's PATH, ~/.local/bin/rex, and the Rex.app bundle on macOS. " +
			"On a computer that already has Rex, run " + install + ".",
		Fix: install,
	}
}

// errConnectionClosed is an SSH connection that ended after the login, again and again.
func errConnectionClosed() *Error {
	return &Error{Code: "unreachable", Sentence: "Velo lost its SSH connection to the host."}
}

// errChannelFailed covers both a rejected channel and a failed remote command.
func errChannelFailed(reason string) *Error {
	return &Error{Code: "unreachable", Sentence: "Velo couldn't connect to Rex over SSH: " + reason + "."}
}

// errChannelTimeout is a host that took the login but left a channel open unanswered.
func errChannelTimeout(t Target) *Error {
	return errChannelFailed(t.Host + " didn't answer in time")
}

// errProbeSlow is a probe that ran out of time on a candidate and found no server on the
// others: the server may be slow to start, so it is worth another try.
func errProbeSlow(t Target) *Error {
	return &Error{Code: "unreachable", Sentence: t.Host + " took too long to start its Rex server."}
}

// errForwardRefused is a host that refuses the forward to the server socket and then the
// dial-stdio fallback too; allowing the forward in its sshd_config fixes it.
func errForwardRefused(reason string) *Error {
	return &Error{Code: "unreachable", Sentence: "Velo couldn't connect to Rex over SSH: " + reason + ".", Fix: "AllowStreamLocalForwarding yes"}
}
