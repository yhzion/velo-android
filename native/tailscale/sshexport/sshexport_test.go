package sshexport

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/crypto/ssh"
	"velo/tailscale/rexssh"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

// closedPort is a loopback address where nothing listens (a listener just closed).
func closedPort(t *testing.T) (host string, port int) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	a := ln.Addr().(*net.TCPAddr)
	ln.Close()
	return "127.0.0.1", a.Port
}

func TestCallsBeforeInitReturnEPERM(t *testing.T) {
	e := New(nil)
	if _, js, rc := e.Dial(-1, "ssh://me@127.0.0.1", time.Second); rc != int(syscall.EPERM) || js != nil {
		t.Errorf("Dial: rc %d json %s", rc, js)
	}
	if _, rc := e.Questions(); rc != int(syscall.EPERM) {
		t.Errorf("Questions: rc %d", rc)
	}
	if rc := e.Answer(1, "", false); rc != int(syscall.EPERM) {
		t.Errorf("Answer: rc %d", rc)
	}
	if rc := e.SetIdentities([]byte("[]")); rc != int(syscall.EPERM) {
		t.Errorf("SetIdentities: rc %d", rc)
	}
	if js, rc := e.Remembered("ssh://me@h"); rc != int(syscall.EPERM) || js != nil {
		t.Errorf("Remembered: rc %d json %s", rc, js)
	}
	if rc := e.ForgetSocket("ssh://me@h"); rc != int(syscall.EPERM) {
		t.Errorf("ForgetSocket: rc %d", rc)
	}
	if rc := e.InstallKey("ssh://me@h", "ssh-ed25519 AAAA", time.Second); rc != int(syscall.EPERM) {
		t.Errorf("InstallKey: rc %d", rc)
	}
	if rc := e.Forget("ssh://me@h"); rc != int(syscall.EPERM) {
		t.Errorf("Forget: rc %d", rc)
	}
}

func TestInitTwiceWithTheSameDirKeepsThePool(t *testing.T) {
	dir := t.TempDir()
	e := New(nil)
	if rc := e.Init(dir); rc != 0 {
		t.Fatalf("Init: %d", rc)
	}
	pool, queue := e.state()
	if rc := e.Init(dir + "/"); rc != 0 {
		t.Fatalf("second Init: %d", rc)
	}
	if p, q := e.state(); p != pool || q != queue {
		t.Error("the second Init replaced the pool or the queue")
	}
	if rc := e.Init(filepath.Join(dir, "other")); rc != int(syscall.EEXIST) {
		t.Errorf("Init with another dir: %d, want EEXIST", rc)
	}
}

func TestErrorForKeepsRexsshErrorsAndMapsTheRest(t *testing.T) {
	target := rexssh.Target{User: "me", Host: "mac.example", Port: 2222}
	own := &rexssh.Error{Code: "not_installed", Sentence: "s", Fix: "rex server install ssh://me@mac.example:2222"}
	if got := ErrorFor(target, own); got != own {
		t.Errorf("a *rexssh.Error came back as %+v", got)
	}
	for _, err := range []error{context.DeadlineExceeded, context.Canceled, errors.New("other")} {
		got := ErrorFor(target, err)
		want := rexssh.Error{Code: "unreachable", Sentence: "Velo couldn't reach mac.example on port 2222."}
		if *got != want {
			t.Errorf("%v → %+v, want %+v", err, *got, want)
		}
	}
}

func TestDialErrorJSON(t *testing.T) {
	e := New(nil)
	e.Init(t.TempDir())
	host, port := closedPort(t)
	cases := []struct {
		locator string
		want    map[string]string
	}{
		{"ssh://h", map[string]string{"code": "no_user", "sentence": "The SSH address needs a username. Use ssh://user@host.", "fix": ""}},
		{"ssh://-oProxyCommand=x@h", map[string]string{"code": "malformed", "sentence": "Invalid SSH address. Use ssh://user@host.", "fix": ""}},
		{"ssh://me@" + host + ":" + strconv.Itoa(port), map[string]string{"code": "unreachable", "sentence": "Velo couldn't reach " + host + " on port " + strconv.Itoa(port) + ".", "fix": ""}},
	}
	for _, c := range cases {
		fd, js, rc := e.Dial(-1, c.locator, 5*time.Second)
		if rc != -1 || fd != -1 {
			t.Errorf("%s: rc %d fd %d", c.locator, rc, fd)
			continue
		}
		var got map[string]string
		if err := json.Unmarshal(js, &got); err != nil {
			t.Errorf("%s: %v in %s", c.locator, err, js)
			continue
		}
		if len(got) != 3 || got["code"] != c.want["code"] || got["sentence"] != c.want["sentence"] || got["fix"] != c.want["fix"] {
			t.Errorf("%s: %s, want %v", c.locator, js, c.want)
		}
	}
}

