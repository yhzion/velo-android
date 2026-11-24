// The ssh:// pool (package rexssh) as C functions for the native core and Kotlin's JNI
// layer. build.sh adds this file to the libtailscale package (package main) with rexssh/
// and sshexport/ beside it; sshexport does the work and keeps the codes (see its package
// doc), so the host can test it.
//
// Strings come in as C strings the caller keeps; strings going out are malloc'd and
// NUL-terminated, and the caller frees them with free. C keeps no Go pointer. Every
// out-parameter given is set (-1 or NULL) before any argument is checked (sshexport's C
// shapes do it), so it never holds a leftover after an error.
//
//	int rexssh_init(char *state_dir);
//	int rexssh_dial(int sd, char *locator, int timeout_ms, int *conn_out, char **error_json_out);
//	int rexssh_questions(char **json_out);
//	int rexssh_answer(long long id, char *answer, int ok);
//	int rexssh_set_identities(char *json_array_of_pem);
//	int rexssh_generate_key(char *comment, char **pem_out, char **public_out);
//	int rexssh_decrypt_key(char *pem, char *passphrase, char **pem_out);
//	int rexssh_install_key(char *locator, char *public_line, int timeout_ms);
//	int rexssh_remembered(char *locator, char **json_out);
//	int rexssh_forget_socket(char *locator);
//	int rexssh_forget(char *locator);

package main

//#include <errno.h>
//#include <stdlib.h>
//#include <string.h>
import "C"

import (
	"context"
	"errors"
	"net"
	"time"
	"unsafe"

	"velo/tailscale/sshexport"
	"tailscale.com/ipn/ipnstate"
)

var sshExports = sshexport.New(sshTailnet{})

// sshTailnet reaches the app's tailnet node by its libtailscale handle.
type sshTailnet struct{}

var errNoTailnetNode = errors.New("rexssh: no started tailnet node")

func (sshTailnet) Status(ctx context.Context, sd int) (*ipnstate.Status, error) {
	s := getServer(C.int(sd))
	if s == nil || !s.started { // LocalClient would start a node that was never started
		return nil, errNoTailnetNode
	}
	lc, err := s.s.LocalClient()
	if err != nil {
		return nil, err
	}
	return lc.Status(ctx)
}

func (sshTailnet) Dial(ctx context.Context, sd int, network, addr string) (net.Conn, error) {
	s := getServer(C.int(sd))
	if s == nil {
		return nil, errNoTailnetNode
	}
	return s.s.Dial(ctx, network, addr)
}

// rexssh_init makes the process-wide pool, remembering server sockets in state_dir
// (host-endpoints.json). Again with the same directory it does nothing.
//
//export rexssh_init
func rexssh_init(stateDir *C.char) C.int {
	if stateDir == nil {
		return C.EINVAL
	}
	return C.int(sshExports.Init(C.GoString(stateDir)))
}

// rexssh_dial opens a stream to the Rex server of the ssh:// locator within timeout_ms
// (0: the pool's own limits) and writes the caller's end of a socket carrying it to
// *conn_out. sd is the app's tailnet node, or < 0 for the system network only. It returns
// -1 when the remote could not be reached, with *error_json_out =
// {"code","sentence","fix"} (the caller frees it; error_json_out may be NULL).
//
//export rexssh_dial
func rexssh_dial(sd C.int, locator *C.char, timeoutMs C.int, connOut *C.int, errorJSONOut **C.char) C.int {
	return C.int(sshExports.DialC(int(sd), goBytes(locator), millis(timeoutMs), intOut(connOut), strOut(errorJSONOut), cAlloc))
}

// rexssh_questions writes the JSON array of the questions waiting for an answer, oldest
// first, to *json_out (the caller frees it).
//
//export rexssh_questions
func rexssh_questions(jsonOut **C.char) C.int {
	return C.int(sshExports.QuestionsC(strOut(jsonOut), cAlloc))
}

// rexssh_answer answers question id (ok 0 declines it); ENOENT when it no longer waits.
//
//export rexssh_answer
func rexssh_answer(id C.longlong, answer *C.char, ok C.int) C.int {
	text := ""
	if answer != nil {
		text = C.GoString(answer)
	}
	return C.int(sshExports.Answer(int64(id), text, ok != 0))
}