// A dial whose time runs out while the host keeps quiet reports the host not answering.
func TestDialTimeoutIsUnreachable(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() { // accept and say nothing
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			defer c.Close()
		}
	}()
	e := New(nil)
	e.Init(t.TempDir())
	port := ln.Addr().(*net.TCPAddr).Port
	start := time.Now()
	_, js, rc := e.Dial(-1, "ssh://me@127.0.0.1:"+strconv.Itoa(port), 300*time.Millisecond)
	if rc != -1 {
		t.Fatalf("rc %d", rc)
	}
	if d := time.Since(start); d > 3*time.Second {
		t.Errorf("the dial took %v despite its 300 ms", d)
	}
	want := `{"code":"unreachable","sentence":"Velo couldn't reach 127.0.0.1 on port ` + strconv.Itoa(port) + `.","fix":""}`
	if string(js) != want {
		t.Errorf("got %s, want %s", js, want)
	}
}

func TestQuestionsJSONAndAnswer(t *testing.T) {
	e := New(nil)
	e.Init("")
	if js, rc := e.Questions(); rc != 0 || string(js) != "[]" {
		t.Fatalf("no questions: %s rc %d", js, rc)
	}
	_, queue := e.state()
	answered := make(chan string, 1)
	go func() {
		a, ok := queue.Ask(context.Background(), rexssh.Question{
			Kind: "hostkey", Target: "ssh://me@h", Text: "h:22", KeyType: "ssh-ed25519", Fingerprint: "SHA256:abc",
		})
		if !ok {
			a = "declined"
		}
		answered <- a
	}()
	var js []byte
	for deadline := time.Now().Add(5 * time.Second); ; {
		js, _ = e.Questions()
		if string(js) != "[]" || time.Now().After(deadline) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	want := `[{"id":1,"kind":"hostkey","target":"ssh://me@h","text":"h:22","keyType":"ssh-ed25519","fingerprint":"SHA256:abc","echo":false,"retry":false}]`
	if string(js) != want {
		t.Fatalf("questions %s, want %s", js, want)
	}
	if rc := e.Answer(1, "trust", true); rc != 0 {
		t.Fatalf("Answer: %d", rc)
	}
	if a := <-answered; a != "trust" {
		t.Errorf("the question got %q", a)
	}
	if rc := e.Answer(1, "trust", true); rc != int(syscall.ENOENT) {
		t.Errorf("a second answer: %d, want ENOENT", rc)
	}
}

func TestKeys(t *testing.T) {
	pemKey, line, rc := GenerateKey("velo")
	if rc != 0 || !strings.HasPrefix(line, "ssh-ed25519 ") || !strings.HasSuffix(line, " velo") {
		t.Fatalf("GenerateKey: rc %d line %q", rc, line)
	}
	e := New(nil)
	e.Init("")
	if rc := e.SetIdentities(keysJSON(t, pemKey)); rc != 0 {
		t.Errorf("SetIdentities(key): %d", rc)
	}
	if rc := e.SetIdentities([]byte("[]")); rc != 0 {
		t.Errorf("SetIdentities(clear): %d", rc)
	}
	if rc := e.SetIdentities(keysJSON(t, []byte("not a key"))); rc != KeyLeftOut+1 {
		t.Errorf("SetIdentities(garbage): %d, want KeyLeftOut+1", rc)
	}

	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	block, err := ssh.MarshalPrivateKeyWithPassphrase(priv, "", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	encrypted := pem.EncodeToMemory(block)
	if _, rc := DecryptKey(encrypted, ""); rc != int(syscall.EACCES) {
		t.Errorf("no passphrase: %d, want EACCES", rc)
	}
	if _, rc := DecryptKey(encrypted, "wrong"); rc != int(syscall.EACCES) {
		t.Errorf("wrong passphrase: %d, want EACCES", rc)
	}
	if _, rc := DecryptKey([]byte("garbage"), ""); rc != int(syscall.EINVAL) {
		t.Errorf("garbage: %d, want EINVAL", rc)
	}
	plain, rc := DecryptKey(encrypted, "secret")
	if rc != 0 {
		t.Fatalf("right passphrase: %d", rc)
	}
	if rc := e.SetIdentities(keysJSON(t, plain)); rc != 0 {
		t.Errorf("SetIdentities(decrypted): %d", rc)
	}
}

// keysJSON is keys as the JSON array of strings SetIdentities takes.
func keysJSON(t *testing.T, keys ...[]byte) []byte {
	t.Helper()
	strs := make([]string, len(keys))
	for i, k := range keys {
		strs[i] = string(k)
	}
	js, err := json.Marshal(strs)
	if err != nil {
		t.Fatal(err)
	}
	return js
}

func TestInstallKeyAndForgetCodes(t *testing.T) {
	e := New(nil)
	e.Init(t.TempDir())
	if rc := e.InstallKey("ssh://h", "ssh-ed25519 AAAA", time.Second); rc != int(syscall.EINVAL) {
		t.Errorf("InstallKey without a user: %d", rc)
	}
	_, line, _ := GenerateKey("")
	host, port := closedPort(t)
	if rc := e.InstallKey("ssh://me@"+host+":"+strconv.Itoa(port), line, 5*time.Second); rc != int(syscall.EIO) {
		t.Errorf("InstallKey on a closed port: %d, want EIO", rc)
	}
	if rc := e.Forget("ssh://h"); rc != int(syscall.EINVAL) {
		t.Errorf("Forget without a user: %d", rc)
	}
	if rc := e.Forget("ssh://me@h"); rc != 0 {
		t.Errorf("Forget: %d", rc)
	}
}

// The keys arrive as a JSON array of PEM strings, in the order logins offer them.
func TestSetIdentities(t *testing.T) {
	e := New(nil)
	e.Init("")
	k1, _, _ := GenerateKey("one")
	k2, _, _ := GenerateKey("two")
	both, err := json.Marshal([]string{string(k1), string(k2)})
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		name string
		js   []byte
		rc   int
	}{
		{"two keys", both, 0},
		{"none (clears)", []byte(" [ ] "), 0},
		{"one key", []byte(`["` + strings.ReplaceAll(string(k1), "\n", `\n`) + `"]`), 0},
		{"no JSON", []byte("-----BEGIN"), int(syscall.EINVAL)},
		{"null", []byte("null"), int(syscall.EINVAL)},
		{"an object", []byte(`{"a":"b"}`), int(syscall.EINVAL)},
		{"not a string", []byte("[1]"), int(syscall.EINVAL)},
		{"unfinished", []byte(`["a"`), int(syscall.EINVAL)},
		{"trailing data", append(append([]byte(nil), both...), "[]"...), int(syscall.EINVAL)},
		// A key that does not parse is left out; the code names the first one left out.
		{"not a key", []byte(`["not a key"]`), KeyLeftOut + 1},
		{"an empty key", []byte(`[""]`), KeyLeftOut + 1},
		{"a broken key after a good one", []byte(`["` + strings.ReplaceAll(string(k1), "\n", `\n`) + `", "x", ""]`), KeyLeftOut + 2},
		{"nil", nil, int(syscall.EINVAL)},
	} {
		if rc := e.SetIdentities(c.js); rc != c.rc {
			t.Errorf("%s: rc %d, want %d", c.name, rc, c.rc)
		}
	}
}

// decodeKeys turns the JSON array into the PEMs byte for byte, escapes and all.
func TestDecodeKeys(t *testing.T) {
	keys, err := decodeKeys([]byte(`["a\nb\u000ac\/\"\\", "", "\u00e9\ud83d\ude00"]`))
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"a\nb\nc/\"\\", "", "\u00e9\U0001F600"}
	if len(keys) != len(want) {
		t.Fatalf("keys = %q, want %q", keys, want)
	}
	for i := range want {
		if string(keys[i]) != want[i] {
			t.Errorf("key %d = %q, want %q", i, keys[i], want[i])
		}
	}
	if keys, err := decodeKeys([]byte("[]")); err != nil || keys == nil || len(keys) != 0 {
		t.Errorf("[] = %q %v, want an empty list", keys, err)
	}
}

// Remembered and ForgetSocket read and clear host-endpoints.json for one target, as JSON
// {"socket","refused_until"} (unix seconds, 0 when the host refuses nothing).
func TestRememberedAndForgetSocket(t *testing.T) {
	dir := t.TempDir()
	at := time.Now().Add(-time.Hour).Unix()
	file := `{"version":1,"ssh_sockets":{"ssh://me@h":"/run/rex.sock"},"refused":{"ssh://me@h":` + strconv.FormatInt(at, 10) + `}}`
	if err := os.WriteFile(filepath.Join(dir, "host-endpoints.json"), []byte(file), 0o600); err != nil {
		t.Fatal(err)
	}
	e := New(nil)
	e.Init(dir)
	js, rc := e.Remembered("ssh://me@h:22")
	want := `{"socket":"/run/rex.sock","refused_until":` + strconv.FormatInt(at+24*3600, 10) + `}`
	if rc != 0 || string(js) != want {
		t.Fatalf("Remembered: %s rc %d, want %s", js, rc, want)
	}
	if js, rc := e.Remembered("ssh://you@h"); rc != 0 || string(js) != `{"socket":"","refused_until":0}` {
		t.Errorf("Remembered of another target: %s rc %d", js, rc)
	}
	if _, rc := e.Remembered("ssh://h"); rc != int(syscall.EINVAL) {
		t.Errorf("Remembered without a user: %d", rc)
	}
	if rc := e.ForgetSocket("ssh://h"); rc != int(syscall.EINVAL) {
		t.Errorf("ForgetSocket without a user: %d", rc)
	}
	if rc := e.ForgetSocket("ssh://me@h"); rc != 0 {
		t.Fatalf("ForgetSocket: %d", rc)
	}
	if js, rc := e.Remembered("ssh://me@h"); rc != 0 || string(js) != `{"socket":"","refused_until":0}` {
		t.Errorf("after ForgetSocket: %s rc %d", js, rc)
	}
	b, err := os.ReadFile(filepath.Join(dir, "host-endpoints.json"))
	if err != nil || bytes.Contains(b, []byte("ssh://me@h")) {
		t.Errorf("host-endpoints.json after ForgetSocket: %s %v", b, err)
	}
}