// rexssh_set_identities sets the keys logins offer, in order (the phone key first, then
// imported keys as imported; a login offers the first five), from a JSON array of PEM
// strings (unencrypted OpenSSH or PEM). "[]" clears them. EINVAL: NULL or not such an
// array; the keys stay as they were. 1000+N (sshexport.KeyLeftOut): a key the app cannot
// log in with was left out, N the place of the first (from 1); the others are set.
//
//export rexssh_set_identities
func rexssh_set_identities(jsonArrayOfPEM *C.char) C.int {
	if jsonArrayOfPEM == nil {
		return C.EINVAL
	}
	js := goBytes(jsonArrayOfPEM)
	defer clear(js)
	return C.int(sshExports.SetIdentities(js))
}

// rexssh_generate_key makes an Ed25519 key: *pem_out its private half (unencrypted
// OpenSSH PEM), *public_out its authorized_keys line ending in comment. It needs no init.
//
//export rexssh_generate_key
func rexssh_generate_key(comment *C.char, pemOut, publicOut **C.char) C.int {
	return C.int(sshexport.GenerateKeyC(goBytes(comment), strOut(pemOut), strOut(publicOut), cAlloc))
}

// rexssh_decrypt_key opens an imported key with its passphrase (NULL or "" for none) and
// writes it as unencrypted OpenSSH PEM to *pem_out. EACCES: the passphrase is missing or
// wrong; EINVAL: not a key the app can use. It needs no init.
//
//export rexssh_decrypt_key
func rexssh_decrypt_key(pem, passphrase *C.char, pemOut **C.char) C.int {
	in, pass := goBytes(pem), goBytes(passphrase)
	defer clear(in)
	defer clear(pass)
	return C.int(sshexport.DecryptKeyC(in, pass, strOut(pemOut), cAlloc))
}

// rexssh_install_key appends public_line to ~/.ssh/authorized_keys on the target, once,
// over the pool's connection, within timeout_ms (0: the pool's own limits).
//
//export rexssh_install_key
func rexssh_install_key(locator, publicLine *C.char, timeoutMs C.int) C.int {
	if locator == nil || publicLine == nil {
		return C.EINVAL
	}
	return C.int(sshExports.InstallKey(C.GoString(locator), C.GoString(publicLine), millis(timeoutMs)))
}

// rexssh_remembered writes what the pool remembers for the target to *json_out (the caller
// frees it): {"socket","refused_until"}, the server socket ("" when none) and until when
// the host's refusal of the forward holds, in unix seconds (0 when none).
//
//export rexssh_remembered
func rexssh_remembered(locator *C.char, jsonOut **C.char) C.int {
	return C.int(sshExports.RememberedC(goBytes(locator), strOut(jsonOut), cAlloc))
}

// rexssh_forget_socket forgets the target's server socket and refusal; the connection and
// its streams stay, and the next stream probes again.
//
//export rexssh_forget_socket
func rexssh_forget_socket(locator *C.char) C.int {
	if locator == nil {
		return C.EINVAL
	}
	return C.int(sshExports.ForgetSocket(C.GoString(locator)))
}

// rexssh_forget drops the target's connection and its remembered socket and refusal.
//
//export rexssh_forget
func rexssh_forget(locator *C.char) C.int {
	if locator == nil {
		return C.EINVAL
	}
	return C.int(sshExports.Forget(C.GoString(locator)))
}

func millis(ms C.int) time.Duration {
	if ms <= 0 {
		return 0
	}
	return time.Duration(ms) * time.Millisecond
}

// goBytes copies a C string into Go memory, without a Go string the caller cannot clear:
// nil for NULL, and never nil for "".
func goBytes(s *C.char) []byte {
	if s == nil {
		return nil
	}
	b := C.GoBytes(unsafe.Pointer(s), C.int(C.strlen(s)))
	if b == nil {
		b = []byte{}
	}
	return b
}

// C int is 32 bits on every target this builds for; intOut relies on it.
var _ = [1]struct{}{}[unsafe.Sizeof(C.int(0))-4]

// intOut and strOut pass an int* and a char** to sshexport's C shapes, NULL as nil.
func intOut(p *C.int) *int32 { return (*int32)(unsafe.Pointer(p)) }

func strOut(p **C.char) *unsafe.Pointer { return (*unsafe.Pointer)(unsafe.Pointer(p)) }

// cAlloc is cString as sshexport.Alloc.
func cAlloc(b []byte) unsafe.Pointer { return unsafe.Pointer(cString(b)) }

// cString is b as a malloc'd NUL-terminated C string, without a Go string copy of b.
func cString(b []byte) *C.char {
	p := (*byte)(C.malloc(C.size_t(len(b) + 1)))
	out := unsafe.Slice(p, len(b)+1)
	copy(out, b)
	out[len(b)] = 0
	return (*C.char)(unsafe.Pointer(p))
}