// The C shapes set every out-parameter they were given to -1 or NULL before they check
// anything, so a caller that reads one after an error reads no leftover.
func TestOutParamsInitialised(t *testing.T) {
	var c fakeC
	junkByte := byte('x')
	junk := unsafe.Pointer(&junkByte)
	e := New(nil)
	e.Init(t.TempDir())

	// rexssh_dial: a NULL locator, a NULL conn_out, a failed dial, a NULL error_json_out.
	conn, errOut := int32(7), junk
	if rc := e.DialC(-1, nil, 0, &conn, &errOut, c.alloc); rc != int(syscall.EINVAL) || conn != -1 || errOut != nil {
		t.Errorf("dial, NULL locator: rc %d conn %d error %p, want EINVAL -1 NULL", rc, conn, errOut)
	}
	errOut = junk
	if rc := e.DialC(-1, []byte("ssh://me@h"), 0, nil, &errOut, c.alloc); rc != int(syscall.EINVAL) || errOut != nil {
		t.Errorf("dial, NULL conn_out: rc %d error %p, want EINVAL NULL", rc, errOut)
	}
	conn, errOut = 7, junk
	if rc := e.DialC(-1, []byte("ssh://h"), 0, &conn, &errOut, c.alloc); rc != -1 || conn != -1 || !strings.Contains(cstr(errOut), `"no_user"`) {
		t.Errorf("dial, no user: rc %d conn %d error %q", rc, conn, cstr(errOut))
	}
	conn = 7
	if rc := e.DialC(-1, []byte("ssh://h"), 0, &conn, nil, c.alloc); rc != -1 || conn != -1 {
		t.Errorf("dial, NULL error_json_out: rc %d conn %d", rc, conn)
	}
	conn, errOut = 7, junk
	if rc := New(nil).DialC(-1, []byte("ssh://me@h"), 0, &conn, &errOut, c.alloc); rc != int(syscall.EPERM) || conn != -1 || errOut != nil {
		t.Errorf("dial before init: rc %d conn %d error %p", rc, conn, errOut)
	}

	// rexssh_questions.
	js := junk
	if rc := New(nil).QuestionsC(&js, c.alloc); rc != int(syscall.EPERM) || js != nil {
		t.Errorf("questions before init: rc %d json %p", rc, js)
	}
	if rc := e.QuestionsC(nil, c.alloc); rc != int(syscall.EINVAL) {
		t.Errorf("questions, NULL json_out: rc %d", rc)
	}
	js = junk
	if rc := e.QuestionsC(&js, c.alloc); rc != 0 || cstr(js) != "[]" {
		t.Errorf("questions: rc %d json %q", rc, cstr(js))
	}

	// rexssh_generate_key: one NULL out-parameter still clears the other.
	pemOut, pubOut := junk, junk
	if rc := GenerateKeyC(nil, &pemOut, nil, c.alloc); rc != int(syscall.EINVAL) || pemOut != nil {
		t.Errorf("generate, NULL public_out: rc %d pem %p", rc, pemOut)
	}
	if rc := GenerateKeyC(nil, nil, &pubOut, c.alloc); rc != int(syscall.EINVAL) || pubOut != nil {
		t.Errorf("generate, NULL pem_out: rc %d public %p", rc, pubOut)
	}
	if rc := GenerateKeyC([]byte("velo"), &pemOut, &pubOut, c.alloc); rc != 0 ||
		!strings.HasPrefix(cstr(pemOut), "-----BEGIN OPENSSH PRIVATE KEY-----") || !strings.HasSuffix(cstr(pubOut), " velo") {
		t.Errorf("generate: rc %d public %q", rc, cstr(pubOut))
	}
	key := cstr(pemOut)

	// rexssh_decrypt_key: a NULL key, a NULL pem_out, a key that is no key.
	pemOut = junk
	if rc := DecryptKeyC(nil, nil, &pemOut, c.alloc); rc != int(syscall.EINVAL) || pemOut != nil {
		t.Errorf("decrypt, NULL key: rc %d pem %p, want EINVAL NULL", rc, pemOut)
	}
	if rc := DecryptKeyC([]byte(key), nil, nil, c.alloc); rc != int(syscall.EINVAL) {
		t.Errorf("decrypt, NULL pem_out: rc %d", rc)
	}
	pemOut = junk
	if rc := DecryptKeyC([]byte("garbage"), nil, &pemOut, c.alloc); rc != int(syscall.EINVAL) || pemOut != nil {
		t.Errorf("decrypt, garbage: rc %d pem %p", rc, pemOut)
	}
	if rc := DecryptKeyC([]byte(key), nil, &pemOut, c.alloc); rc != 0 || !strings.HasPrefix(cstr(pemOut), "-----BEGIN OPENSSH PRIVATE KEY-----") {
		t.Errorf("decrypt: rc %d", rc)
	}

	// rexssh_remembered: a NULL locator, a NULL json_out, a malformed locator, before init.
	js = junk
	if rc := e.RememberedC(nil, &js, c.alloc); rc != int(syscall.EINVAL) || js != nil {
		t.Errorf("remembered, NULL locator: rc %d json %p, want EINVAL NULL", rc, js)
	}
	if rc := e.RememberedC([]byte("ssh://me@h"), nil, c.alloc); rc != int(syscall.EINVAL) {
		t.Errorf("remembered, NULL json_out: rc %d", rc)
	}
	js = junk
	if rc := e.RememberedC([]byte("ssh://h"), &js, c.alloc); rc != int(syscall.EINVAL) || js != nil {
		t.Errorf("remembered, no user: rc %d json %p", rc, js)
	}
	js = junk
	if rc := New(nil).RememberedC([]byte("ssh://me@h"), &js, c.alloc); rc != int(syscall.EPERM) || js != nil {
		t.Errorf("remembered before init: rc %d json %p", rc, js)
	}
	if rc := e.RememberedC([]byte("ssh://me@h"), &js, c.alloc); rc != 0 || cstr(js) != `{"socket":"","refused_until":0}` {
		t.Errorf("remembered: rc %d json %q", rc, cstr(js))
	}
}

// fakeC stands in for ssh.go's cString: each string it hands out is a NUL-terminated copy.
type fakeC struct {
	mu   sync.Mutex
	strs [][]byte
}

func (f *fakeC) alloc(b []byte) unsafe.Pointer {
	s := append(append(make([]byte, 0, len(b)+1), b...), 0)
	f.mu.Lock()
	f.strs = append(f.strs, s)
	f.mu.Unlock()
	return unsafe.Pointer(&s[0])
}

// cstr reads a NUL-terminated string; "" for NULL.
func cstr(p unsafe.Pointer) string {
	if p == nil {
		return ""
	}
	n := 0
	for *(*byte)(unsafe.Add(p, n)) != 0 {
		n++
	}
	return string(unsafe.Slice((*byte)(p), n))
}

// Routing.

func status(running bool, suffix string, peers ...*ipnstate.PeerStatus) *ipnstate.Status {
	st := &ipnstate.Status{BackendState: "Stopped", Peer: map[key.NodePublic]*ipnstate.PeerStatus{}}
	if running {
		st.BackendState = "Running"
	}
	if suffix != "" {
		st.CurrentTailnet = &ipnstate.TailnetStatus{MagicDNSSuffix: suffix}
	}
	for _, p := range peers {
		st.Peer[key.NewNode().Public()] = p
	}
	return st
}

func peer(dns string, ips ...string) *ipnstate.PeerStatus {
	p := &ipnstate.PeerStatus{DNSName: dns}
	for _, ip := range ips {
		p.TailscaleIPs = append(p.TailscaleIPs, netip.MustParseAddr(ip))
	}
	return p
}

func TestTailnetAddr(t *testing.T) {
	mac := peer("mac.tail1234.ts.net.", "fd7a:115c:a1e0::1", "100.64.0.5")
	shared := peer("box.other.ts.net.", "100.64.0.9")
	running := status(true, "tail1234.ts.net", mac, shared)
	cases := []struct {
		st   *ipnstate.Status
		addr string
		want string // "" = not through the tailnet
	}{
		{running, "100.64.0.5:22", "100.64.0.5:22"},
		{running, "[fd7a:115c:a1e0::1]:22", "[fd7a:115c:a1e0::1]:22"},
		{running, "mac.tail1234.ts.net:2222", "100.64.0.5:2222"},
		{running, "MAC.tail1234.ts.net.:22", "100.64.0.5:22"},
		{running, "mac:22", "100.64.0.5:22"},
		{running, "box.other.ts.net:22", "100.64.0.9:22"},
		{running, "box:22", ""},                                      // a short name only in the node's own tailnet
		{running, "100.64.0.77:22", ""},                              // in 100.64.0.0/10 but no peer (NetBird and the like)
		{running, "10.0.0.10:22", ""},                                // the LAN
		{running, "example.com:22", ""},                              // anything else
		{status(false, "tail1234.ts.net", mac), "100.64.0.5:22", ""}, // not Running
		{nil, "100.64.0.5:22", ""},
	}
	for _, c := range cases {
		got, ok := tailnetAddr(c.st, c.addr)
		if ok != (c.want != "") || got != c.want {
			t.Errorf("%s: %q %v, want %q", c.addr, got, ok, c.want)
		}
	}
}

// An address that names a peer in another form dials the peer's own form: an IPv4-mapped
// IPv6 address as the IPv4 address, a zone dropped, IPv6 in its canonical text.
func TestTailnetAddrNormalised(t *testing.T) {
	st := status(true, "tail1234.ts.net", peer("mac.tail1234.ts.net.", "fd7a:115c:a1e0::1", "100.64.0.5"))
	for addr, want := range map[string]string{
		"[::ffff:100.64.0.5]:22":        "100.64.0.5:22",
		"[fd7a:115c:a1e0::1%eth0]:2222": "[fd7a:115c:a1e0::1]:2222",
		"[FD7A:115C:A1E0:0:0:0:0:1]:22": "[fd7a:115c:a1e0::1]:22",
		"[::ffff:100.64.0.5%wlan0]:22":  "100.64.0.5:22",
		"[::ffff:100.64.0.77]:22":       "",
		"[fd7a:115c:a1e0::2%eth0]:22":   "",
	} {
		got, ok := tailnetAddr(st, addr)
		if ok != (want != "") || got != want {
			t.Errorf("%s: %q %v, want %q", addr, got, ok, want)
		}
	}
}

type fakeTailnet struct {
	st      *ipnstate.Status
	connect func(ctx context.Context) (net.Conn, error) // nil: every dial fails
	mu      sync.Mutex
	statusd []int
	dialed  []string
}

func (f *fakeTailnet) Status(_ context.Context, sd int) (*ipnstate.Status, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.statusd = append(f.statusd, sd)
	return f.st, nil
}

func (f *fakeTailnet) Dial(ctx context.Context, sd int, network, addr string) (net.Conn, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.dialed = append(f.dialed, strconv.Itoa(sd)+" "+network+" "+addr)
	if f.connect != nil {
		return f.connect(ctx)
	}
	return nil, errors.New("fake tailnet: no route")
}

func (f *fakeTailnet) calls() ([]int, []string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]int(nil), f.statusd...), append([]string(nil), f.dialed...)
}

func TestDialRoutesThroughTheTailnetForPeersOnly(t *testing.T) {
	f := &fakeTailnet{st: status(true, "tail1234.ts.net", peer("mac.tail1234.ts.net.", "100.64.0.5"))}
	e := New(f)
	e.Init(t.TempDir())

	// A peer, with the node named: through the node, at the peer's address.
	_, js, _ := e.Dial(7, "ssh://me@mac:2222", 5*time.Second)
	if !bytes.Contains(js, []byte(`"Velo couldn't reach mac on port 2222."`)) {
		t.Errorf("peer: %s", js)
	}
	statusd, dialed := f.calls()
	if len(statusd) != 1 || statusd[0] != 7 || len(dialed) != 1 || dialed[0] != "7 tcp 100.64.0.5:2222" {
		t.Errorf("peer: status %v dialed %v", statusd, dialed)
	}

	// Not a peer: the system network (a closed loopback port), the node only asked.
	host, port := closedPort(t)
	_, js, _ = e.Dial(7, "ssh://me@"+host+":"+strconv.Itoa(port), 5*time.Second)
	if !bytes.Contains(js, []byte(`"unreachable"`)) {
		t.Errorf("not a peer: %s", js)
	}
	statusd, dialed = f.calls()
	if len(statusd) != 2 || len(dialed) != 1 {
		t.Errorf("not a peer: status %v dialed %v", statusd, dialed)
	}

	// sd < 0: the system network only, the node not even asked.
	e.Forget("ssh://me@" + host + ":" + strconv.Itoa(port))
	e.Dial(-1, "ssh://me@"+host+":"+strconv.Itoa(port), 5*time.Second)
	statusd, dialed = f.calls()
	if len(statusd) != 2 || len(dialed) != 1 {
		t.Errorf("sd -1: status %v dialed %v", statusd, dialed)
	}
}

// Forget drops the route of the target's host:port, unless another target the app dialed
// still uses it; routes do not pile up for remotes that are gone.
func TestForgetPrunesRoute(t *testing.T) {
	f := &fakeTailnet{st: status(true, "tail1234.ts.net", peer("mac.tail1234.ts.net.", "100.64.0.5"))}
	e := New(f)
	e.Init(t.TempDir())
	host, port := closedPort(t)
	lan := "ssh://me@" + host + ":" + strconv.Itoa(port)
	for _, locator := range []string{"ssh://me@mac:2222", "ssh://you@mac:2222", lan} {
		e.Dial(7, locator, 5*time.Second)
	}
	routes := func() map[string]int {
		e.mu.Lock()
		defer e.mu.Unlock()
		out := map[string]int{}
		for k, v := range e.routes {
			out[k] = v
		}
		return out
	}
	if r := routes(); len(r) != 2 || r["mac:2222"] != 7 {
		t.Fatalf("routes after the dials = %v", r)
	}
	if rc := e.Forget("ssh://me@mac:2222"); rc != 0 {
		t.Fatalf("Forget: %d", rc)
	}
	if r := routes(); r["mac:2222"] != 7 {
		t.Fatalf("forgetting me@mac dropped the route you@mac still uses: %v", r)
	}
	e.Forget("ssh://you@mac:2222")
	e.Forget(lan)
	if r := routes(); len(r) != 0 {
		t.Fatalf("routes after every target was forgotten = %v, want none", r)
	}
}

// Forget prunes the route before the pool drops the connection: a Dial of the same target
// that comes in meanwhile (here, while the pool closes the connection) sets the route again,
// and it stays, so that Dial's login still goes through the tailnet. Pruning after the pool's
// Forget dropped the route that Dial had just set.
func TestForgetPrunesRouteFirst(t *testing.T) {
	host := sshHost(t)
	f := &fakeTailnet{st: status(true, "tail1234.ts.net", peer("mac.tail1234.ts.net.", "100.64.0.5"))}
	e := New(f)
	e.Init(t.TempDir())
	const locator = "ssh://me@mac:2222"
	route := func() bool {
		e.mu.Lock()
		defer e.mu.Unlock()
		_, ok := e.routes["mac:2222"]
		return ok
	}
	var once sync.Once
	between := make(chan []byte, 1)
	f.connect = func(ctx context.Context) (net.Conn, error) {
		var d net.Dialer
		c, err := d.DialContext(ctx, "tcp", host)
		if err != nil {
			return nil, err
		}
		return &closeHook{Conn: c, hook: func() {
			once.Do(func() {
				go func() {
					_, js, _ := e.Dial(7, locator, 10*time.Second)
					between <- js
				}()
				for deadline := time.Now().Add(5 * time.Second); !route() && time.Now().Before(deadline); {
					time.Sleep(time.Millisecond)
				}
			})
		}}, nil
	}
	stop := answerHostKeys(t, e)
	defer stop()

	// The host has no rex: not_installed, but the login stays for the next Dial.
	if _, js, rc := e.Dial(7, locator, 10*time.Second); rc != -1 || !bytes.Contains(js, []byte(`"not_installed"`)) {
		t.Fatalf("first Dial: rc %d %s, want not_installed", rc, js)
	}
	if rc := e.Forget(locator); rc != 0 {
		t.Fatalf("Forget: %d", rc)
	}
	if !route() {
		t.Error("the route of a Dial that came in while Forget dropped the connection is gone")
	}
	select {
	case js := <-between:
		if !bytes.Contains(js, []byte(`"not_installed"`)) && !bytes.Contains(js, []byte(`"closed_before_login"`)) {
			t.Errorf("the Dial in between: %s", js)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("the Dial in between did not end")
	}
	if _, dialed := f.calls(); len(dialed) < 2 {
		t.Errorf("tailnet dials %q, want the Dial in between through the tailnet too", dialed)
	}
}

// closeHook runs hook when the connection is closed, before closing it.
type closeHook struct {
	net.Conn
	hook func()
}

func (c *closeHook) Close() error {
	c.hook()
	return c.Conn.Close()
}

// answerHostKeys trusts every host key the pool asks about until stop.
func answerHostKeys(t *testing.T, e *Exports) (stop func()) {
	t.Helper()
	done := make(chan struct{})
	ended := make(chan struct{})
	go func() {
		defer close(ended)
		for {
			select {
			case <-done:
				return
			case <-time.After(10 * time.Millisecond):
			}
			js, _ := e.Questions()
			var qs []rexssh.Question
			json.Unmarshal(js, &qs)
			for _, q := range qs {
				if q.Kind == "hostkey" {
					e.Answer(q.ID, "trust", true)
				}
			}
		}
	}()
	return func() { close(done); <-ended }
}

// sshHost is an SSH host on 127.0.0.1 that lets anyone in and has no rex: every command
// exits 127, as sh does for a missing one.
func sshHost(t *testing.T) string {
	t.Helper()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	cfg := &ssh.ServerConfig{NoClientAuth: true}
	cfg.AddHostKey(signer)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var conns []net.Conn
	t.Cleanup(func() {
		ln.Close()
		mu.Lock()
		defer mu.Unlock()
		for _, c := range conns {
			c.Close()
		}
	})
	go func() {
		for {
			nc, err := ln.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			conns = append(conns, nc)
			mu.Unlock()
			go func() {
				_, chans, reqs, err := ssh.NewServerConn(nc, cfg)
				if err != nil {
					nc.Close()
					return
				}
				go ssh.DiscardRequests(reqs)
				for nch := range chans {
					if nch.ChannelType() != "session" {
						nch.Reject(ssh.UnknownChannelType, "unknown channel type")
						continue
					}
					ch, chReqs, err := nch.Accept()
					if err != nil {
						continue
					}
					go func() {
						for req := range chReqs {
							req.Reply(req.Type == "exec", nil)
							if req.Type == "exec" {
								ch.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{127}))
								ch.Close()
							}
						}
					}()
				}
			}()
		}
	}()
	return ln.Addr().String()
}

// The socket hand-off.

func TestBridgeCarriesBothWaysAndTheCoreFdBlocks(t *testing.T) {
	remote, far := net.Pipe()
	fd, done, err := bridge(remote)
	if err != nil {
		t.Fatal(err)
	}
	flags, err := fcntlFlags(fd)
	if err != nil || flags&syscall.O_NONBLOCK != 0 {
		t.Errorf("the core's fd: flags %#x err %v, want blocking", flags, err)
	}
	if cloexec, err := fcntlFD(fd); err != nil || cloexec&syscall.FD_CLOEXEC == 0 {
		t.Errorf("the core's fd is not close-on-exec: %#x %v", cloexec, err)
	}
	core := coreFile(t, fd)
	defer core.Close()
	far.SetDeadline(time.Now().Add(5 * time.Second))

	go core.Write([]byte("GET /api/control HTTP/1.1\r\n"))
	buf := make([]byte, 64)
	n, err := io.ReadAtLeast(far, buf, len("GET /api/control HTTP/1.1\r\n"))
	if err != nil || string(buf[:n]) != "GET /api/control HTTP/1.1\r\n" {
		t.Errorf("core → remote: %q %v", buf[:n], err)
	}
	go far.Write([]byte("HTTP/1.1 101 Switching Protocols\r\n"))
	n, err = io.ReadAtLeast(core, buf, len("HTTP/1.1 101 Switching Protocols\r\n"))
	if err != nil || string(buf[:n]) != "HTTP/1.1 101 Switching Protocols\r\n" {
		t.Errorf("remote → core: %q %v", buf[:n], err)
	}
	far.Close()
	waitDone(t, done)
}

// The core writes its last bytes and closes its fd while the remote stays quiet: the
// remote gets the bytes, then the end, and no goroutine is left.
func TestBridgeCoreCloseEndsTheRemote(t *testing.T) {
	remote, far := net.Pipe()
	fd, done, err := bridge(remote)
	if err != nil {
		t.Fatal(err)
	}
	core := coreFile(t, fd)
	far.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := core.Write([]byte("bye")); err != nil {
		t.Fatal(err)
	}
	core.Close()
	got, err := io.ReadAll(far)
	if string(got) != "bye" || err != nil {
		t.Errorf("remote read %q %v, want \"bye\" then EOF", got, err)
	}
	waitDone(t, done)
	if _, err := far.Write([]byte("x")); err == nil {
		t.Error("the remote is still open")
	}
}

// The remote sends its last bytes and ends: the core reads them, then the end of the
// stream; its writes then fail, and no goroutine is left.
func TestBridgeRemoteCloseEndsTheCore(t *testing.T) {
	remote, far := net.Pipe()
	fd, done, err := bridge(remote)
	if err != nil {
		t.Fatal(err)
	}
	core := coreFile(t, fd)
	defer core.Close()
	go func() {
		far.Write([]byte("bye"))
		far.Close()
	}()
	got, err := io.ReadAll(core)
	if string(got) != "bye" || err != nil {
		t.Errorf("core read %q %v, want \"bye\" then EOF", got, err)
	}
	waitDone(t, done)
	if _, err := core.Write([]byte("x")); !errors.Is(err, syscall.EPIPE) {
		t.Errorf("core write after the end: %v, want EPIPE", err)
	}
}

// The core goes away while the remote is sending: the remote is closed too.
func TestBridgeCoreCloseWhileRemoteSends(t *testing.T) {
	remote, far := net.Pipe()
	fd, done, err := bridge(remote)
	if err != nil {
		t.Fatal(err)
	}
	stop := make(chan struct{})
	go func() {
		chunk := bytes.Repeat([]byte("x"), 4096)
		for {
			if _, err := far.Write(chunk); err != nil {
				close(stop)
				return
			}
		}
	}()
	time.Sleep(50 * time.Millisecond) // let the socket buffer fill
	syscall.Close(fd)
	waitDone(t, done)
	select {
	case <-stop:
	case <-time.After(5 * time.Second):
		t.Error("the remote writer was not stopped")
	}
}

// The core sends its last request and closes its fd while the remote is still sending a
// long reply, and the remote reads nothing until it has sent all of it (a server that
// writes its whole reply before it reads again, or an SSH window that is full): the bridge
// keeps reading the remote after its write to the core failed, so the remote gets to the
// request, and the stream ends.
func TestBridgeDrainsRemoteAfterLocalWriteError(t *testing.T) {
	remote, far := net.Pipe()
	fd, done, err := bridge(remote)
	if err != nil {
		t.Fatal(err)
	}
	core := coreFile(t, fd)
	if _, err := core.Write([]byte("last request")); err != nil {
		t.Fatal(err)
	}
	core.Close()
	far.SetDeadline(time.Now().Add(10 * time.Second))
	got := make(chan string, 1)
	go func() {
		chunk := bytes.Repeat([]byte("r"), 4096)
		for range 256 { // 1 MiB, more than a socket buffer holds
			if _, err := far.Write(chunk); err != nil {
				break
			}
		}
		b, _ := io.ReadAll(far)
		got <- string(b)
	}()
	waitDone(t, done)
	if s := <-got; s != "last request" {
		t.Errorf("the remote read %q, want the core's last request", s)
	}
}

// coreFile is the core's end as the core uses it (blocking), with read and write timeouts
// so a bridge that fails to end the stream fails the test instead of hanging it.
func coreFile(t *testing.T, fd int) *os.File {
	t.Helper()
	tv := syscall.Timeval{Sec: 5}
	if err := syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &tv); err != nil {
		t.Fatal(err)
	}
	if err := syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_SNDTIMEO, &tv); err != nil {
		t.Fatal(err)
	}
	return os.NewFile(uintptr(fd), "core")
}

func waitDone(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("the bridge's goroutines did not return")
	}
}

func fcntlFlags(fd int) (int, error) {
	r, _, errno := syscall.Syscall(syscall.SYS_FCNTL, uintptr(fd), syscall.F_GETFL, 0)
	if errno != 0 {
		return 0, errno
	}
	return int(r), nil
}

func fcntlFD(fd int) (int, error) {
	r, _, errno := syscall.Syscall(syscall.SYS_FCNTL, uintptr(fd), syscall.F_GETFD, 0)
	if errno != 0 {
		return 0, errno
	}
	return int(r), nil
}
