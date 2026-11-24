package rexssh

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"maps"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// The probe commands, verbatim from the Global Constraints (what the desktop sends).
const (
	probeRex      = `sh -c 'case "${REX_AUTOSTART-}" in ""|1|t|T|true|TRUE|True) rex server start --json;; *) rex server status --json;; esac'`
	probeLocalBin = `sh -c 'case "${REX_AUTOSTART-}" in ""|1|t|T|true|TRUE|True) ~/.local/bin/rex server start --json;; *) ~/.local/bin/rex server status --json;; esac'`
	probeMacApp   = `sh -c 'case "${REX_AUTOSTART-}" in ""|1|t|T|true|TRUE|True) ~/Library/Application\ Support/com.superlogical.rex/RexServer/Versions/Current/Rex\ Server.app/Contents/MacOS/rex server start --json;; *) ~/Library/Application\ Support/com.superlogical.rex/RexServer/Versions/Current/Rex\ Server.app/Contents/MacOS/rex server status --json;; esac'`
)

// asker records the questions it gets and answers them with fn.
type asker struct {
	mu sync.Mutex
	qs []Question
	fn func(Question) (string, bool)
}

func (a *asker) Ask(_ context.Context, q Question) (string, bool) {
	a.mu.Lock()
	a.qs = append(a.qs, q)
	a.mu.Unlock()
	return a.fn(q)
}

func (a *asker) questions() []Question {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]Question(nil), a.qs...)
}

func (a *asker) count(kind string) (n int) {
	for _, q := range a.questions() {
		if q.Kind == kind {
			n++
		}
	}
	return n
}

// ctxAsker is an asker whose fn sees the question's context, which ends when the pool
// withdraws the question.
type ctxAsker struct {
	mu sync.Mutex
	qs []Question
	fn func(context.Context, Question) (string, bool)
}

func (a *ctxAsker) Ask(ctx context.Context, q Question) (string, bool) {
	a.mu.Lock()
	a.qs = append(a.qs, q)
	a.mu.Unlock()
	return a.fn(ctx, q)
}

func (a *ctxAsker) questions() []Question {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]Question(nil), a.qs...)
}

// trusting trusts every host key and declines everything else.
func trusting() *asker {
	return &asker{fn: func(q Question) (string, bool) { return "trust", q.Kind == "hostkey" }}
}

func newTestPool(t *testing.T, dir string, ask Asker) *Pool {
	t.Helper()
	p := NewPool(dir, nil, ask)
	t.Cleanup(p.CloseAll)
	return p
}

// useKey gives the pool a fresh phone key and lets the server accept it.
func useKey(t *testing.T, p *Pool, srv *testServer) {
	t.Helper()
	pemKey, line, err := GenerateKey("test")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.SetIdentities([][]byte{pemKey}); err != nil {
		t.Fatal(err)
	}
	pub, _, _, _, err := ssh.ParseAuthorizedKey([]byte(line))
	if err != nil {
		t.Fatal(err)
	}
	srv.set(func(s *testServer) { s.keys = append(s.keys, pub) })
}

func ctxFor(t *testing.T, d time.Duration) context.Context {
	ctx, cancel := context.WithTimeout(context.Background(), d)
	t.Cleanup(cancel)
	return ctx
}

func mustOpen(t *testing.T, p *Pool, locator string) net.Conn {
	t.Helper()
	c, err := p.Open(ctxFor(t, 10*time.Second), locator)
	if err != nil {
		t.Fatalf("Open(%s): %v", locator, err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

// roundTrip writes msg on the stream and expects the server socket to have it and echo it.
func roundTrip(t *testing.T, c net.Conn, srv *testServer, msg string) {
	t.Helper()
	if _, err := io.WriteString(c, msg); err != nil {
		t.Fatalf("write: %v", err)
	}
	got := make(chan string, 1)
	go func() {
		buf := make([]byte, len(msg))
		n, _ := io.ReadFull(c, buf)
		got <- string(buf[:n])
	}()
	select {
	case echo := <-got:
		if echo != msg {
			t.Fatalf("echo = %q, want %q", echo, msg)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no echo from the server socket")
	}
	if !strings.Contains(srv.SocketBytes(), msg) {
		t.Fatalf("server socket got %q, want it to contain %q", srv.SocketBytes(), msg)
	}
}

func readEndpoints(t *testing.T, dir string) endpointFile {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(dir, "host-endpoints.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f endpointFile
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatalf("host-endpoints.json: %v\n%s", err, b)
	}
	return f
}

func asError(t *testing.T, err error) *Error {
	t.Helper()
	var e *Error
	if !errors.As(err, &e) {
		t.Fatalf("error = %v (%T), want *Error", err, err)
	}
	return e
}

func waitFor(t *testing.T, d time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(d)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("waited %v for %s", d, what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestOpenForwardsToTheProbedSocket(t *testing.T) {
	srv := newTestServer(t)
	srv.install("~/.local/bin/rex")
	dir := t.TempDir()
	p := newTestPool(t, dir, trusting())
	useKey(t, p, srv)

	c := mustOpen(t, p, srv.locator())
	roundTrip(t, c, srv, "hello rex")

	if got, want := srv.Commands(), []string{probeRex, probeLocalBin}; !equalStrings(got, want) {
		t.Fatalf("commands:\n%q\nwant\n%q", got, want)
	}
	if srv.Forwards() != 1 {
		t.Fatalf("forwards = %d, want 1", srv.Forwards())
	}
	f := readEndpoints(t, dir)
	if f.Version != 1 || f.SSHSockets[srv.locator()] != srv.socket() {
		t.Fatalf("host-endpoints.json = %+v, want version 1 and %s → %s", f, srv.locator(), srv.socket())
	}
	if _, ok := f.Refused[srv.locator()]; ok {
		t.Fatalf("refused entry written for a forward that worked: %+v", f)
	}
}

func TestSecondOpenReusesConnectionAndSocket(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	dir := t.TempDir()
	p := newTestPool(t, dir, trusting())
	useKey(t, p, srv)

	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "one")
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "two")
	if srv.Handshakes() != 1 || len(srv.Commands()) != 1 {
		t.Fatalf("handshakes = %d, commands = %q; want one of each", srv.Handshakes(), srv.Commands())
	}

	// After a restart the socket comes from host-endpoints.json: a new login, no probe.
	p2 := newTestPool(t, dir, trusting())
	useKey(t, p2, srv)
	roundTrip(t, mustOpen(t, p2, srv.locator()), srv, "three")
	if srv.Handshakes() != 2 || len(srv.Commands()) != 1 {
		t.Fatalf("after restart: handshakes = %d, commands = %q; want 2 and the one probe", srv.Handshakes(), srv.Commands())
	}
}

func TestConcurrentOpensAskOnce(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password = "pw" })
	a := &asker{fn: func(q Question) (string, bool) {
		switch q.Kind {
		case "hostkey":
			time.Sleep(300 * time.Millisecond) // both Opens are waiting by now
			return "trust", true
		case "password":
			return "pw", true
		}
		return "", false
	}}
	p := newTestPool(t, t.TempDir(), a)

	var wg sync.WaitGroup
	start := make(chan struct{})
	conns := make([]net.Conn, 2)
	errs := make([]error, 2)
	for i := range 2 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			conns[i], errs[i] = p.Open(ctxFor(t, 10*time.Second), srv.locator())
		}()
	}
	close(start)
	wg.Wait()
	for i := range 2 {
		if errs[i] != nil {
			t.Fatalf("Open %d: %v", i, errs[i])
		}
		defer conns[i].Close()
		roundTrip(t, conns[i], srv, fmt.Sprintf("stream %d", i))
	}
	if a.count("hostkey") != 1 || a.count("password") != 1 {
		t.Fatalf("questions = %+v, want one hostkey and one password question", a.questions())
	}
	if srv.Handshakes() != 1 || len(srv.Commands()) != 1 {
		t.Fatalf("handshakes = %d, commands = %q; want one login and one probe", srv.Handshakes(), srv.Commands())
	}
}

func TestRefusedForwardFallsBackToDialStdio(t *testing.T) {
	srv := newTestServer(t)
	srv.install("~/.local/bin/rex")
	srv.set(func(s *testServer) { s.refuseForward = true })
	dir := t.TempDir()
	p := newTestPool(t, dir, trusting())
	useKey(t, p, srv)
	dialStdio := "~/.local/bin/rex -S " + srv.socket() + " __internal dial-stdio"

	before := time.Now().Unix()
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "over stdio")
	after := time.Now().Unix()
	if got, want := srv.Commands(), []string{probeRex, probeLocalBin, dialStdio}; !equalStrings(got, want) {
		t.Fatalf("commands:\n%q\nwant\n%q", got, want)
	}
	if srv.Forwards() != 1 {
		t.Fatalf("forwards = %d, want 1", srv.Forwards())
	}
	f := readEndpoints(t, dir)
	if at := f.Refused[srv.locator()]; at < before || at > after {
		t.Fatalf("refused = %+v, want %s at a time in [%d, %d]", f.Refused, srv.locator(), before, after)
	}

	// Within the day: no forward, straight to dial-stdio, no probe.
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "again")
	if got, want := srv.Commands(), []string{probeRex, probeLocalBin, dialStdio, dialStdio}; !equalStrings(got, want) || srv.Forwards() != 1 {
		t.Fatalf("second Open: forwards = %d, commands = %q", srv.Forwards(), got)
	}

	// After a restart the refusal still holds; the rex that answers is found by probing again.
	p2 := newTestPool(t, dir, trusting())
	useKey(t, p2, srv)
	roundTrip(t, mustOpen(t, p2, srv.locator()), srv, "restarted")
	if srv.Forwards() != 1 {
		t.Fatalf("after restart: forwards = %d, want 1", srv.Forwards())
	}
	if cmds := srv.Commands(); cmds[len(cmds)-1] != dialStdio {
		t.Fatalf("after restart: last command %q, want %q", cmds[len(cmds)-1], dialStdio)
	}

	// A refusal older than a day: the forward is tried again. It fails on the remembered
	// socket, which could be gone, so the probe runs once more, and then on the socket the
	// probe just named: refused again, dial-stdio.
	f = readEndpoints(t, dir)
	f.Refused[srv.locator()] = time.Now().Add(-25 * time.Hour).Unix()
	b, _ := json.Marshal(f)
	if err := os.WriteFile(filepath.Join(dir, "host-endpoints.json"), b, 0o600); err != nil {
		t.Fatal(err)
	}
	p3 := newTestPool(t, dir, trusting())
	useKey(t, p3, srv)
	roundTrip(t, mustOpen(t, p3, srv.locator()), srv, "a day later")
	if srv.Forwards() != 3 {
		t.Fatalf("a day later: forwards = %d, want 3", srv.Forwards())
	}
	if at := readEndpoints(t, dir).Refused[srv.locator()]; at < after {
		t.Fatalf("a day later: refused time %d not renewed", at)
	}
}

func TestProbeIgnoresNoise(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.noise = true })
	dir := t.TempDir()
	p := newTestPool(t, dir, trusting())
	useKey(t, p, srv)

	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "through the banner")
	if got := readEndpoints(t, dir).SSHSockets[srv.locator()]; got != srv.socket() {
		t.Fatalf("socket = %q, want %q", got, srv.socket())
	}
}

func TestNoRexGivesTheInstallSentence(t *testing.T) {
	srv := newTestServer(t)
	p := newTestPool(t, t.TempDir(), trusting())
	useKey(t, p, srv)

	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	e := asError(t, err)
	target := fmt.Sprintf("ssh://rex@127.0.0.1:%d", srv.port)
	sentence := "Velo couldn't find Rex on 127.0.0.1. It checked the non-interactive shell's PATH, ~/.local/bin/rex, and the Rex.app bundle on macOS. " +
		"On a computer that already has Rex, run rex server install " + target + "."
	if e.Code != "not_installed" || e.Sentence != sentence || e.Fix != "rex server install "+target {
		t.Fatalf("error = %+v\nwant code not_installed, sentence %q, fix %q", e, sentence, "rex server install "+target)
	}
	if got, want := srv.Commands(), []string{probeRex, probeLocalBin, probeMacApp}; !equalStrings(got, want) {
		t.Fatalf("commands:\n%q\nwant\n%q", got, want)
	}
}

func TestHostKeyChangedRefusesWithoutAsking(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password = "pw" })
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "changed", true
		}
		return "pw", true
	}}
	p := newTestPool(t, t.TempDir(), a)

	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	e := asError(t, err)
	if e.Code != "host_key_changed" {
		t.Fatalf("error = %+v, want host_key_changed", e)
	}
	qs := a.questions()
	if len(qs) != 1 {
		t.Fatalf("questions = %+v, want only the host key question", qs)
	}
	want := Question{
		Kind:        "hostkey",
		Target:      srv.locator(),
		Text:        fmt.Sprintf("127.0.0.1:%d", srv.port),
		KeyType:     "ssh-ed25519",
		Fingerprint: ssh.FingerprintSHA256(srv.hostKey.PublicKey()),
	}
	if qs[0] != want {
		t.Fatalf("question = %+v\nwant %+v", qs[0], want)
	}
	if srv.Handshakes() != 0 || len(srv.Commands()) != 0 {
		t.Fatalf("handshakes = %d, commands = %q; want none", srv.Handshakes(), srv.Commands())
	}
}

func TestPasswordQuestionAndRefusal(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password = "s3cret" })
	q := NewQueue()
	p := newTestPool(t, t.TempDir(), q)

	// The script answers the questions in the order they come, through the Queue.
	type reply struct {
		kind, text string
		ok         bool
	}
	script := []reply{
		{"hostkey", "trust", true}, {"password", "wrong", true}, {"password", "", false}, // refused, then declined
		{"hostkey", "trust", true}, {"password", "", false}, // declined at once
		{"hostkey", "trust", true}, {"password", "s3cret", true}, // logs in
	}
	var mu sync.Mutex
	var asked []Question
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		for {
			select {
			case <-stop:
				return
			case <-time.After(10 * time.Millisecond):
			}
			for _, pq := range q.Pending() {
				mu.Lock()
				i := len(asked)
				asked = append(asked, pq)
				mu.Unlock()
				r := reply{"", "", false}
				if i < len(script) {
					r = script[i]
				}
				q.Answer(pq.ID, r.text, r.ok)
			}
		}
	}()

	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "password_refused" || e.Sentence != "Password rejected for rex on 127.0.0.1." {
		t.Fatalf("after a wrong password and a decline: %+v, want password_refused", e)
	}
	_, err = p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "declined" {
		t.Fatalf("after a decline: %+v, want declined", e)
	}
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "logged in")

	mu.Lock()
	defer mu.Unlock()
	if len(asked) != len(script) {
		t.Fatalf("asked %d questions, want %d: %+v", len(asked), len(script), asked)
	}
	for i, r := range script {
		if asked[i].Kind != r.kind {
			t.Fatalf("question %d is %q, want %q: %+v", i, asked[i].Kind, r.kind, asked)
		}
	}
	pw := asked[1]
	if pw.Text != "Enter the SSH password for rex on 127.0.0.1." || pw.Echo || pw.Retry || pw.Target != srv.locator() {
		t.Fatalf("first password question = %+v", pw)
	}
	if !asked[2].Retry || asked[4].Retry || asked[6].Retry {
		t.Fatalf("Retry flags = %v %v %v, want true false false", asked[2].Retry, asked[4].Retry, asked[6].Retry)
	}
	if len(q.Pending()) != 0 {
		t.Fatalf("questions left pending: %+v", q.Pending())
	}
}

func TestKeepaliveDropsADeadConnection(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	p := newTestPool(t, t.TempDir(), trusting())
	p.keepaliveInterval = 250 * time.Millisecond // production: 15 s (TestTimingsMatchTheDesktop)
	useKey(t, p, srv)
	locator := srv.locator()

	roundTrip(t, mustOpen(t, p, locator), srv, "alive")
	// Answered keepalives keep it: counted at the server, so a slow machine only waits longer.
	waitFor(t, 30*time.Second, "six answered keepalives", func() bool { return srv.Keepalives() >= 6 || srv.Closed() > 0 })
	if !p.connected(locator) || srv.Closed() != 0 {
		t.Fatalf("the pool dropped a connection after %d answered keepalives", srv.Keepalives())
	}
	srv.set(func(s *testServer) { s.silent = true })
	waitFor(t, 50*time.Second, "the pool to drop the silent connection", func() bool {
		return srv.Closed() == 1 && !p.connected(locator)
	})

	srv.set(func(s *testServer) { s.silent = false })
	roundTrip(t, mustOpen(t, p, locator), srv, "back")
	if srv.Handshakes() != 2 || len(srv.Commands()) != 1 {
		t.Fatalf("handshakes = %d, commands = %q; want a new login and no new probe", srv.Handshakes(), srv.Commands())
	}
}

func TestTimingsMatchTheDesktop(t *testing.T) {
	p := NewPool(t.TempDir(), nil, nil)
	if p.keepaliveInterval != 15*time.Second || p.keepaliveMax != 3 || p.connectTimeout != 10*time.Second || questionTimeout != 120*time.Second {
		t.Fatalf("keepalive %v × %d, connect %v, question %v; want 15s × 3, 10s, 120s",
			p.keepaliveInterval, p.keepaliveMax, p.connectTimeout, questionTimeout)
	}
	if p.probeTimeout != 30*time.Second || p.channelTimeout != 30*time.Second {
		t.Fatalf("probe %v, channel open %v; want 30s each", p.probeTimeout, p.channelTimeout)
	}
	// OpenSSH bounds its packet reads by ServerAliveInterval × ServerAliveCountMax.
	if p.stepTimeout != 45*time.Second {
		t.Fatalf("login step %v, want 45s", p.stepTimeout)
	}
}

func TestInstallKeyAppendsOnce(t *testing.T) {
	srv := newTestServer(t) // no rex: installing a key needs only the login
	p := newTestPool(t, t.TempDir(), trusting())
	useKey(t, p, srv)
	_, line, err := GenerateKey("phone")
	if err != nil {
		t.Fatal(err)
	}

	for range 2 {
		if err := p.InstallKey(ctxFor(t, 10*time.Second), srv.locator(), line); err != nil {
			t.Fatal(err)
		}
	}
	sshDir := filepath.Join(srv.home, ".ssh")
	b, err := os.ReadFile(filepath.Join(sshDir, "authorized_keys"))
	if err != nil {
		t.Fatal(err)
	}
	if string(b) != line+"\n" {
		t.Fatalf("authorized_keys = %q, want the line once", b)
	}
	if st, _ := os.Stat(sshDir); st.Mode().Perm() != 0o700 {
		t.Fatalf("~/.ssh mode = %v, want 700", st.Mode().Perm())
	}
	if st, _ := os.Stat(filepath.Join(sshDir, "authorized_keys")); st.Mode().Perm() != 0o600 {
		t.Fatalf("authorized_keys mode = %v, want 600", st.Mode().Perm())
	}
	cmds := srv.Commands()
	if len(cmds) != 2 || cmds[0] != cmds[1] {
		t.Fatalf("commands = %q, want the same install command twice", cmds)
	}
	if strings.Contains(cmds[0], strings.Fields(line)[1]) {
		t.Fatalf("the key travelled in the command line: %q", cmds[0])
	}

	// A line with options (command=, from=) would do more than authorize the key.
	if err := p.InstallKey(ctxFor(t, 10*time.Second), srv.locator(), `command="echo hi" `+line); err == nil {
		t.Fatal("a key line with options was installed")
	}
	if len(srv.Commands()) != 2 {
		t.Fatalf("commands = %q, want nothing run for the line with options", srv.Commands())
	}

	// A file without a final newline keeps its line; the key goes on a line of its own.
	_, otherLine, err := GenerateKey("other")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(sshDir, "authorized_keys"), []byte(otherLine), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := p.InstallKey(ctxFor(t, 10*time.Second), srv.locator(), line); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(sshDir, "authorized_keys")); string(b) != otherLine+"\n"+line+"\n" {
		t.Fatalf("authorized_keys = %q", b)
	}

	// The key is there already when its type and base64 are, whatever its comment or
	// options: the key_refused fix pasted by hand, a comment edited on the host.
	keyOnly := strings.Join(strings.Fields(line)[:2], " ")
	for _, there := range []string{
		keyOnly,                       // without a final newline
		keyOnly + "\n",                // as an older key_refused fix wrote it
		keyOnly + " edited comment\n", // another comment
		"# rex\n" + otherLine + "\n" + `from="10.0.0.0/8",no-pty ` + keyOnly + " x\n", // after options
		"\t" + keyOnly + "  \t velo\n",
	} {
		if err := os.WriteFile(filepath.Join(sshDir, "authorized_keys"), []byte(there), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := p.InstallKey(ctxFor(t, 10*time.Second), srv.locator(), line); err != nil {
			t.Fatal(err)
		}
		if b, _ := os.ReadFile(filepath.Join(sshDir, "authorized_keys")); string(b) != there {
			t.Errorf("with %q there: authorized_keys = %q, want it unchanged", there, b)
		}
	}
	// The same base64 under another type, or the type alone, is not the key; nor is a
	// commented-out line (an old phone's key, revoked by hand), which sshd ignores.
	for _, near := range []string{
		"ssh-rsa " + strings.Fields(line)[1] + "\n",
		strings.Fields(line)[0] + " " + strings.Fields(otherLine)[1] + " " + strings.Fields(line)[1][:20] + "\n",
		"# " + keyOnly + " old phone, revoked\n",
		"#" + keyOnly + "\n",
		"  # " + keyOnly + "\n",
	} {
		if err := os.WriteFile(filepath.Join(sshDir, "authorized_keys"), []byte(near), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := p.InstallKey(ctxFor(t, 10*time.Second), srv.locator(), line); err != nil {
			t.Fatal(err)
		}
		if b, _ := os.ReadFile(filepath.Join(sshDir, "authorized_keys")); string(b) != near+line+"\n" {
			t.Errorf("with %q there: authorized_keys = %q, want the key added", near, b)
		}
	}
	// A host whose authorized_keys holds shell marks reads them as text.
	odd := `command="echo $(id) *" ssh-ed25519 AAAA'x` + "\n"
	if err := os.WriteFile(filepath.Join(sshDir, "authorized_keys"), []byte(odd), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := p.InstallKey(ctxFor(t, 10*time.Second), srv.locator(), line); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(sshDir, "authorized_keys")); string(b) != odd+line+"\n" {
		t.Errorf("authorized_keys = %q", b)
	}
}

func TestRejectsLeadingDash(t *testing.T) {
	var dials atomic.Int32
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		dials.Add(1)
		return nil, errors.New("no network in this test")
	}
	p := NewPool(t.TempDir(), dial, trusting())
	defer p.CloseAll()
	for _, locator := range []string{"ssh://-oProxyCommand=x@h", "ssh://me@-h", "ssh://me@-h:2222"} {
		_, err := p.Open(ctxFor(t, 5*time.Second), locator)
		if e := asError(t, err); e.Code != "malformed" {
			t.Fatalf("%s: %+v, want malformed", locator, e)
		}
		if err := p.InstallKey(ctxFor(t, 5*time.Second), locator, "ssh-ed25519 AAAA"); asError(t, err).Code != "malformed" {
			t.Fatalf("InstallKey %s: %v, want malformed", locator, err)
		}
	}
	if dials.Load() != 0 {
		t.Fatalf("dialed %d times, want none", dials.Load())
	}
}

func TestParseTarget(t *testing.T) {
	for _, tc := range []struct {
		locator, canonical string
		want               Target
	}{
		{"ssh://me@h", "ssh://me@h", Target{"me", "h", 22}},
		{"ssh://me@h:22", "ssh://me@h", Target{"me", "h", 22}},
		{"ssh://rex@127.0.0.1:2222", "ssh://rex@127.0.0.1:2222", Target{"rex", "127.0.0.1", 2222}},
		{"ssh://me@[::1]:2222", "ssh://me@[::1]:2222", Target{"me", "::1", 2222}},
		{"ssh://me@[::1]", "ssh://me@[::1]", Target{"me", "::1", 22}},
		{"SSH://me@host.example/", "ssh://me@host.example", Target{"me", "host.example", 22}},
	} {
		got, err := ParseTarget(tc.locator)
		if err != nil || got != tc.want || got.String() != tc.canonical {
			t.Fatalf("ParseTarget(%q) = %+v (%q), %v; want %+v (%q)", tc.locator, got, got.String(), err, tc.want, tc.canonical)
		}
	}
	for _, tc := range []struct{ locator, code, guidance string }{
		{"ssh://h", "no_user", "needs a username"},
		{"ssh://me:fixture-secret@h", "malformed", "Remove the password"},
		{"ssh://me:fixture-secret@h/tmp", "malformed", "optional port"},
		{"ssh://me:fixture-secret@", "malformed", "needs a host"},
		{"ssh://:fixture-secret@h", "no_user", "needs a username"},
		{"ssh://me:fixture-secret@h:bad", "malformed", "ssh://user@host"},
		{"ssh://me@h/tmp", "malformed", "optional port"},
		{"ssh://me@", "malformed", "needs a host"},
		{"https://h", "malformed", "ssh://user@host"},
		{"ssh://me@h:0", "malformed", "ssh://user@host"},
		{"ssh://me@h:99999", "malformed", "ssh://user@host"},
		{"ssh://m e@h", "malformed", "ssh://user@host"},
	} {
		_, err := ParseTarget(tc.locator)
		var e *Error
		if !errors.As(err, &e) || e.Code != tc.code || !strings.Contains(e.Sentence, tc.guidance) || strings.Contains(e.Sentence, "fixture-secret") {
			t.Fatalf("ParseTarget(%q) error = %v, want %s with guidance %q and no credentials", tc.locator, err, tc.code, tc.guidance)
		}
	}
}

// A user or host that a shell would read as more than a word is malformed: only letters,
// digits, ".", "_" and "-" (the core's and Kotlin's rule for hosts, and now for users).
func TestRejectsShellMarksInTheLogin(t *testing.T) {
	for _, locator := range []string{
		"ssh://a$(rm$IFS-rf$IFS~)@evil.example", // the review's example: it parsed, and its fix ran it
		"ssh://a`id`@h", "ssh://a;b@h", "ssh://a'b@h", "ssh://~a@h", "ssh://a*@h", "ssh://a&b@h",
		"ssh://a(b)@h", "ssh://a!b@h", "ssh://a+b@h", "ssh://a,b@h", "ssh://a=b@h", "ssh://a%24b@h",
		"ssh://me@$(x)", "ssh://me@a;b", "ssh://me@a'b", "ssh://me@a*b", "ssh://me@[::1%25eth0]",
		"ssh://me@" + strings.Repeat("a", 254),
	} {
		_, err := ParseTarget(locator)
		var e *Error
		if !errors.As(err, &e) || e.Code != "malformed" {
			t.Errorf("ParseTarget(%q) = %v, want malformed", locator, err)
		}
	}
	for _, locator := range []string{"ssh://first.last_1-x@host-1.example", "ssh://Me@H_1", "ssh://me@[fd7a::1]:2222", "ssh://me@[::ffff:192.0.2.1]"} {
		if _, err := ParseTarget(locator); err != nil {
			t.Errorf("ParseTarget(%q): %v", locator, err)
		}
	}
}

// runFix runs a fix the way a user pastes it into a shell, with rex standing in as a
// function that prints its arguments one per line, in a throwaway HOME.
func runFix(t *testing.T, fix string) string {
	t.Helper()
	cmd := exec.Command("/bin/sh", "-c", `rex() { printf '%s\n' "$@"; }; `+fix)
	cmd.Env = []string{"PATH=/usr/bin:/bin", "HOME=" + t.TempDir()}
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("%s: %v\n%s", fix, err, out)
	}
	return string(out)
}

// Every fix that names the target hands it to the shell as one word, whatever the target
// holds: nothing in it is run, expanded or split.
func TestFixesAreShellSafe(t *testing.T) {
	for _, target := range []Target{
		{User: "a$(echo${IFS}pwned)", Host: "evil.example", Port: 22}, // what ParseTarget let through
		{User: "a`echo pwned`;echo pwned", Host: "h", Port: 2222},
		{User: "it's", Host: "h", Port: 22},
		{User: "me", Host: "::1", Port: 2222}, // brackets are a glob
		{User: "me", Host: "studio.example", Port: 22},
	} {
		e := errNotInstalled(target)
		if got, want := runFix(t, e.Fix), "server\ninstall\n"+target.String()+"\n"; got != want {
			t.Errorf("%+v: the fix %q gave rex %q, want %q", target, e.Fix, got, want)
		}
		if !strings.Contains(e.Sentence, e.Fix) {
			t.Errorf("%+v: the sentence %q does not name the fix %q", target, e.Sentence, e.Fix)
		}
	}
	// An ordinary hostname and username make a single shell word without quotes.
	if e := errNotInstalled(Target{User: "me", Host: "studio.example", Port: 22}); e.Fix != "rex server install ssh://me@studio.example" {
		t.Errorf("fix = %q", e.Fix)
	}
}

func TestGenerateKeyRoundTrip(t *testing.T) {
	pemKey, line, err := GenerateKey("velo")
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.ParsePrivateKey(pemKey)
	if err != nil {
		t.Fatalf("PEM does not parse: %v\n%s", err, pemKey)
	}
	if !strings.HasPrefix(line, "ssh-ed25519 ") || !strings.HasSuffix(line, " velo") {
		t.Fatalf("public line = %q", line)
	}
	pub, comment, _, _, err := ssh.ParseAuthorizedKey([]byte(line))
	if err != nil || comment != "velo" || string(pub.Marshal()) != string(signer.PublicKey().Marshal()) {
		t.Fatalf("public line %q does not match the private key (%v)", line, err)
	}
}

func TestDecryptKey(t *testing.T) {
	pemKey, line, err := GenerateKey("imported")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := ssh.ParseRawPrivateKey(pemKey)
	if err != nil {
		t.Fatal(err)
	}
	block, err := ssh.MarshalPrivateKeyWithPassphrase(raw, "imported", []byte("open sesame"))
	if err != nil {
		t.Fatal(err)
	}
	encrypted := pem.EncodeToMemory(block)

	if _, err := DecryptKey(encrypted, "wrong"); err == nil {
		t.Fatal("a wrong passphrase decrypted the key")
	}
	plain, err := DecryptKey(encrypted, "open sesame")
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.ParsePrivateKey(plain) // unencrypted now
	if err != nil {
		t.Fatalf("decrypted key does not parse without a passphrase: %v", err)
	}
	pub, _, _, _, _ := ssh.ParseAuthorizedKey([]byte(line))
	if string(signer.PublicKey().Marshal()) != string(pub.Marshal()) {
		t.Fatal("decrypted key is another key")
	}
	if again, err := DecryptKey(plain, ""); err != nil || len(again) == 0 {
		t.Fatalf("an unencrypted key with no passphrase: %v", err)
	}
}

func TestQueueWithdrawsAnUnansweredQuestion(t *testing.T) {
	q := NewQueue()
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	done := make(chan bool)
	go func() {
		_, ok := q.Ask(ctx, Question{Kind: "password", Target: "ssh://me@h"})
		done <- ok
	}()
	waitFor(t, 2*time.Second, "the question to be pending", func() bool { return len(q.Pending()) == 1 })
	id := q.Pending()[0].ID
	if ok := <-done; ok {
		t.Fatal("an unanswered question came back answered")
	}
	if len(q.Pending()) != 0 || q.Answer(id, "late", true) {
		t.Fatal("the question was not withdrawn")
	}

	go func() {
		text, ok := q.Ask(context.Background(), Question{Kind: "prompt"})
		done <- ok && text == "123456"
	}()
	waitFor(t, 2*time.Second, "the second question", func() bool { return len(q.Pending()) == 1 })
	if !q.Answer(q.Pending()[0].ID, "123456", true) || !<-done {
		t.Fatal("the answer did not reach the asker")
	}
}

// fakeTCP serves one reply on 127.0.0.1 to every connection, then hangs up.
func fakeTCP(t *testing.T, reply string) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			io.WriteString(c, reply)
			c.Close()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port
}

func TestLoginFailuresHaveDistinctCodesAndDiagnostics(t *testing.T) {
	p := newTestPool(t, "", trusting())
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	closedPort := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	for _, tc := range []struct {
		name, code, sentence string
		port                 int
	}{
		{"nothing listens", "unreachable", fmt.Sprintf("Velo couldn't reach 127.0.0.1 on port %d.", closedPort), closedPort},
		{"an HTTP server", "not_ssh", "Velo reached 127.0.0.1, but the port doesn't appear to be serving SSH.", fakeTCP(t, "HTTP/1.1 400 Bad Request\r\n\r\n")},
		{"a hang-up after the banner", "closed_before_login", "The connection to 127.0.0.1 ended before SSH sign-in completed.", fakeTCP(t, "SSH-2.0-OpenSSH_9.6\r\n")},
	} {
		_, err := p.Open(ctxFor(t, 15*time.Second), fmt.Sprintf("ssh://rex@127.0.0.1:%d", tc.port))
		if e := asError(t, err); e.Code != tc.code || e.Sentence != tc.sentence {
			t.Fatalf("%s: %+v, want %s %q", tc.name, e, tc.code, tc.sentence)
		}
	}
}

func TestKeyRefused(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	_, other, err := GenerateKey("someone else")
	if err != nil {
		t.Fatal(err)
	}
	pub, _, _, _, _ := ssh.ParseAuthorizedKey([]byte(other))
	srv.set(func(s *testServer) { s.keys = []ssh.PublicKey{pub} })
	sentence := "SSH key rejected for rex on 127.0.0.1. On that host, append the public key to rex's ~/.ssh/authorized_keys file."

	// The host takes neither the phone's key nor a password: convenience B's command.
	a := trusting()
	p := newTestPool(t, "", a)
	pemKey, line, err := GenerateKey("phone")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.SetIdentities([][]byte{pemKey}); err != nil {
		t.Fatal(err)
	}
	_, err = p.Open(ctxFor(t, 10*time.Second), srv.locator())
	// The line the key page shows, comment and all (Kotlin's sshInstallCommand of it).
	fix := "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '" + line + "' >> ~/.ssh/authorized_keys"
	if e := asError(t, err); e.Code != "key_refused" || e.Sentence != sentence || e.Fix != fix || len(a.questions()) != 1 {
		t.Fatalf("key refused: %+v after %d questions\nwant key_refused %q, fix %q, after the host key", e, len(a.questions()), sentence, fix)
	}

	// No phone key at all: no key was offered, so nothing was refused; nothing to copy.
	_, err = newTestPool(t, "", trusting()).Open(ctxFor(t, 10*time.Second), srv.locator())
	noKey := "127.0.0.1 asks for a key, and this phone has none to offer yet. Open Settings → SSH key."
	if e := asError(t, err); e.Code != "key_refused" || e.Sentence != noKey || e.Fix != "" {
		t.Fatalf("no key: %+v, want key_refused %q without a fix", e, noKey)
	}

	// A host that also takes a password asks for it; declining it is declined.
	srv.set(func(s *testServer) { s.password = "pw" })
	a3 := trusting()
	p3 := newTestPool(t, "", a3)
	if err := p3.SetIdentities([][]byte{pemKey}); err != nil {
		t.Fatal(err)
	}
	_, err = p3.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "declined" || a3.count("password") != 1 {
		t.Fatalf("declined password: %+v after %+v, want declined after one password question", e, a3.questions())
	}
}

func TestAlgorithmsFollowOpenSSH(t *testing.T) {
	for _, tc := range []struct {
		name            string
		got, want, impl []string
	}{
		{"kex", kexAlgos, []string{
			"mlkem768x25519-sha256", "curve25519-sha256", "ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521",
			"diffie-hellman-group-exchange-sha256", "diffie-hellman-group16-sha512", "diffie-hellman-group14-sha256",
		}, ssh.SupportedAlgorithms().KeyExchanges},
		{"ciphers", cipherAlgos, []string{
			"chacha20-poly1305@openssh.com", "aes128-gcm@openssh.com", "aes256-gcm@openssh.com",
			"aes128-ctr", "aes192-ctr", "aes256-ctr",
		}, ssh.SupportedAlgorithms().Ciphers},
		{"macs", macAlgos, []string{
			"hmac-sha2-256-etm@openssh.com", "hmac-sha2-512-etm@openssh.com", "hmac-sha2-256", "hmac-sha2-512", "hmac-sha1",
		}, ssh.SupportedAlgorithms().MACs},
		{"host keys", hostKeyAlgos, []string{
			"ssh-ed25519-cert-v01@openssh.com", "ecdsa-sha2-nistp256-cert-v01@openssh.com",
			"ecdsa-sha2-nistp384-cert-v01@openssh.com", "ecdsa-sha2-nistp521-cert-v01@openssh.com",
			"rsa-sha2-512-cert-v01@openssh.com", "rsa-sha2-256-cert-v01@openssh.com",
			"ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
			"rsa-sha2-512", "rsa-sha2-256",
		}, ssh.SupportedAlgorithms().HostKeys},
	} {
		if !equalStrings(tc.got, tc.want) {
			t.Errorf("%s = %q\nwant %q", tc.name, tc.got, tc.want)
		}
		for _, a := range tc.got {
			if !slices.Contains(tc.impl, a) {
				t.Errorf("%s: %s is not one of x/crypto's secure algorithms", tc.name, a)
			}
		}
	}
}

func TestKeyboardInteractive(t *testing.T) {
	// A lone hidden "Password:" (PAM) is the password question.
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.kbd = []kbdQuestion{{text: "Password: ", want: "pw"}} })
	a := &asker{fn: func(q Question) (string, bool) {
		switch q.Kind {
		case "hostkey":
			return "trust", true
		case "password":
			return "pw", true
		}
		return "", false
	}}
	roundTrip(t, mustOpen(t, newTestPool(t, "", a), srv.locator()), srv, "pam")
	if qs := a.questions(); len(qs) != 2 || qs[1].Kind != "password" || qs[1].Text != "Enter the SSH password for rex on 127.0.0.1." {
		t.Fatalf("questions = %+v, want the host key and the password question", qs)
	}

	// Anything else is a prompt, shown as the host wrote it, with its echo; a wrong answer
	// is asked again with Retry.
	srv2 := newTestServer(t)
	srv2.install("rex")
	srv2.set(func(s *testServer) { s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}} })
	var prompts atomic.Int32
	a2 := &asker{fn: func(q Question) (string, bool) {
		switch q.Kind {
		case "hostkey":
			return "trust", true
		case "prompt":
			if prompts.Add(1) == 1 {
				return "000000", true
			}
			return "123456", true
		}
		return "", false
	}}
	roundTrip(t, mustOpen(t, newTestPool(t, "", a2), srv2.locator()), srv2, "otp")
	qs := a2.questions()
	if len(qs) != 3 || qs[1].Kind != "prompt" || qs[1].Text != "Verification code: " || !qs[1].Echo || qs[1].Retry || !qs[2].Retry {
		t.Fatalf("questions = %+v, want the host key and the prompt twice, the second with Retry", qs)
	}
}

func TestVanishedSocketIsProbedAgain(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	dir := t.TempDir()
	p := newTestPool(t, dir, trusting())
	useKey(t, p, srv)

	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "first socket")
	srv.moveSocket() // the server restarted elsewhere: the remembered socket is gone
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "second socket")

	if srv.Handshakes() != 1 || len(srv.Commands()) != 2 || srv.Forwards() != 3 {
		t.Fatalf("handshakes = %d, commands = %q, forwards = %d; want one login, two probes, three forwards",
			srv.Handshakes(), srv.Commands(), srv.Forwards())
	}
	f := readEndpoints(t, dir)
	if f.SSHSockets[srv.locator()] != srv.socket() || len(f.Refused) != 0 {
		t.Fatalf("host-endpoints.json = %+v, want the new socket %s and no refusal", f, srv.socket())
	}
}

func TestRefusedExecKeepsTheConnection(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.refuseExec = true })
	p := newTestPool(t, "", trusting())
	useKey(t, p, srv)

	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "unreachable" || !strings.HasPrefix(e.Sentence, "Velo couldn't connect to Rex over SSH: ") || !strings.Contains(e.Sentence, "rex server start") {
		t.Fatalf("refused exec: %+v, want the remote command refusal", e)
	}
	if !p.connected(srv.locator()) || srv.Closed() != 0 {
		t.Fatal("a refused exec closed the shared connection")
	}
	srv.set(func(s *testServer) { s.refuseExec = false })
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "same login")
	if srv.Handshakes() != 1 {
		t.Fatalf("handshakes = %d, want the first login kept", srv.Handshakes())
	}
}

// The fix of a refused key adds the line the key page shows: an imported key (no comment)
// and a comment a shell would read are handled, and running the fix adds exactly that line.
func TestKeyRefusedFixIsTheKeyPageLine(t *testing.T) {
	signer, err := ssh.ParsePrivateKey(mustKey(t, "it's $(echo pwned) `x`"))
	if err != nil {
		t.Fatal(err)
	}
	pub := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey())))
	for _, tc := range []struct{ name, comment, line string }{
		{"a quote and marks", "it's $(echo pwned) `x`", pub + " it's $(echo pwned) `x`"},
		{"no comment (an imported key)", "", pub},
		{"a control character", "a\nb", pub},
	} {
		fix := errKeyRefused(Target{User: "me", Host: "h", Port: 22}, signer, tc.comment).Fix
		home := t.TempDir()
		cmd := exec.Command("/bin/sh", "-c", fix)
		cmd.Env = []string{"PATH=/usr/bin:/bin", "HOME=" + home}
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("%s: %q: %v\n%s", tc.name, fix, err, out)
		}
		if b, _ := os.ReadFile(filepath.Join(home, ".ssh", "authorized_keys")); string(b) != tc.line+"\n" {
			t.Errorf("%s: the fix %q wrote %q, want %q", tc.name, fix, b, tc.line+"\n")
		}
	}

	// The comment comes from the key the pool logs in with; DecryptKey's output has none.
	p := NewPool("", nil, nil)
	pemKey, line, err := GenerateKey("velo")
	if err != nil {
		t.Fatal(err)
	}
	comments := func() (out []string) {
		p.mu.Lock()
		defer p.mu.Unlock()
		for _, id := range p.identities {
			out = append(out, id.comment)
		}
		return out
	}
	if err := p.SetIdentities([][]byte{pemKey}); err != nil || !equalStrings(comments(), []string{"velo"}) {
		t.Fatalf("comments %q (%v), want velo from %q", comments(), err, line)
	}
	plain, err := DecryptKey(pemKey, "")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.SetIdentities([][]byte{plain}); err != nil || !equalStrings(comments(), []string{""}) {
		t.Fatalf("imported key: comments %q (%v), want none", comments(), err)
	}
	if err := p.SetIdentities(nil); err != nil || len(comments()) != 0 {
		t.Fatal("clearing the key kept it")
	}
	other := mustKey(t, "imported")
	if err := p.SetIdentities([][]byte{pemKey, other}); err != nil || !equalStrings(comments(), []string{"velo", "imported"}) {
		t.Fatalf("two keys: comments %q (%v)", comments(), err)
	}
}

// mustKey is a new Ed25519 key as OpenSSH PEM with comment.
func mustKey(t *testing.T, comment string) []byte {
	t.Helper()
	pemKey, _, err := GenerateKey(comment)
	if err != nil {
		t.Fatal(err)
	}
	return pemKey
}

// A TCP connect that takes the whole connect timeout leaves the handshake its own time:
// the host answers as an SSH server, so it is not "not an SSH server".
func TestSlowConnectStillLogsIn(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		if deadline, ok := ctx.Deadline(); ok {
			time.Sleep(time.Until(deadline) + 20*time.Millisecond) // connected as the time ran out
		}
		var d net.Dialer
		return d.Dial(network, addr)
	}
	p := NewPool("", dial, trusting())
	p.connectTimeout = 300 * time.Millisecond
	t.Cleanup(p.CloseAll)
	useKey(t, p, srv)
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "after a slow connect")
}

// A host that offers only algorithms the pool refuses never shows its key: that is
// permanent, not a host that hung up (which the core retries for ever).
func TestNoCommonAlgorithm(t *testing.T) {
	rsaKey, err := rsa.GenerateKey(rand.Reader, 1024)
	if err != nil {
		t.Fatal(err)
	}
	rsaSigner, err := ssh.NewSignerFromKey(rsaKey)
	if err != nil {
		t.Fatal(err)
	}
	sha1Only, err := ssh.NewSignerWithAlgorithms(rsaSigner.(ssh.AlgorithmSigner), []string{ssh.KeyAlgoRSA})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name string
		set  func(s *testServer)
	}{
		{"CBC ciphers only", func(s *testServer) { s.ciphers = []string{ssh.InsecureCipherAES128CBC, ssh.InsecureCipherTripleDESCBC} }},
		{"SHA-1 RSA host key only", func(s *testServer) { s.hostKeyOnly = sha1Only }},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(tc.set)
		a := trusting()
		p := newTestPool(t, "", a)
		useKey(t, p, srv)
		_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		e := asError(t, err)
		if e.Code != "no_common_algorithm" || e.Sentence != "127.0.0.1 uses only SSH algorithms that Velo doesn't accept." || e.Fix != "" {
			t.Errorf("%s: %+v, want no_common_algorithm", tc.name, e)
		}
		if len(a.questions()) != 0 {
			t.Errorf("%s: asked %+v before the algorithms were agreed", tc.name, a.questions())
		}
	}
}

// A probe that runs past its time on every candidate is a host that is slow to start its
// server, which may answer next time: transient, not not_installed.
func TestSlowProbeIsNotNotInstalled(t *testing.T) {
	srv := newTestServer(t)
	srv.set(func(s *testServer) { s.hung["rex"] = true }) // the others are missing
	p := newTestPool(t, t.TempDir(), trusting())
	p.probeTimeout = 300 * time.Millisecond
	useKey(t, p, srv)

	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	e := asError(t, err)
	if e.Code != "unreachable" || e.Sentence != "127.0.0.1 took too long to start its Rex server." || e.Fix != "" {
		t.Fatalf("slow probe: %+v, want unreachable, took too long", e)
	}
	if got, want := srv.Commands(), []string{probeRex, probeLocalBin, probeMacApp}; !equalStrings(got, want) {
		t.Fatalf("commands:\n%q\nwant\n%q", got, want)
	}

	// Nothing hangs and nothing answers: that is not_installed, as before.
	srv.set(func(s *testServer) { s.hung = map[string]bool{} })
	_, err = p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "not_installed" {
		t.Fatalf("no rex: %+v, want not_installed", e)
	}

	// Once the server starts in time, the same login reaches it.
	srv.install("~/.local/bin/rex")
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "started")
}

// A host that keeps the connection alive (keepalives answered) but never answers a channel
// open fails the dial within the channel timeout, transient, and keeps the connection.
func TestUnansweredChannelOpenEnds(t *testing.T) {
	for _, refuseForward := range []bool{false, true} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(func(s *testServer) { s.refuseForward = refuseForward })
		p := newTestPool(t, "", trusting())
		p.channelTimeout = 300 * time.Millisecond
		useKey(t, p, srv)
		roundTrip(t, mustOpen(t, p, srv.locator()), srv, "before") // the socket and route are known

		srv.set(func(s *testServer) { s.ignoreOpens = true })
		start := time.Now()
		_, err := p.Open(ctxFor(t, 5*time.Second), srv.locator())
		e := asError(t, err)
		if e.Code != "unreachable" || !strings.Contains(e.Sentence, "didn't answer in time") {
			t.Fatalf("refuseForward %v: %+v, want unreachable, didn't answer in time", refuseForward, e)
		}
		if d := time.Since(start); d > 3*time.Second {
			t.Fatalf("refuseForward %v: took %v", refuseForward, d)
		}
		if !p.connected(srv.locator()) || srv.Closed() != 0 {
			t.Fatalf("refuseForward %v: the connection was dropped", refuseForward)
		}
		srv.set(func(s *testServer) { s.ignoreOpens = false })
		roundTrip(t, mustOpen(t, p, srv.locator()), srv, "after")
		if srv.Handshakes() != 1 {
			t.Fatalf("refuseForward %v: handshakes = %d, want the first login kept", refuseForward, srv.Handshakes())
		}
	}
}

// The probe's session (a channel open) and InstallKey's are bounded too.
func TestUnansweredSessionOpenEnds(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	p := newTestPool(t, "", trusting())
	p.probeTimeout = 300 * time.Millisecond
	useKey(t, p, srv)
	// Log in first (installing a key needs only the login), then go quiet.
	_, line, err := GenerateKey("phone")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.InstallKey(ctxFor(t, 10*time.Second), srv.locator(), line); err != nil {
		t.Fatal(err)
	}
	srv.set(func(s *testServer) { s.ignoreOpens = true })

	_, err = p.Open(ctxFor(t, 5*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "unreachable" || e.Sentence != "127.0.0.1 took too long to start its Rex server." {
		t.Fatalf("probe: %+v, want unreachable, took too long", e)
	}
	start := time.Now()
	if err := p.InstallKey(ctxFor(t, 500*time.Millisecond), srv.locator(), line); err == nil {
		t.Fatal("InstallKey worked on a host that answers no channel open")
	}
	if d := time.Since(start); d > 3*time.Second {
		t.Fatalf("InstallKey took %v past its 500ms", d)
	}
}

// A host that allows one session at a time (OpenSSH's MaxSessions 1) frees a session only
// after it has processed the client's close, so a session opened right after another can be
// refused ("no more sessions": connect failed, "open failed", as OpenSSH 9.6 says). The next probe
// candidate, dial-stdio right after the probe and a key install right after another wait
// that out on the same connection: no failure, and the probe does not start over.
func TestSessionLimitIsWaitedOut(t *testing.T) {
	srv := newTestServer(t)
	srv.install("~/.local/bin/rex")
	srv.set(func(s *testServer) { s.maxSessions, s.sessionLag = 1, 100*time.Millisecond })
	p := newTestPool(t, "", trusting())
	useKey(t, p, srv)

	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "one session at a time")
	if got, want := srv.Commands(), []string{probeRex, probeLocalBin}; !equalStrings(got, want) {
		t.Fatalf("commands:\n%q\nwant\n%q (each probe once)", got, want)
	}
	if srv.RefusedSessions() == 0 {
		t.Fatal("the host refused no session: the test did not reach its limit")
	}

	_, line, err := GenerateKey("phone")
	if err != nil {
		t.Fatal(err)
	}
	for range 2 { // the second right after the first one's session
		if err := p.InstallKey(ctxFor(t, 10*time.Second), srv.locator(), line); err != nil {
			t.Fatalf("InstallKey: %v", err)
		}
	}
	if srv.Handshakes() != 1 {
		t.Fatalf("handshakes = %d, want 1: a refused session is no reason to log in again", srv.Handshakes())
	}

	// The forward refused: dial-stdio's session comes right after the probe's.
	srv2 := newTestServer(t)
	srv2.install("~/.local/bin/rex")
	srv2.set(func(s *testServer) { s.maxSessions, s.sessionLag, s.refuseForward = 1, 100*time.Millisecond, true })
	p2 := newTestPool(t, "", trusting())
	useKey(t, p2, srv2)
	roundTrip(t, mustOpen(t, p2, srv2.locator()), srv2, "over stdio, one session")
	dialStdio := "~/.local/bin/rex -S " + srv2.socket() + " __internal dial-stdio"
	if got, want := srv2.Commands(), []string{probeRex, probeLocalBin, dialStdio}; !equalStrings(got, want) {
		t.Fatalf("commands:\n%q\nwant\n%q", got, want)
	}
}

// A refused session is tried again a few times, in under a second, whatever reason the host
// gives (servers differ), except a host that does not know sessions at all; a host that
// refuses sessions for good gets the same answer as before.
func TestSessionRefusalsAreTriedAFewTimes(t *testing.T) {
	for _, c := range []struct {
		reason ssh.RejectionReason
		opens  int
	}{
		{ssh.ConnectionFailed, 1 + len(sessionRetries)}, // OpenSSH's answer
		{ssh.Prohibited, 1 + len(sessionRetries)},
		{ssh.ResourceShortage, 1 + len(sessionRetries)},
		{ssh.UnknownChannelType, 1},
	} {
		t.Run(c.reason.String(), func(t *testing.T) {
			srv := newTestServer(t)
			srv.install("rex")
			srv.set(func(s *testServer) { s.refuseSession = c.reason })
			p := newTestPool(t, "", trusting())
			useKey(t, p, srv)
			start := time.Now()
			_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
			if e := asError(t, err); e.Code != "unreachable" || !strings.Contains(e.Sentence, "open failed") {
				t.Fatalf("Open: %+v, want unreachable with the host's session refusal", e)
			}
			if srv.RefusedSessions() != c.opens {
				t.Fatalf("session opens = %d, want %d", srv.RefusedSessions(), c.opens)
			}
			if d := time.Since(start); d > 3*time.Second {
				t.Fatalf("Open took %v", d)
			}
			if len(srv.Commands()) != 0 {
				t.Fatalf("commands = %q, want none", srv.Commands())
			}
		})
	}
}

func TestEndpointSocket(t *testing.T) {
	status := "{\n  \"endpoint\": \"unix:///run/user/1001/rex/server.sock\",\n  \"pid\": 3742\n}\n"
	for _, tc := range []struct{ name, out, want string }{
		{"pretty-printed", status, "/run/user/1001/rex/server.sock"},
		{"one line", `{"endpoint":"unix:///tmp/s.sock"}`, "/tmp/s.sock"},
		{"after a banner with braces", "Welcome {user}\n{oops\n" + status, "/run/user/1001/rex/server.sock"},
		{"indented", "  " + status, "/run/user/1001/rex/server.sock"},
		{"relative path", `{"endpoint":"unix://run/s.sock"}`, ""},
		{"no scheme", `{"endpoint":"/tmp/s.sock"}`, ""},
		{"tcp", `{"endpoint":"tcp://127.0.0.1:1"}`, ""},
		{"control character", `{"endpoint":"unix:///tmp/a\nb"}`, ""},
		{"not at a line start", `Status: {"endpoint":"unix:///tmp/s.sock"}`, ""},
	} {
		if got := endpointSocket([]byte(tc.out)); got != tc.want {
			t.Errorf("%s: %q, want %q", tc.name, got, tc.want)
		}
	}

	// Output built to make a scan quadratic stays fast: at most maxStatusTries decodes.
	for name, out := range map[string]string{
		"braces":        strings.Repeat("{", probeOutput),
		"brace lines":   strings.Repeat("{\n", probeOutput/2),
		"open objects":  strings.Repeat(`{"a":`+"\n", probeOutput/6),
		"nested object": strings.Repeat(`{"a":`, probeOutput/5),
	} {
		start := time.Now()
		if got := endpointSocket([]byte(out)); got != "" {
			t.Errorf("%s: %q", name, got)
		}
		if d := time.Since(start); d > time.Second {
			t.Errorf("%s took %v", name, d)
		}
	}
}

// A refusal older than a day no longer counts, and the next save drops it from
// host-endpoints.json, so the file does not keep every host that ever refused the forward.
// The socket of that host stays, and so does a refusal still within its day.
func TestExpiredRefusalsPruned(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	old, fresh := "ssh://me@old.example", "ssh://me@fresh.example"
	f := endpointFile{
		Version:    1,
		SSHSockets: map[string]string{old: "/run/old.sock", fresh: "/run/fresh.sock"},
		Refused:    map[string]int64{old: now.Add(-25 * time.Hour).Unix(), fresh: now.Add(-time.Hour).Unix()},
	}
	b, err := json.Marshal(f)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "host-endpoints.json"), b, 0o600); err != nil {
		t.Fatal(err)
	}
	p := NewPool(dir, nil, nil)
	p.store.setSocket("ssh://me@new.example", "/run/new.sock") // any change writes the file

	got := readEndpoints(t, dir)
	if at, ok := got.Refused[old]; ok {
		t.Errorf("the expired refusal of %s is still in the file (%d)", old, at)
	}
	if got.Refused[fresh] != f.Refused[fresh] {
		t.Errorf("the refusal of %s within its day: %d, want %d", fresh, got.Refused[fresh], f.Refused[fresh])
	}
	if got.SSHSockets[old] != "/run/old.sock" || got.SSHSockets[fresh] != "/run/fresh.sock" || got.SSHSockets["ssh://me@new.example"] != "/run/new.sock" {
		t.Errorf("sockets = %v, want all three kept", got.SSHSockets)
	}
	// What the details panel reads: no refusal for the expired one, the day's end for the other.
	if r, err := p.Remembered(old); err != nil || r != (Remembered{Socket: "/run/old.sock"}) {
		t.Errorf("Remembered(%s) = %+v %v, want the socket and no refusal", old, r, err)
	}
	want := Remembered{Socket: "/run/fresh.sock", RefusedUntil: f.Refused[fresh] + int64(refusalMemory/time.Second)}
	if r, err := p.Remembered(fresh); err != nil || r != want {
		t.Errorf("Remembered(%s) = %+v %v, want %+v", fresh, r, err, want)
	}
}

// An answer that races the end of its question (Kotlin answers just as the question's time
// runs out, or as the login gives up) is either taken or refused, never both: Answer
// reports true only when the asker got the answer.
func TestAnswerAfterTimeoutIsFalse(t *testing.T) {
	q := NewQueue()
	mismatches := 0
	for i := range 2000 {
		ctx, cancel := context.WithCancel(context.Background())
		got := make(chan answer, 1)
		go func() {
			text, ok := q.Ask(ctx, Question{Kind: "password"})
			got <- answer{text, ok}
		}()
		var id int64
		for id == 0 {
			if qs := q.Pending(); len(qs) == 1 {
				id = qs[0].ID
			} else {
				runtime.Gosched()
			}
		}
		if i%2 == 0 {
			cancel() // the time ran out just before the answer came
		} else {
			go cancel() // at the same moment
		}
		answered := q.Answer(id, "pw", true)
		a := <-got
		cancel()
		if answered != a.ok || (a.ok && a.text != "pw") {
			if mismatches == 0 {
				t.Errorf("round %d: Answer reported %v, the asker got %+v", i, answered, a)
			}
			mismatches++
		}
	}
	if mismatches > 0 {
		t.Errorf("%d of 2000 answers disagreed with what the asker got", mismatches)
	}
	if len(q.Pending()) != 0 {
		t.Errorf("questions left pending: %+v", q.Pending())
	}
}

// newKeys makes n Ed25519 keys: their PEMs (for SetIdentities) and SHA256 fingerprints.
func newKeys(t *testing.T, n int) (pems [][]byte, fps []string, pubs []ssh.PublicKey) {
	t.Helper()
	for i := range n {
		pemKey, line, err := GenerateKey(fmt.Sprintf("key %d", i+1))
		if err != nil {
			t.Fatal(err)
		}
		pub, _, _, _, err := ssh.ParseAuthorizedKey([]byte(line))
		if err != nil {
			t.Fatal(err)
		}
		pems, fps, pubs = append(pems, pemKey), append(fps, ssh.FingerprintSHA256(pub)), append(pubs, pub)
	}
	return pems, fps, pubs
}

// A login offers the keys in the order given, as OpenSSH offers its identities: a host
// that takes only the third sees the first two, then the third, and the login works.
func TestKeysOfferedInOrder(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	pems, fps, pubs := newKeys(t, 3)
	srv.set(func(s *testServer) { s.keys = []ssh.PublicKey{pubs[2]} })
	a := trusting()
	p := newTestPool(t, "", a)
	if err := p.SetIdentities(pems); err != nil {
		t.Fatal(err)
	}
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "third key")
	if got := srv.Auths(); !equalStrings(got, fps) {
		t.Fatalf("keys offered:\n%q\nwant\n%q", got, fps)
	}
	if len(a.questions()) != 1 {
		t.Fatalf("questions = %+v, want only the host key", a.questions())
	}
}

// Review Focus 1: twenty keys, none of them taken. A login offers the first five, in order,
// and then the password: a host with OpenSSH's MaxAuthTries 6 would hang up on a sixth key.
func TestAtMostFiveKeys(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	pems, fps, _ := newKeys(t, 20)
	_, _, other := newKeys(t, 1)
	srv.set(func(s *testServer) {
		s.keys = other // public key login is on, but for another key
		s.password = "pw"
	})
	a := &asker{fn: func(q Question) (string, bool) {
		switch q.Kind {
		case "hostkey":
			return "trust", true
		case "password":
			return "pw", true
		}
		return "", false
	}}
	p := newTestPool(t, "", a)
	if err := p.SetIdentities(pems); err != nil {
		t.Fatal(err)
	}
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "after five keys")
	want := append(slices.Clone(fps[:5]), "password")
	if got := srv.Auths(); !equalStrings(got, want) {
		t.Fatalf("login attempts:\n%q\nwant\n%q", got, want)
	}
}

// Review Focus 2: a key removed while a login waits on a question stays in that login's
// keys (it took them when it started); the next login no longer offers it.
func TestRemoveKeyDuringLogin(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	pems, fps, pubs := newKeys(t, 2)
	srv.set(func(s *testServer) { s.keys = []ssh.PublicKey{pubs[1]} }) // only the second
	asked, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind != "hostkey" {
			return "", false
		}
		once.Do(func() { close(asked) })
		<-release
		return "trust", true
	}}
	p := newTestPool(t, "", a)
	if err := p.SetIdentities(pems); err != nil {
		t.Fatal(err)
	}
	opened := make(chan error, 1)
	go func() {
		c, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		if err == nil {
			c.Close()
		}
		opened <- err
	}()
	select {
	case <-asked:
	case <-time.After(10 * time.Second):
		t.Fatal("the login asked no host key question")
	}
	if err := p.SetIdentities(pems[:1]); err != nil { // the second key is removed
		t.Fatal(err)
	}
	close(release)
	if err := <-opened; err != nil {
		t.Fatalf("the login under way lost the key removed meanwhile: %v", err)
	}
	if got, want := srv.Auths(), fps; !equalStrings(got, want) {
		t.Fatalf("first login offered %q, want %q", got, want)
	}

	p.CloseAll()
	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "key_refused" {
		t.Fatalf("the next login: %+v, want key_refused", e)
	}
	if got, want := srv.Auths(), []string{fps[0], fps[1], fps[0]}; !equalStrings(got, want) {
		t.Fatalf("the next login offered %q after the first's, want only the key left", got[2:])
	}
}

// SetIdentities takes the whole list or nothing: a key that does not parse leaves the keys
// as they were. A key given twice is offered once; nil clears the keys.
func TestSetIdentities(t *testing.T) {
	pems, _, pubs := newKeys(t, 2)
	p := NewPool("", nil, nil)
	if err := p.SetIdentities([][]byte{pems[0], pems[1], pems[0]}); err != nil {
		t.Fatal(err)
	}
	keys := func() (out []string) {
		p.mu.Lock()
		defer p.mu.Unlock()
		for _, id := range p.identities {
			out = append(out, ssh.FingerprintSHA256(id.signer.PublicKey()))
		}
		return out
	}
	want := []string{ssh.FingerprintSHA256(pubs[0]), ssh.FingerprintSHA256(pubs[1])}
	if got := keys(); !equalStrings(got, want) {
		t.Fatalf("keys = %q, want %q (the duplicate once)", got, want)
	}
	// A key that does not parse is left out, as OpenSSH skips an identity it cannot load;
	// the others are set, so removing a key still reaches the logins.
	err := p.SetIdentities([][]byte{pems[1], []byte("not a key"), {}, pems[0]})
	var left *KeysLeftOut
	if !errors.As(err, &left) || !slices.Equal(left.Places, []int{2, 3}) {
		t.Fatalf("a list with a broken and an empty key: %v, want KeysLeftOut [2 3]", err)
	}
	if strings.Contains(err.Error(), "not a key") {
		t.Fatalf("the error %q carries the key's bytes", err)
	}
	if got, want := keys(), []string{want[1], want[0]}; !equalStrings(got, want) {
		t.Fatalf("after a list with broken keys: keys = %q, want the two good ones %q", got, want)
	}
	if err := p.SetIdentities([][]byte{[]byte("not a key")}); !errors.As(err, &left) || len(keys()) != 0 {
		t.Fatalf("only a broken key: %v, keys %q, want KeysLeftOut and no keys", err, keys())
	}
	if err := p.SetIdentities(nil); err != nil || len(keys()) != 0 {
		t.Fatalf("clearing: %v, keys %q", err, keys())
	}
}

// Review Focus 3: Remembered tells the socket and until when dial-stdio is used;
// ForgetSocket clears both, on disk too, without dropping the connection or its streams,
// and the next stream or login probes again.
func TestRememberedAndForgetSocket(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	dir := t.TempDir()
	p := newTestPool(t, dir, trusting())
	useKey(t, p, srv)
	loc := srv.locator()

	if r, err := p.Remembered(loc); err != nil || r != (Remembered{}) {
		t.Fatalf("before any login: %+v %v, want nothing", r, err)
	}
	c := mustOpen(t, p, loc)
	roundTrip(t, c, srv, "before")
	if r, err := p.Remembered(loc); err != nil || r != (Remembered{Socket: srv.socket()}) {
		t.Fatalf("after the probe: %+v %v, want the socket %s and no refusal", r, err, srv.socket())
	}

	if err := p.ForgetSocket(loc); err != nil {
		t.Fatal(err)
	}
	if r, err := p.Remembered(loc); err != nil || r != (Remembered{}) {
		t.Fatalf("after ForgetSocket: %+v %v, want nothing", r, err)
	}
	if _, ok := readEndpoints(t, dir).SSHSockets[loc]; ok {
		t.Fatal("host-endpoints.json still has the forgotten socket")
	}
	roundTrip(t, c, srv, "still up") // the open stream stays
	if !p.connected(loc) || srv.Closed() != 0 {
		t.Fatal("ForgetSocket dropped the connection")
	}
	roundTrip(t, mustOpen(t, p, loc), srv, "probed again")
	if srv.Handshakes() != 1 || len(srv.Commands()) != 2 {
		t.Fatalf("handshakes = %d, commands = %q; want the same login and a second probe", srv.Handshakes(), srv.Commands())
	}

	// The next login (the app started again) probes too.
	if err := p.ForgetSocket(loc); err != nil {
		t.Fatal(err)
	}
	p2 := newTestPool(t, dir, trusting())
	useKey(t, p2, srv)
	roundTrip(t, mustOpen(t, p2, loc), srv, "new login")
	if srv.Handshakes() != 2 || len(srv.Commands()) != 3 {
		t.Fatalf("handshakes = %d, commands = %q; want a new login and a third probe", srv.Handshakes(), srv.Commands())
	}

	// A host that refuses the forward: dial-stdio until a day after the refusal.
	srv2 := newTestServer(t)
	srv2.install("rex")
	srv2.set(func(s *testServer) { s.refuseForward = true })
	useKey(t, p, srv2)
	before := time.Now().Unix()
	c2 := mustOpen(t, p, srv2.locator())
	roundTrip(t, c2, srv2, "over stdio")
	after := time.Now().Unix()
	day := int64(refusalMemory / time.Second)
	r, err := p.Remembered(srv2.locator())
	if err != nil || r.Socket != srv2.socket() || r.RefusedUntil < before+day || r.RefusedUntil > after+day {
		t.Fatalf("after a refusal: %+v %v, want %s until [%d, %d]", r, err, srv2.socket(), before+day, after+day)
	}
	if err := p.ForgetSocket(srv2.locator()); err != nil {
		t.Fatal(err)
	}
	if r, _ := p.Remembered(srv2.locator()); r != (Remembered{}) {
		t.Fatalf("after ForgetSocket: %+v, want nothing", r)
	}
	if _, ok := readEndpoints(t, dir).Refused[srv2.locator()]; ok {
		t.Fatal("host-endpoints.json still has the forgotten refusal")
	}
	roundTrip(t, c2, srv2, "stdio still up")
	roundTrip(t, mustOpen(t, p, srv2.locator()), srv2, "forward tried again")
	if srv2.Forwards() != 2 || srv2.Handshakes() != 1 {
		t.Fatalf("forwards = %d, handshakes = %d; want the forward tried again on the same login", srv2.Forwards(), srv2.Handshakes())
	}

	// Malformed locators are the parse errors.
	if _, err := p.Remembered("ssh://h"); asError(t, err).Code != "no_user" {
		t.Fatalf("Remembered(ssh://h): %v", err)
	}
	if err := p.ForgetSocket("http://h"); asError(t, err).Code != "malformed" {
		t.Fatalf("ForgetSocket(http://h): %v", err)
	}
}

// Five refused keys and a wrong answer on a host with OpenSSH's MaxAuthTries 6: the host
// hangs up right after the answer, the sixth failure, without asking again. That is a
// refused password (or prompt), which stops the core, not a hang-up it would retry with
// the saved password every few seconds until the account locks.
func TestHangUpAfterWrongAnswerIsRefused(t *testing.T) {
	for _, tc := range []struct {
		name string
		set  func(s *testServer)
		kind string
	}{
		{"password", func(s *testServer) { s.password = "right" }, "password"},
		{"keyboard-interactive", func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}}
		}, "prompt"},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		pems, fps, _ := newKeys(t, 5)
		_, _, other := newKeys(t, 1)
		srv.set(func(s *testServer) { s.keys = other })
		srv.set(tc.set)
		a := &asker{fn: func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			return "wrong", true
		}}
		p := newTestPool(t, "", a)
		if err := p.SetIdentities(pems); err != nil {
			t.Fatal(err)
		}
		_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		if e := asError(t, err); e.Code != "password_refused" || e.Fix != "" {
			t.Errorf("%s: %+v, want password_refused", tc.name, e)
		}
		if a.count(tc.kind) != 1 {
			t.Errorf("%s: questions %+v, want one %s question", tc.name, a.questions(), tc.kind)
		}
		if got := srv.Auths(); tc.kind == "password" && !equalStrings(got, append(slices.Clone(fps), "password")) {
			t.Errorf("%s: login attempts %q, want the five keys and the password", tc.name, got)
		}
	}
}

// A connection the pool closes itself (Forget while the answer goes out) is not the host
// refusing the answer: it stays closed_before_login.
func TestOwnCloseAfterAnswerIsNotRefused(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password = "pw" })
	var p *Pool
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "trust", true
		}
		p.Forget(srv.locator())
		return "pw", true
	}}
	p = newTestPool(t, "", a)
	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "closed_before_login" {
		t.Fatalf("forgotten during the login: %+v, want closed_before_login", e)
	}
}

// A host that refuses an answer and closes the TCP connection right after (the refusal, then
// its exit, without an SSH disconnect) refused it: password_refused, which stops the core, so
// a wrong saved password is not sent again every few seconds until the account locks.
// x/crypto reads the refusal and then the connection's end before the login sees the
// refusal; the bytes that came after the answer tell it the host replied. Most logins ask no
// Retry on the dead connection; when the login sees the refusal before the end, one Retry
// goes out (the host did refuse) and nothing after it.
func TestRefusalThenCloseIsRefused(t *testing.T) {
	for _, tc := range []struct {
		name string
		set  func(s *testServer)
		kind string
	}{
		{"password", func(s *testServer) { s.password = "right" }, "password"},
		{"prompt", func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}}
		}, "prompt"},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(tc.set)
		srv.set(func(s *testServer) { s.closeOnRefuse = true })
		for i := range 5 {
			a := &asker{fn: func(q Question) (string, bool) {
				if q.Kind == "hostkey" {
					return "trust", true
				}
				return "wrong", true
			}}
			p := newTestPool(t, "", a)
			_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
			if e := asError(t, err); e.Code != "password_refused" || e.Fix != "" {
				t.Errorf("%s, login %d: %+v, want password_refused", tc.name, i+1, e)
			}
			retries := 0
			for _, q := range a.questions() {
				if q.Retry {
					retries++
				}
			}
			if a.count(tc.kind) > 2 || retries > 1 {
				t.Errorf("%s, login %d: questions %+v, want at most one Retry", tc.name, i+1, a.questions())
			}
		}
	}
}

// OpenSSH's PAM keyboard-interactive takes the right password, then sends a round with no
// prompts (PAM_SUCCESS) and only after the reply its SUCCESS. A link that dies in that last
// round trip refused nothing: closed_before_login, no Retry, so Kotlin keeps the saved
// password. The empty round is the host's reply to the password, and the login's reply to
// it is the next answer: bytes after it are the host's verdict. A refusal after an empty
// round, the connection closed right after it, is still password_refused.
func TestEmptyPromptRoundThenReset(t *testing.T) {
	for _, tc := range []struct {
		name, answer, want string
	}{
		{"right password, reset before SUCCESS", "right", "closed_before_login"},
		{"wrong password, refusal, then closed", "wrong", "password_refused"},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Password:", want: "right"}} // PAM's hidden "Password:"
			s.kbdEmptyRound, s.kbdResetLogin, s.closeOnRefuse = true, true, true
		})
		for i := range 5 {
			a := &asker{fn: func(q Question) (string, bool) {
				if q.Kind == "hostkey" {
					return "trust", true
				}
				return tc.answer, true
			}}
			p := newTestPool(t, "", a)
			_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
			if e := asError(t, err); e.Code != tc.want {
				t.Errorf("%s, login %d: %+v, want %s", tc.name, i+1, e, tc.want)
			}
			if tc.want == "closed_before_login" {
				for _, q := range a.questions() {
					if q.Retry {
						t.Errorf("%s, login %d: a Retry question: %+v", tc.name, i+1, q)
					}
				}
			}
			if n := a.count("password"); n < 1 || n > 2 {
				t.Errorf("%s, login %d: questions %+v, want the password question (and at most one Retry)", tc.name, i+1, a.questions())
			}
		}
	}
}

// keyLine is the authorized_keys line of pemKey as the key_refused fix carries it.
func keyLine(t *testing.T, pemKey []byte) string {
	t.Helper()
	signer, err := ssh.ParsePrivateKey(pemKey)
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(ssh.MarshalAuthorizedKey(signer.PublicKey()))) + " " + keyComment(pemKey)
}

// A host whose MaxAuthTries is no more than the keys the phone offers (CIS asks for 4 or
// less; 3 is common) hangs up during the key stage, before any password question, as sshd
// does with "Too many authentication failures". That stops the core like a refused key: a
// retry every few seconds would only add failures (and maybe a fail2ban ban). The sentence
// says the host hung up while the keys went out; the fix installs the first key.
func TestHangUpDuringKeysStops(t *testing.T) {
	for _, tc := range []struct {
		name         string
		keys, tries  int
		offered      int
		sentenceKeys string
	}{
		{"three keys, MaxAuthTries 3", 3, 3, 3, "this phone's 3 keys"},
		{"five keys, MaxAuthTries 3", 5, 3, 3, "this phone's 5 keys"},
		{"one key, MaxAuthTries 1", 1, 1, 1, "this phone's key"},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		pems, fps, _ := newKeys(t, tc.keys)
		_, _, other := newKeys(t, 1)
		srv.set(func(s *testServer) { s.keys, s.password, s.maxAuthTries = other, "pw", tc.tries })
		a := &asker{fn: func(q Question) (string, bool) {
			switch q.Kind {
			case "hostkey":
				return "trust", true
			case "password":
				return "pw", true
			}
			return "", false
		}}
		p := newTestPool(t, "", a)
		if err := p.SetIdentities(pems); err != nil {
			t.Fatal(err)
		}
		_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		sentence := "127.0.0.1 hung up while Velo offered " + tc.sentenceKeys + " for rex. " +
			"It may allow only a few login tries (MaxAuthTries). "
		if tc.keys == 1 {
			sentence += "On that host, append the public key to rex's ~/.ssh/authorized_keys file."
		} else {
			sentence += "On that host, append the first public key to rex's ~/.ssh/authorized_keys file, or remove imported keys in Settings → SSH key."
		}
		fix := "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '" + keyLine(t, pems[0]) + "' >> ~/.ssh/authorized_keys"
		if e := asError(t, err); e.Code != "key_refused" || e.Sentence != sentence || e.Fix != fix {
			t.Errorf("%s: %+v\nwant key_refused %q, fix %q", tc.name, e, sentence, fix)
		}
		if a.count("password") != 0 {
			t.Errorf("%s: questions %+v, want no password question (the host hung up first)", tc.name, a.questions())
		}
		if got := srv.Auths(); !equalStrings(got, fps[:tc.offered]) {
			t.Errorf("%s: login attempts %q, want the first %d keys", tc.name, got, tc.offered)
		}
	}
}

// A connection that drops (no SSH disconnect) while the keys go out is the network, not the
// host's limit: closed_before_login, which the core retries, without a password question on
// the dead connection (x/crypto goes on to the next method after a read error) and, on a
// key-only host, without calling the keys refused.
func TestDropDuringKeysIsNotRefused(t *testing.T) {
	for _, password := range []string{"", "pw"} {
		srv := newTestServer(t)
		srv.install("rex")
		pems, _, _ := newKeys(t, 3)
		_, _, other := newKeys(t, 1)
		srv.set(func(s *testServer) { s.keys, s.password, s.dropOnKey = other, password, true })
		a := &asker{fn: func(q Question) (string, bool) {
			switch q.Kind {
			case "hostkey":
				return "trust", true
			case "password":
				return "pw", true
			}
			return "", false
		}}
		p := newTestPool(t, "", a)
		if err := p.SetIdentities(pems); err != nil {
			t.Fatal(err)
		}
		_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		if e := asError(t, err); e.Code != "closed_before_login" || a.count("password") != 0 {
			t.Errorf("password %q, dropped during the keys: %+v after %+v, want closed_before_login and no password question",
				password, e, a.questions())
		}
	}
}

// The pool closing the connection itself during the key stage (Forget), even as the host
// hangs up on its MaxAuthTries, is not the host's limit: closed_before_login. The host holds
// its disconnect until the pool has closed the connection, and that close reaches the socket
// only once the disconnect is read (it was on its way already): every run sees the host's
// disconnect with the target forgotten, so the ctx guard is what keeps it from key_refused.
func TestOwnCloseDuringKeysIsNotRefused(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	pems, _, _ := newKeys(t, 3)
	_, _, other := newKeys(t, 1)
	closed := make(chan struct{})
	var once sync.Once
	var mu sync.Mutex
	var socks []net.Conn
	t.Cleanup(func() {
		mu.Lock()
		defer mu.Unlock()
		for _, c := range socks {
			c.Close()
		}
	})
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		var d net.Dialer
		c, err := d.DialContext(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		mu.Lock()
		socks = append(socks, c)
		mu.Unlock()
		return &lateClose{Conn: c, closed: func() { once.Do(func() { close(closed) }) }}, nil
	}
	p := NewPool("", dial, trusting())
	t.Cleanup(p.CloseAll)
	srv.set(func(s *testServer) {
		s.keys, s.password, s.maxAuthTries = other, "pw", 1
		s.onKey = func() {
			p.Forget(srv.locator())
			select {
			case <-closed:
			case <-time.After(5 * time.Second):
				t.Error("Forget did not close the login's connection")
			}
		}
	})
	if err := p.SetIdentities(pems); err != nil {
		t.Fatal(err)
	}
	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "closed_before_login" {
		t.Fatalf("forgotten during the keys: %+v, want closed_before_login", e)
	}
}

// lateClose is a connection whose Close (the pool's) reaches the socket only once a read
// ends in an error: what the host sent before the close is still read.
type lateClose struct {
	net.Conn
	closed func()
}

func (c *lateClose) Close() error {
	c.closed()
	return nil
}

func (c *lateClose) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if err != nil {
		c.Conn.Close()
	}
	return n, err
}

// Review Focus 1: the connection resets right after a password or prompt answer, before
// the host replies: the network died, the host refused nothing. x/crypto goes on to the
// next method after the read error; the pool asks nothing more on the dead connection. No
// Retry question (Kotlin forgets the saved password on one), and closed_before_login, which
// the core retries, not password_refused. A right or a wrong answer alike: the host never
// said which it was.
func TestDropAfterAnswerKeepsPassword(t *testing.T) {
	password := func(s *testServer) { s.password = "pw" }
	prompt := func(s *testServer) { s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}} }
	for _, tc := range []struct {
		name         string
		keys         bool // refused keys go out first, as on most hosts
		set          func(s *testServer)
		kind, answer string
	}{
		{"right password", false, password, "password", "pw"},
		{"wrong password", false, password, "password", "wrong"},
		{"wrong password after refused keys", true, password, "password", "wrong"},
		{"right prompt", false, prompt, "prompt", "123456"},
		{"wrong prompt after refused keys", true, prompt, "prompt", "000000"},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(tc.set)
		srv.set(func(s *testServer) { s.dropOnAnswer = true })
		a := &asker{fn: func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			return tc.answer, true
		}}
		p := newTestPool(t, "", a)
		if tc.keys {
			pems, _, _ := newKeys(t, 2)
			_, _, other := newKeys(t, 1)
			srv.set(func(s *testServer) { s.keys = other })
			if err := p.SetIdentities(pems); err != nil {
				t.Fatal(err)
			}
		}
		_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		if e := asError(t, err); e.Code != "closed_before_login" {
			t.Errorf("%s: %+v, want closed_before_login", tc.name, e)
		}
		if a.count(tc.kind) != 1 {
			t.Errorf("%s: questions %+v, want one %s question and none on the dead connection", tc.name, a.questions(), tc.kind)
		}
		for _, q := range a.questions() {
			if q.Retry {
				t.Errorf("%s: a Retry question after the connection dropped: %+v", tc.name, q)
			}
		}
	}

	// The host refuses the first answer (a Retry follows, rightly), and the connection drops
	// right after the second: the host did refuse a password in this login, so
	// password_refused, as when it ends the connection right after its refusal
	// (TestRefusalThenCloseIsRefused). Kotlin forgot the refused password on the Retry.
	srv := newTestServer(t)
	srv.install("rex")
	var answers atomic.Int32
	srv.set(func(s *testServer) {
		s.password = "pw"
		s.onAnswer = func() {
			if answers.Add(1) == 2 {
				srv.set(func(s *testServer) { s.dropOnAnswer = true })
			}
		}
	})
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "trust", true
		}
		return "wrong", true
	}}
	_, err := newTestPool(t, "", a).Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "password_refused" {
		t.Errorf("refused, then dropped: %+v, want password_refused", e)
	}
	var retries []bool
	for _, q := range a.questions() {
		if q.Kind == "password" {
			retries = append(retries, q.Retry)
		}
	}
	if !slices.Equal(retries, []bool{false, true}) {
		t.Errorf("refused, then dropped: Retry of the password questions = %v, want [false true]", retries)
	}
}

// Review Focus 2: a host that leaves an answer without a reply past the step timeout (a slow
// PAM) ends the login as closed_before_login, and the next login's question carries
// AfterStall: Kotlin asks the user instead of sending the saved password again, and keeps it
// (no Retry). A login that finishes clears the mark: the one after it asks plainly.
func TestStalledAnswerMarksNextQuestion(t *testing.T) {
	for _, tc := range []struct {
		name         string
		set          func(s *testServer)
		kind, answer string
	}{
		{"password", func(s *testServer) { s.password = "pw" }, "password", "pw"},
		{"prompt", func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}}
		}, "prompt", "123456"},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(tc.set)
		release := make(chan struct{})
		var answers atomic.Int32
		srv.set(func(s *testServer) {
			s.onAnswer = func() {
				if answers.Add(1) == 1 { // the first answer waits for its reply
					select {
					case <-release:
					case <-time.After(10 * time.Second):
					}
				}
			}
		})
		a := &asker{fn: func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			return tc.answer, true
		}}
		p := newTestPool(t, "", a)
		p.stepTimeout = 3 * time.Second // the held reply waits 10 s; production: 45 s (TestTimingsMatchTheDesktop)
		loc := srv.locator()
		_, err := p.Open(ctxFor(t, 10*time.Second), loc)
		close(release)
		if e := asError(t, err); e.Code != "closed_before_login" {
			t.Fatalf("%s: a stalled answer: %+v, want closed_before_login", tc.name, e)
		}
		roundTrip(t, mustOpen(t, p, loc), srv, "after the stall")
		srv.closeConns() // the next stream logs in again, on the same target
		waitFor(t, 5*time.Second, "the pool to see its connection end", func() bool { return !p.connected(loc) })
		roundTrip(t, mustOpen(t, p, loc), srv, "after a finished login")

		var marks []bool
		for _, q := range a.questions() {
			if q.Kind == tc.kind {
				marks = append(marks, q.AfterStall)
			}
			if q.Retry {
				t.Errorf("%s: a Retry question: %+v", tc.name, q)
			}
		}
		if !slices.Equal(marks, []bool{false, true, false}) {
			t.Errorf("%s: AfterStall of the three logins' questions = %v, want [false true false]", tc.name, marks)
		}
	}
	// Kotlin reads the mark as after_stall; a question without it leaves it out.
	for q, want := range map[Question]string{{Kind: "password", AfterStall: true}: `"after_stall":true`, {Kind: "password"}: ""} {
		b, err := json.Marshal(q)
		if err != nil {
			t.Fatal(err)
		}
		if got := strings.Contains(string(b), "after_stall"); want == "" && got || want != "" && !strings.Contains(string(b), want) {
			t.Errorf("JSON %s, want %q", b, want)
		}
	}
}

// Review Focus 3: a host that closes the connection (no SSH disconnect) while the keys go
// out, login after login. Two in a row can be a flapping network: closed_before_login, which
// the core retries. The third in a row stops as key_refused, saying the host kept closing the
// connection while Velo offered the keys, with the first key's fix. The stop starts the count
// over, and so do a login that gets past the key stage and Forget.
func TestKeyStageClosesStopAfterThree(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	pems, _, pubs := newKeys(t, 3)
	srv.set(func(s *testServer) { s.keys, s.dropOnKey = []ssh.PublicKey{pubs[2]}, true })
	p := newTestPool(t, "", trusting())
	if err := p.SetIdentities(pems); err != nil {
		t.Fatal(err)
	}
	loc := srv.locator()
	sentence := "127.0.0.1 kept closing the connection while Velo offered this phone's 3 keys for rex. " +
		"On that host, append the first public key to rex's ~/.ssh/authorized_keys file, or remove imported keys in Settings → SSH key."
	fix := "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '" + keyLine(t, pems[0]) + "' >> ~/.ssh/authorized_keys"
	expect := func(step string, codes ...string) {
		t.Helper()
		for i, want := range codes {
			_, err := p.Open(ctxFor(t, 10*time.Second), loc)
			e := asError(t, err)
			if e.Code != want {
				t.Fatalf("%s, close %d: %+v, want %s", step, i+1, e, want)
			}
			if want == "key_refused" && (e.Sentence != sentence || e.Fix != fix) {
				t.Fatalf("%s, close %d: %+v\nwant sentence %q, fix %q", step, i+1, e, sentence, fix)
			}
		}
	}
	expect("three in a row, then one more", "closed_before_login", "closed_before_login", "key_refused", "closed_before_login")
	p.Forget(loc)
	expect("after Forget", "closed_before_login", "closed_before_login")

	srv.set(func(s *testServer) { s.dropOnKey = false })
	roundTrip(t, mustOpen(t, p, loc), srv, "past the keys")
	srv.closeConns()
	waitFor(t, 5*time.Second, "the pool to see its connection end", func() bool { return !p.connected(loc) })
	srv.set(func(s *testServer) { s.dropOnKey = true })
	expect("after a login", "closed_before_login", "closed_before_login", "key_refused")
}

// hostDisconnect is x/crypto's own error for an SSH disconnect from the host with reason and
// message, which a host sends here in the clear, before the key exchange: the error's type
// is not exported, and x/crypto's server sends only its MaxAuthTries disconnect.
func hostDisconnect(t *testing.T, reason uint32, message string) error {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		payload := ssh.Marshal(struct {
			Reason   uint32 `sshtype:"1"`
			Message  string
			Language string
		}{reason, message, ""})
		pad := 8 - (5+len(payload))%8
		if pad < 4 {
			pad += 8
		}
		packet := []byte{0, 0, 0, 0, byte(pad)}
		binary.BigEndian.PutUint32(packet, uint32(1+len(payload)+pad))
		packet = append(append(packet, payload...), make([]byte, pad)...)
		io.WriteString(c, "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.20\r\n")
		c.Write(packet)
		io.Copy(io.Discard, c)
	}()
	c, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_, _, _, err = ssh.NewClientConn(c, ln.Addr().String(), &ssh.ClientConfig{
		User:            "rex",
		HostKeyCallback: func(string, net.Addr, ssh.PublicKey) error { return errors.New("no key exchange expected") },
	})
	if err == nil {
		t.Fatal("the disconnect made no error")
	}
	return err
}

// Only a disconnect that says the login has no tries left stops a login during the key
// stage: OpenSSH's MaxAuthTries disconnect (measured against OpenSSH 9.6p1 in the e2e mock,
// MaxAuthTries 3 and three or five keys: reason 2, SSH2_DISCONNECT_PROTOCOL_ERROR, "Too many
// authentication failures"), x/crypto's, and NO_MORE_AUTH_METHODS_AVAILABLE. Any other
// (BY_APPLICATION "server shutting down", CONNECTION_LOST, another protocol error) is
// transient, as a dropped connection is, and counts toward the three closes in a row.
func TestDisconnectReasonDuringKeys(t *testing.T) {
	pems, _, _ := newKeys(t, 2)
	ids, err := parseIdentities(pems)
	if err != nil {
		t.Fatal(err)
	}
	target, err := ParseTarget("ssh://rex@127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	hungUp := errKeysHungUp(target, ids)
	closing := errKeysClosed(target, ids)
	for _, tc := range []struct {
		name    string
		reason  uint32
		message string
		stops   bool
	}{
		{"OpenSSH 9.6 MaxAuthTries (measured)", 2, "Too many authentication failures", true},
		{"x/crypto MaxAuthTries", 2, "too many authentication failures", true},
		{"no more methods", 14, "No more authentication methods available", true},
		{"server shutting down", 11, "server shutting down", false},
		{"connection lost", 10, "", false},
		{"another protocol error", 2, "Corrupted MAC on input", false},
	} {
		hostErr := hostDisconnect(t, tc.reason, tc.message)
		p := NewPool("", nil, nil)
		tg := p.target(target)
		for i := range 3 {
			l := &login{p: p, t: target, ctx: context.Background(), ids: ids, conn: &sniffConn{}, watch: &watchdog{},
				kex: true, keysTrying: true}
			e := p.ended(tg, l, hostErr)
			want := errClosedBeforeLogin(target)
			switch {
			case tc.stops:
				want = hungUp
			case i == 2:
				want = closing
			}
			if *e != *want {
				t.Errorf("%s (%v), login %d: %+v, want %+v", tc.name, hostErr, i+1, e, want)
			}
		}
	}
}

// A refusal of the forward older than a day is dropped when host-endpoints.json is read, and
// the file is written without it then: the file does not keep it until some other change.
// The sockets stay, and so does a refusal within its day.
func TestExpiredRefusalsDroppedOnLoad(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	old, fresh := "ssh://me@old.example", "ssh://me@fresh.example"
	f := endpointFile{
		Version:    1,
		SSHSockets: map[string]string{old: "/run/old.sock", fresh: "/run/fresh.sock"},
		Refused:    map[string]int64{old: now.Add(-25 * time.Hour).Unix(), fresh: now.Add(-time.Hour).Unix()},
	}
	b, err := json.Marshal(f)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "host-endpoints.json"), b, 0o600); err != nil {
		t.Fatal(err)
	}
	p := NewPool(dir, nil, nil)
	if r, err := p.Remembered(fresh); err != nil || r.Socket != "/run/fresh.sock" || r.RefusedUntil == 0 {
		t.Fatalf("Remembered(%s) = %+v %v", fresh, r, err)
	}
	p.store.mu.Lock()
	_, inMemory := p.store.f.Refused[old]
	p.store.mu.Unlock()
	if inMemory {
		t.Errorf("the expired refusal of %s is still in memory after the load", old)
	}
	got := readEndpoints(t, dir)
	if at, ok := got.Refused[old]; ok {
		t.Errorf("the expired refusal of %s is still in the file after the load (%d)", old, at)
	}
	if got.Refused[fresh] != f.Refused[fresh] {
		t.Errorf("the refusal of %s within its day: %d, want %d", fresh, got.Refused[fresh], f.Refused[fresh])
	}
	if got.SSHSockets[old] != "/run/old.sock" || got.SSHSockets[fresh] != "/run/fresh.sock" {
		t.Errorf("sockets = %v, want both kept", got.SSHSockets)
	}
}

// #292: a host's name is one host whatever its case, and the app keeps it in lower case
// (usableUrl): so does the target. Its key (host-endpoints.json, questions), the name its
// host key is kept under and the address dialled are in lower case; the user stays as
// written, since login names tell case apart.
func TestParseTargetLowersTheHost(t *testing.T) {
	for _, tc := range []struct{ locator, key, hostPort string }{
		{"ssh://Rex@Studio.EXAMPLE", "ssh://Rex@studio.example", "studio.example:22"},
		{"SSH://rex@STUDIO:2222", "ssh://rex@studio:2222", "studio:2222"},
		{"ssh://rex@[FE80::A1]", "ssh://rex@[fe80::a1]", "[fe80::a1]:22"},
		{"ssh://rex@[2001:DB8::1]:2222", "ssh://rex@[2001:db8::1]:2222", "[2001:db8::1]:2222"},
		{"ssh://rex@studio.example", "ssh://rex@studio.example", "studio.example:22"},
	} {
		target, err := ParseTarget(tc.locator)
		if err != nil {
			t.Errorf("ParseTarget(%s): %v", tc.locator, err)
			continue
		}
		if got := target.String(); got != tc.key {
			t.Errorf("ParseTarget(%s).String() = %s, want %s", tc.locator, got, tc.key)
		}
		if got := target.hostPort(); got != tc.hostPort {
			t.Errorf("ParseTarget(%s).hostPort() = %s, want %s", tc.locator, got, tc.hostPort)
		}
	}
}

// #292: a host-endpoints.json written while the pool kept the host's case is read under
// the lower-case key, and written back so. Where both spellings are there, the lower-case
// one stays and the other goes; a key that is no target is left alone.
func TestEndpointKeysOfAnotherCaseAreReadInLowerCase(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	f := endpointFile{
		Version: 1,
		SSHSockets: map[string]string{
			"ssh://rex@Studio.Example":    "/run/studio.sock",
			"ssh://rex@Both.example:2222": "/run/upper.sock",
			"ssh://rex@both.example:2222": "/run/lower.sock",
			"not a target":              "/run/other.sock",
		},
		Refused: map[string]int64{"ssh://rex@Studio.Example": now.Add(-time.Hour).Unix()},
	}
	b, err := json.Marshal(f)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "host-endpoints.json"), b, 0o600); err != nil {
		t.Fatal(err)
	}
	p := NewPool(dir, nil, nil)
	if r, err := p.Remembered("ssh://rex@studio.example"); err != nil || r.Socket != "/run/studio.sock" || r.RefusedUntil == 0 {
		t.Errorf("Remembered(studio.example) = %+v %v, want the socket and the refusal kept as Studio.Example", r, err)
	}
	// The locator's own case does not matter either.
	if r, err := p.Remembered("ssh://rex@STUDIO.example"); err != nil || r.Socket != "/run/studio.sock" {
		t.Errorf("Remembered(STUDIO.example) = %+v %v", r, err)
	}
	if r, err := p.Remembered("ssh://rex@both.example:2222"); err != nil || r.Socket != "/run/lower.sock" {
		t.Errorf("Remembered(both.example) = %+v %v, want the lower-case entry", r, err)
	}
	got := readEndpoints(t, dir)
	want := map[string]string{
		"ssh://rex@studio.example":    "/run/studio.sock",
		"ssh://rex@both.example:2222": "/run/lower.sock",
		"not a target":              "/run/other.sock",
	}
	if !maps.Equal(got.SSHSockets, want) {
		t.Errorf("the file's sockets after the load: %v, want %v", got.SSHSockets, want)
	}
	if _, ok := got.Refused["ssh://rex@studio.example"]; !ok || len(got.Refused) != 1 {
		t.Errorf("the file's refusals after the load: %v", got.Refused)
	}
}

// marksOf lists the Retry and AfterStall marks of the password and prompt questions in qs.
func marksOf(qs []Question) (retry, afterStall []bool) {
	for _, q := range qs {
		if q.Kind == "password" || q.Kind == "prompt" {
			retry, afterStall = append(retry, q.Retry), append(afterStall, q.AfterStall)
		}
	}
	return retry, afterStall
}

// #292: a host that closes the TCP connection after refusing an answer, late enough for the
// login to see the refusal first and ask again. The Retry question is withdrawn as soon as
// the connection's end is seen: it does not wait for the user (120 s) on a dead connection,
// whose login is password_refused whatever the answer. Kotlin may not have read a question
// that was withdrawn, so its refusal is carried to the next login.
func TestRetryOnAClosedConnectionIsWithdrawn(t *testing.T) {
	for _, tc := range []struct {
		name    string
		set     func(s *testServer)
		carried bool // the refused answer was a password's: the next login's question says so
	}{
		{"password", func(s *testServer) { s.password = "right" }, true},
		{"prompt", func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}}
		}, false},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(tc.set)
		// The host closes once the Retry question is out: no timing decides which comes first.
		asked := make(chan struct{})
		srv.set(func(s *testServer) { s.closeOnRefuse, s.closeWhen = true, asked })
		withdrawn := make(chan struct{}, 1)
		var once sync.Once
		a := &ctxAsker{fn: func(ctx context.Context, q Question) (string, bool) {
			switch {
			case q.Kind == "hostkey":
				return "trust", true
			case !q.Retry:
				return "wrong", true
			}
			// The user does not answer the Retry: only its withdrawal ends the wait.
			once.Do(func() { close(asked) })
			<-ctx.Done()
			withdrawn <- struct{}{}
			return "", false
		}}
		p := newTestPool(t, "", a)
		start := time.Now()
		_, err := p.Open(ctxFor(t, 8*time.Second), srv.locator())
		if e := asError(t, err); e.Code != "password_refused" {
			t.Fatalf("%s: %+v, want password_refused", tc.name, e)
		}
		if took := time.Since(start); took > 5*time.Second {
			t.Errorf("%s: the login took %v: the Retry question waited on the dead connection", tc.name, took)
		}
		select {
		case <-withdrawn:
		case <-time.After(2 * time.Second):
			t.Errorf("%s: no Retry question was asked and withdrawn; questions %+v", tc.name, a.questions())
		}
		if retry, _ := marksOf(a.questions()); !slices.Equal(retry, []bool{false, true}) {
			t.Errorf("%s: Retry of the questions = %v, want [false true]", tc.name, retry)
		}
		target, err := ParseTarget(srv.locator())
		if err != nil {
			t.Fatal(err)
		}
		tg := p.target(target)
		tg.mu.Lock()
		carried := tg.retryNext
		tg.mu.Unlock()
		if carried != tc.carried {
			t.Errorf("%s: the refusal is carried to the next login: %v, want %v", tc.name, carried, tc.carried)
		}
	}

	// A first password question is not withdrawn when the connection ends under it: the
	// login has refused nothing, and its answer still counts for the next login in Kotlin.
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password = "right" })
	asked := make(chan struct{})
	answer := make(chan struct{})
	a := &ctxAsker{fn: func(ctx context.Context, q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "trust", true
		}
		close(asked)
		select {
		case <-answer:
			return "right", true
		case <-ctx.Done():
			return "", false
		}
	}}
	p := newTestPool(t, "", a)
	done := make(chan error, 1)
	go func() {
		_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		done <- err
	}()
	<-asked
	srv.closeLogins() // the host goes away while the user reads the question
	select {
	case err := <-done:
		t.Fatalf("the first question was withdrawn when the connection ended: %v", err)
	case <-time.After(500 * time.Millisecond):
	}
	close(answer)
	if e := asError(t, <-done); e.Code != "closed_before_login" {
		t.Errorf("answered on a connection that had ended: %+v, want closed_before_login", e)
	}
	// That answer never reached the host: it is no close of the host's after an answer
	// (TestTwoAnswersClosedWithoutAReplyStop counts those).
	srv.set(func(s *testServer) { s.dropOnAnswer = true })
	a.fn = func(_ context.Context, q Question) (string, bool) {
		return map[bool]string{true: "trust", false: "right"}[q.Kind == "hostkey"], true
	}
	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "closed_before_login" {
		t.Errorf("one close after an answer: %+v, want closed_before_login (the first of two)", e)
	}
}

// #292: a refusal that ends the login before a Retry question can tell Kotlin of it leaves a
// saved password that the host no longer takes, sent again on every Reconnect. The pool
// carries the refusal: the next login's first question has Retry, on which Kotlin forgets the
// saved password and asks the user. Once that question is answered the mark is gone.
func TestRefusalNoQuestionToldOfIsCarriedToTheNextLogin(t *testing.T) {
	for _, tc := range []struct {
		name string
		set  func(s *testServer)
	}{
		// sshd's MaxAuthTries: the refused answer is the last try, and the host hangs up with
		// "Too many authentication failures" instead of a refusal (five keys and a password at
		// OpenSSH's default 6).
		{"the host hangs up: no tries left", func(s *testServer) { s.maxAuthTries = 1 }},
		// The host refuses and closes at once: the Retry, if it gets out at all, is withdrawn.
		{"the host closes after its refusal", func(s *testServer) { s.closeOnRefuse = true }},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(func(s *testServer) { s.password = "right" })
		srv.set(tc.set)
		var answer atomic.Value
		answer.Store("stale")
		a := &ctxAsker{fn: func(ctx context.Context, q Question) (string, bool) {
			switch {
			case q.Kind == "hostkey":
				return "trust", true
			case q.Retry && answer.Load() == "stale":
				// The first login's Retry on the closed connection: the user is not there yet.
				<-ctx.Done()
				return "", false
			}
			return answer.Load().(string), true
		}}
		p := newTestPool(t, "", a)
		loc := srv.locator()
		_, err := p.Open(ctxFor(t, 10*time.Second), loc)
		if e := asError(t, err); e.Code != "password_refused" {
			t.Fatalf("%s: %+v, want password_refused", tc.name, e)
		}
		before := len(a.questions())

		// Reconnect: the question says that the last answer was refused.
		answer.Store("right")
		roundTrip(t, mustOpen(t, p, loc), srv, tc.name)
		retry, afterStall := marksOf(a.questions()[before:])
		if !slices.Equal(retry, []bool{true}) || !slices.Equal(afterStall, []bool{false}) {
			t.Errorf("%s: the next login's questions: Retry %v, AfterStall %v; want one with Retry", tc.name, retry, afterStall)
		}
		before = len(a.questions())

		// The login after that asks plainly.
		srv.closeConns()
		waitFor(t, 5*time.Second, "the pool to see its connection end", func() bool { return !p.connected(loc) })
		roundTrip(t, mustOpen(t, p, loc), srv, tc.name)
		if retry, _ := marksOf(a.questions()[before:]); !slices.Equal(retry, []bool{false}) {
			t.Errorf("%s: the login after: Retry %v, want [false]", tc.name, retry)
		}
	}
}

// Which refusals are carried: the last of a login whose tries ran out (no question followed
// it). Not one a Retry question told of, answered or cancelled by the user; and not that of
// a login after a stalled answer, where the user answered and the saved password stays.
func TestWhichRefusalsAreCarried(t *testing.T) {
	login := func(t *testing.T, p *Pool, srv *testServer, answers ...string) (*Error, []bool) {
		t.Helper()
		var n atomic.Int32
		before := len(p.ask.(*asker).questions())
		p.ask.(*asker).fn = func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			i := int(n.Add(1)) - 1
			if i >= len(answers) || answers[i] == "" {
				return "", false // the user cancels
			}
			return answers[i], true
		}
		c, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		retry, _ := marksOf(p.ask.(*asker).questions()[before:])
		if err != nil {
			return asError(t, err), retry
		}
		c.Close()
		srv.closeConns()
		waitFor(t, 5*time.Second, "the pool to see its connection end", func() bool { return !p.connected(srv.locator()) })
		return nil, retry
	}
	newServer := func(t *testing.T) *testServer {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(func(s *testServer) { s.password = "right" })
		return srv
	}

	t.Run("wrong, then right", func(t *testing.T) {
		srv := newServer(t)
		p := newTestPool(t, "", &asker{})
		if e, retry := login(t, p, srv, "wrong", "right"); e != nil || !slices.Equal(retry, []bool{false, true}) {
			t.Fatalf("login 1: %+v, Retry %v", e, retry)
		}
		if e, retry := login(t, p, srv, "right"); e != nil || !slices.Equal(retry, []bool{false}) {
			t.Errorf("login 2: %+v, Retry %v; want a plain question", e, retry)
		}
	})
	t.Run("three wrong answers", func(t *testing.T) {
		srv := newServer(t)
		p := newTestPool(t, "", &asker{})
		if e, retry := login(t, p, srv, "a", "b", "c"); e == nil || e.Code != "password_refused" || !slices.Equal(retry, []bool{false, true, true}) {
			t.Fatalf("login 1: %+v, Retry %v", e, retry)
		}
		// The third refusal had no question after it: the next login's first one says it.
		if e, retry := login(t, p, srv, "right"); e != nil || !slices.Equal(retry, []bool{true}) {
			t.Errorf("login 2: %+v, Retry %v; want [true]", e, retry)
		}
	})
	t.Run("the user cancels the Retry", func(t *testing.T) {
		srv := newServer(t)
		p := newTestPool(t, "", &asker{})
		if e, retry := login(t, p, srv, "wrong", ""); e == nil || e.Code != "password_refused" || !slices.Equal(retry, []bool{false, true}) {
			t.Fatalf("login 1: %+v, Retry %v", e, retry)
		}
		// Kotlin had the Retry question, and the user's cancel: nothing is left to tell.
		if e, retry := login(t, p, srv, "right"); e != nil || !slices.Equal(retry, []bool{false}) {
			t.Errorf("login 2: %+v, Retry %v; want a plain question", e, retry)
		}
	})
	t.Run("a carried Retry the user cancels", func(t *testing.T) {
		srv := newServer(t)
		p := newTestPool(t, "", &asker{})
		if e, retry := login(t, p, srv, "a", "b", "c"); e == nil || e.Code != "password_refused" || !slices.Equal(retry, []bool{false, true, true}) {
			t.Fatalf("login 1: %+v, Retry %v", e, retry)
		}
		if e, retry := login(t, p, srv, ""); e == nil || e.Code != "declined" || !slices.Equal(retry, []bool{true}) {
			t.Fatalf("login 2: %+v, Retry %v; want declined after one question with Retry", e, retry)
		}
		if e, retry := login(t, p, srv, "right"); e != nil || !slices.Equal(retry, []bool{false}) {
			t.Errorf("login 3: %+v, Retry %v; want a plain question", e, retry)
		}
	})
	t.Run("after a stalled answer", func(t *testing.T) {
		srv := newServer(t)
		srv.set(func(s *testServer) { s.maxAuthTries = 1 })
		p := newTestPool(t, "", &asker{})
		target, err := ParseTarget(srv.locator())
		if err != nil {
			t.Fatal(err)
		}
		tg := p.target(target)
		tg.mu.Lock()
		tg.afterStall = true // the login before left an answer without a reply (TestStalledAnswerMarksNextQuestion)
		tg.mu.Unlock()
		// The user's answer is refused, with the host's hang-up: the saved password was not it.
		if e, retry := login(t, p, srv, "wrong"); e == nil || e.Code != "password_refused" || !slices.Equal(retry, []bool{false}) {
			t.Fatalf("login 1: %+v, Retry %v", e, retry)
		}
		// The next question says both: Retry drops the answer Kotlin kept for the last login,
		// and AfterStall with it keeps the saved password (which never answered).
		before := len(p.ask.(*asker).questions())
		if e, retry := login(t, p, srv, "right"); e != nil || !slices.Equal(retry, []bool{true}) {
			t.Errorf("login 2: %+v, Retry %v; want [true]", e, retry)
		}
		if _, afterStall := marksOf(p.ask.(*asker).questions()[before:]); !slices.Equal(afterStall, []bool{true}) {
			t.Errorf("login 2: AfterStall %v, want [true]: without it Kotlin forgets the saved password on the Retry", afterStall)
		}
		// A finished login ends both marks.
		before = len(p.ask.(*asker).questions())
		if e, retry := login(t, p, srv, "right"); e != nil || !slices.Equal(retry, []bool{false}) {
			t.Errorf("login 3: %+v, Retry %v; want a plain question", e, retry)
		}
		if _, afterStall := marksOf(p.ask.(*asker).questions()[before:]); !slices.Equal(afterStall, []bool{false}) {
			t.Errorf("login 3: AfterStall %v, want [false]", afterStall)
		}
	})
	t.Run("a hang-up after a Retry that was told", func(t *testing.T) {
		srv := newServer(t)
		srv.set(func(s *testServer) { s.maxAuthTries = 2 })
		p := newTestPool(t, "", &asker{})
		// The first refusal is told by the Retry question; the answer to that one meets the
		// host's limit, and no question follows the hang-up.
		if e, retry := login(t, p, srv, "a", "b"); e == nil || e.Code != "password_refused" || !slices.Equal(retry, []bool{false, true}) {
			t.Fatalf("login 1: %+v, Retry %v", e, retry)
		}
		if e, retry := login(t, p, srv, "right"); e != nil || !slices.Equal(retry, []bool{true}) {
			t.Errorf("login 2: %+v, Retry %v; want [true]", e, retry)
		}
	})
	t.Run("a login that asks nothing leaves the mark", func(t *testing.T) {
		srv := newServer(t)
		srv.set(func(s *testServer) { s.maxAuthTries = 1 })
		a := &asker{}
		p := newTestPool(t, "", a)
		if e, _ := login(t, p, srv, "stale"); e == nil || e.Code != "password_refused" {
			t.Fatalf("login 1: %+v", e)
		}
		// The host key question is declined: the login ends before any password question.
		a.fn = func(Question) (string, bool) { return "", false }
		_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		if e := asError(t, err); e.Code != "declined" {
			t.Fatalf("login 2: %+v, want declined", e)
		}
		if e, retry := login(t, p, srv, "right"); e != nil || !slices.Equal(retry, []bool{true}) {
			t.Errorf("login 3: %+v, Retry %v; want [true]", e, retry)
		}
	})
}

// #292: a host that hangs up on a wrong password before it sends its refusal (Dropbear with a
// low -T, a firewall). One such login is a dropped connection, and the core tries again with
// the saved password. The second stops as password_refused, so a stale saved password is not
// sent every 15 s; the login after it carries AfterStall, on which Kotlin asks
// the user and keeps the saved password (the host never said that it was wrong).
func TestTwoAnswersClosedWithoutAReplyStop(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password, s.dropOnAnswer = "right", true })
	var answer atomic.Value
	answer.Store("stale")
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "trust", true
		}
		return answer.Load().(string), true
	}}
	p := newTestPool(t, "", a)
	loc := srv.locator()
	open := func() *Error {
		t.Helper()
		_, err := p.Open(ctxFor(t, 10*time.Second), loc)
		return asError(t, err)
	}
	if e := open(); e.Code != "closed_before_login" {
		t.Fatalf("the first close: %+v, want closed_before_login", e)
	}
	want := "127.0.0.1 kept closing the connection after Velo sent the password for rex. Reconnect to enter the password again."
	// Two logins got past the key stage: closes counted there before do not stay.
	target, err := ParseTarget(loc)
	if err != nil {
		t.Fatal(err)
	}
	tg := p.target(target)
	tg.mu.Lock()
	tg.keyCloses = keyStageCloses - 1
	tg.mu.Unlock()
	if e := open(); e.Code != "password_refused" || e.Sentence != want || e.Fix != "" {
		t.Fatalf("the second close after a password: %+v, want password_refused %q", e, want)
	}
	tg.mu.Lock()
	keyCloses := tg.keyCloses
	tg.mu.Unlock()
	if keyCloses != 0 {
		t.Errorf("key-stage closes after a login past the key stage: %d, want 0", keyCloses)
	}
	retry, afterStall := marksOf(a.questions())
	if !slices.Equal(retry, []bool{false, false}) || !slices.Equal(afterStall, []bool{false, false}) {
		t.Errorf("the two logins' questions: Retry %v, AfterStall %v; want plain ones", retry, afterStall)
	}

	// Reconnect: the user is asked (AfterStall), and the right password logs in.
	before := len(a.questions())
	srv.set(func(s *testServer) { s.dropOnAnswer = false })
	answer.Store("right")
	roundTrip(t, mustOpen(t, p, loc), srv, "after the stop")
	if retry, afterStall := marksOf(a.questions()[before:]); !slices.Equal(retry, []bool{false}) || !slices.Equal(afterStall, []bool{true}) {
		t.Errorf("the login after the stop: Retry %v, AfterStall %v; want one question with AfterStall", retry, afterStall)
	}

	// A finished login starts the count again: one more close is a dropped connection.
	srv.closeConns()
	waitFor(t, 5*time.Second, "the pool to see its connection end", func() bool { return !p.connected(loc) })
	before = len(a.questions())
	srv.set(func(s *testServer) { s.dropOnAnswer = true })
	if e := open(); e.Code != "closed_before_login" {
		t.Errorf("a close after a finished login: %+v, want closed_before_login", e)
	}
	if _, afterStall := marksOf(a.questions()[before:]); !slices.Equal(afterStall, []bool{false}) {
		t.Errorf("the question after a finished login: AfterStall %v, want [false]", afterStall)
	}
	// So does the host's reply to an answer: a refusal between two closes is no second close.
	srv.set(func(s *testServer) { s.dropOnAnswer = false })
	answer.Store("wrong")
	if e := open(); e.Code != "password_refused" || e.Fix != "" {
		t.Errorf("three refused answers: %+v, want the host's refusal", e)
	}
	srv.set(func(s *testServer) { s.dropOnAnswer = true })
	if e := open(); e.Code != "closed_before_login" {
		t.Errorf("a close after a refusal: %+v, want closed_before_login", e)
	}
}

// kindsOf lists the kind and the Retry mark of the password and prompt questions in qs, as
// "password", "password!" (with Retry), "prompt", "prompt!".
func kindsOf(qs []Question) (out []string) {
	for _, q := range qs {
		if q.Kind == "password" || q.Kind == "prompt" {
			out = append(out, q.Kind+map[bool]string{true: "!"}[q.Retry])
		}
	}
	return out
}

// Review of the #292 login change: hosts that ask in more than one step (PAM asks the
// password, then a code, in rounds of their own; sshd's AuthenticationMethods makes the
// password a partial success). The refusal carried to the next login is the refusal of a
// password, and only a password question carries it: Kotlin forgets the saved password on
// that mark and on nothing else.
func TestCarriedRefusalIsAPasswordsOnly(t *testing.T) {
	newServer := func(t *testing.T, set func(s *testServer)) *testServer {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(set)
		return srv
	}
	// answering answers the password questions with password and the prompts with code;
	// "" declines that kind (the user cancels it).
	answering := func(a *asker, password, code string) {
		a.fn = func(q Question) (string, bool) {
			switch q.Kind {
			case "hostkey":
				return "trust", true
			case "password":
				return password, password != ""
			}
			return code, code != ""
		}
	}
	open := func(t *testing.T, p *Pool, srv *testServer) (*Error, []string) {
		t.Helper()
		a := p.ask.(*asker)
		before := len(a.questions())
		c, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		asked := kindsOf(a.questions()[before:])
		if err != nil {
			return asError(t, err), asked
		}
		c.Close()
		srv.closeConns()
		waitFor(t, 5*time.Second, "the pool to see its connection end", func() bool { return !p.connected(srv.locator()) })
		return nil, asked
	}
	passwordThenCode := []kbdQuestion{{text: "Password:", want: "right"}, {text: "Verification code: ", want: "123456", echo: true}}

	// The saved password answers the first round and the user cancels the code. The host
	// refused nothing: the login is declined, and the next one sends the password plainly.
	t.Run("a cancelled later round refuses nothing", func(t *testing.T) {
		srv := newServer(t, func(s *testServer) { s.kbd, s.kbdRounds = passwordThenCode, true })
		a := &asker{}
		p := newTestPool(t, "", a)
		answering(a, "right", "")
		if e, asked := open(t, p, srv); e == nil || e.Code != "declined" || !slices.Equal(asked, []string{"password", "prompt"}) {
			t.Fatalf("login 1: %+v, questions %v; want declined after the password and the code", e, asked)
		}
		answering(a, "right", "123456")
		if e, asked := open(t, p, srv); e != nil || !slices.Equal(asked, []string{"password", "prompt"}) {
			t.Errorf("login 2: %+v, questions %v; want plain ones (a Retry would have Kotlin forget a right password)", e, asked)
		}
	})
	// Two prompts of one round: the first is answered, the second cancelled.
	t.Run("a cancelled later prompt refuses nothing", func(t *testing.T) {
		srv := newServer(t, func(s *testServer) { s.kbd = passwordThenCode })
		a := &asker{}
		p := newTestPool(t, "", a)
		calls := 0
		a.fn = func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			calls++
			return "right", calls == 1 // two prompts in one round are both kind prompt
		}
		if e, _ := open(t, p, srv); e == nil || e.Code != "declined" {
			t.Fatalf("login 1: %+v, want declined", e)
		}
	})
	// AuthenticationMethods password,keyboard-interactive: the host takes the password and
	// refuses the codes until the tries run out. The password was not refused.
	t.Run("a password the host took is not carried", func(t *testing.T) {
		srv := newServer(t, func(s *testServer) {
			s.password, s.kbdAfterPass = "right", true
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}}
		})
		a := &asker{}
		p := newTestPool(t, "", a)
		answering(a, "right", "000000")
		if e, asked := open(t, p, srv); e == nil || e.Code != "password_refused" || !slices.Equal(asked, []string{"password", "prompt", "prompt!"}) {
			t.Fatalf("login 1: %+v, questions %v; want the password, then two refused codes", e, asked)
		}
		answering(a, "right", "123456")
		if e, asked := open(t, p, srv); e != nil || !slices.Equal(asked, []string{"password", "prompt"}) {
			t.Errorf("login 2: %+v, questions %v; want plain ones", e, asked)
		}
	})
	// The code is asked first, then the password, which is stale; the host hangs up at its
	// limit. On the next login the code question does not use the mark up: the password
	// question carries it.
	t.Run("the mark waits for the password question", func(t *testing.T) {
		srv := newServer(t, func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}, {text: "Password:", want: "right"}}
			s.kbdRounds, s.maxAuthTries = true, 1
		})
		a := &asker{}
		p := newTestPool(t, "", a)
		answering(a, "stale", "123456")
		if e, asked := open(t, p, srv); e == nil || e.Code != "password_refused" || !slices.Equal(asked, []string{"prompt", "password"}) {
			t.Fatalf("login 1: %+v, questions %v", e, asked)
		}
		answering(a, "right", "123456")
		if e, asked := open(t, p, srv); e != nil || !slices.Equal(asked, []string{"prompt", "password!"}) {
			t.Errorf("login 2: %+v, questions %v; want the Retry on the password question only", e, asked)
		}
		if e, asked := open(t, p, srv); e != nil || !slices.Equal(asked, []string{"prompt", "password"}) {
			t.Errorf("login 3: %+v, questions %v; want plain ones", e, asked)
		}
	})
	// Second review: the refused login followed a stalled answer, so the user answered it and
	// the saved password must stay. A login between that ends at the keys (the host's limit)
	// clears the target's stall mark; the carried refusal still says that the answer was the
	// user's, so the password question has Retry with AfterStall, not Retry alone.
	t.Run("a carried refusal of the user's answer stays the user's", func(t *testing.T) {
		srv := newServer(t, func(s *testServer) { s.password, s.maxAuthTries = "right", 1 })
		a := &asker{}
		p := newTestPool(t, "", a)
		target, err := ParseTarget(srv.locator())
		if err != nil {
			t.Fatal(err)
		}
		tg := p.target(target)
		tg.mu.Lock()
		tg.afterStall = true // the login before left an answer without a reply
		tg.mu.Unlock()
		answering(a, "typed wrong", "")
		if e, _ := open(t, p, srv); e == nil || e.Code != "password_refused" {
			t.Fatalf("login 1: %+v, want password_refused", e)
		}
		// The host hangs up at its limit while the keys go out: no password question.
		pems, _, _ := newKeys(t, 1)
		_, _, other := newKeys(t, 1)
		srv.set(func(s *testServer) { s.keys = other })
		if err := p.SetIdentities(pems); err != nil {
			t.Fatal(err)
		}
		if e, asked := open(t, p, srv); e == nil || e.Code != "key_refused" || len(asked) != 0 {
			t.Fatalf("login 2: %+v, questions %v; want key_refused with no question", e, asked)
		}
		if err := p.SetIdentities(nil); err != nil {
			t.Fatal(err)
		}
		srv.set(func(s *testServer) { s.maxAuthTries = 0 })
		answering(a, "right", "")
		before := len(a.questions())
		if e, asked := open(t, p, srv); e != nil || !slices.Equal(asked, []string{"password!"}) {
			t.Errorf("login 3: %+v, questions %v; want the password question with Retry", e, asked)
		}
		if _, afterStall := marksOf(a.questions()[before:]); !slices.Equal(afterStall, []bool{true}) {
			t.Errorf("login 3: AfterStall %v, want [true]: on Retry alone Kotlin forgets the saved password, which the host never refused", afterStall)
		}
	})
	// keyboard-interactive,password: a code is refused, the next one is taken, and then the
	// password is asked for the first time. No password was refused: its question is plain.
	t.Run("a refused code puts no Retry on the password question", func(t *testing.T) {
		srv := newServer(t, func(s *testServer) {
			s.password, s.passAfterKbd = "right", true
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}}
		})
		a := &asker{}
		p := newTestPool(t, "", a)
		codes := []string{"000000", "123456"}
		a.fn = func(q Question) (string, bool) {
			switch q.Kind {
			case "hostkey":
				return "trust", true
			case "password":
				return "right", true
			}
			code := codes[0]
			codes = codes[1:]
			return code, true
		}
		if e, asked := open(t, p, srv); e != nil || !slices.Equal(asked, []string{"prompt", "prompt!", "password"}) {
			t.Errorf("%+v, questions %v; want a plain password question after the codes", e, asked)
		}
	})
	// A code alone, refused by a hang-up: there is no password to forget, and nothing to carry.
	t.Run("a refused code is not carried", func(t *testing.T) {
		srv := newServer(t, func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}}
			s.maxAuthTries = 1
		})
		a := &asker{}
		p := newTestPool(t, "", a)
		answering(a, "", "000000")
		if e, _ := open(t, p, srv); e == nil || e.Code != "password_refused" {
			t.Fatalf("login 1: %+v", e)
		}
		answering(a, "", "123456")
		if e, asked := open(t, p, srv); e != nil || !slices.Equal(asked, []string{"prompt"}) {
			t.Errorf("login 2: %+v, questions %v; want a plain prompt", e, asked)
		}
	})
	// A host that closes after a code, without a reply: the user answers each login, so
	// nothing goes out by itself and the core does not stop on it.
	t.Run("closes after a code are not counted", func(t *testing.T) {
		srv := newServer(t, func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Verification code: ", want: "123456", echo: true}}
			s.dropOnAnswer = true
		})
		a := &asker{}
		p := newTestPool(t, "", a)
		answering(a, "", "123456")
		for i := range 3 {
			if e, _ := open(t, p, srv); e == nil || e.Code != "closed_before_login" {
				t.Errorf("login %d: %+v, want closed_before_login", i+1, e)
			}
		}
	})
}

// Second review of the #292 login change: PAM asks the password and then the code, in
// rounds of their own. A code is refused; on the next try the user gives the password again
// and the connection ends while the code question waits (the pool withdraws it). The code
// round's request is the host's answer to the password round and no verdict on it: the login
// carries no refusal, so Kotlin keeps the password the user just gave for the next login.
func TestAWithdrawnLaterRoundRefusesNothing(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) {
		s.kbd = []kbdQuestion{{text: "Password:", want: "right"}, {text: "Verification code: ", want: "123456", echo: true}}
		s.kbdRounds = true
	})
	var prompts atomic.Int32
	a := &ctxAsker{fn: func(ctx context.Context, q Question) (string, bool) {
		switch q.Kind {
		case "hostkey":
			return "trust", true
		case "password":
			return "right", true
		}
		if prompts.Add(1) == 1 {
			return "000000", true // the first code is wrong: the host refuses the try
		}
		// The second code question: the host goes away while it waits.
		srv.closeLogins()
		<-ctx.Done()
		return "", false
	}}
	p := newTestPool(t, "", a)
	_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
	if e := asError(t, err); e.Code != "password_refused" {
		t.Fatalf("%+v, want password_refused (a code was refused in this login)", e)
	}
	if got := kindsOf(a.questions()); !slices.Equal(got, []string{"password", "prompt", "password!", "prompt!"}) {
		t.Fatalf("questions %v", got)
	}
	target, err := ParseTarget(srv.locator())
	if err != nil {
		t.Fatal(err)
	}
	tg := p.target(target)
	tg.mu.Lock()
	carried := tg.retryNext
	tg.mu.Unlock()
	if carried {
		t.Errorf("the refusal is carried although the Retry password question told Kotlin, and the host gave no verdict on its answer")
	}
}

// Third review: which closes after a password count toward the stop when the host asks in
// rounds. After the password and PAM's empty round nothing else went out, and no question
// waits for the user: the host closed on the password, and the second such login stops, or
// the saved password would go out every 15 s. After the password and a code, the code went
// out last and the user gives one each login: those closes are dropped connections.
func TestClosesAfterAPasswordInRounds(t *testing.T) {
	for _, tc := range []struct {
		name  string
		set   func(s *testServer)
		stops bool
	}{
		{"the password, then an empty round", func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Password:", want: "right"}}
			s.kbdEmptyRound, s.kbdResetLogin = true, true
		}, true},
		{"the password, then a code", func(s *testServer) {
			s.kbd = []kbdQuestion{{text: "Password:", want: "right"}, {text: "Verification code: ", want: "123456", echo: true}}
			s.kbdRounds, s.dropOnAnswer = true, true
		}, false},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(tc.set)
		a := &asker{fn: func(q Question) (string, bool) {
			switch q.Kind {
			case "hostkey":
				return "trust", true
			case "password":
				return "right", true
			}
			return "123456", true
		}}
		p := newTestPool(t, "", a)
		var codes []string
		for range 3 {
			_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
			codes = append(codes, asError(t, err).Code)
		}
		want := []string{"closed_before_login", "closed_before_login", "closed_before_login"}
		if tc.stops {
			want[1] = "password_refused" // the second; the third is the first of a new count
		}
		if !slices.Equal(codes, want) {
			t.Errorf("%s: the three logins ended %v, want %v", tc.name, codes, want)
		}
	}
}

// #302 (TestABannerThenCloseIsNotCarried of #296 before): sshd's PAM account step can turn a
// login away after the right password and say why (pam_nologin while the host boots or shuts
// down, an expired account). With the password method its words come as a banner and the
// connection closes, with no success and no failure (measured with OpenSSH 9.6p1). No refusal
// came, so the password was right: the login is login_refused with the host's first line
// under the sentence, and nothing is carried, so the next login sends the saved password
// plainly and Kotlin keeps it. The first two such logins are closed_before_login, which the
// core retries (a host that boots lets the login in a little later); the third stops.
//
// sshd logs each such login as "Failed password", which fail2ban counts (5 within 10 minutes
// by default), so the three are all the host gets by itself: after the stop every login it
// turns away stops at once (the user's Reconnect: one line each), until it has turned none
// away for turnAwayMemory. That time runs from the last one it turned away, not from the
// stop (fail2ban's window slides), and a login that finishes between does not give the three
// back: two boots within the time would write five lines. A count that never reached three
// goes the same way (review of #302: two logins turned away hours ago are no part of a row).
func TestATurnedAwayLoginRetriesTwiceThenStops(t *testing.T) {
	const nologin = "System is booting up. Unprivileged users are not permitted to log in yet.\n\n"
	sentence := "127.0.0.1 didn't let rex log in.\n127.0.0.1 says: System is booting up. Unprivileged users are not permitted to log in yet."
	words := func(on bool) string {
		if on {
			return nologin
		}
		return ""
	}
	for _, tc := range []struct {
		name string
		set  func(s *testServer, on bool)
	}{
		{"the password method", func(s *testServer, on bool) { s.password, s.accountDenied = "right", words(on) }},
		// Not what OpenSSH sends with keyboard-interactive (see the next test); kept from #296:
		// a banner after the answers and a close read the same whatever the method.
		{"keyboard-interactive, a banner", func(s *testServer, on bool) {
			s.kbd, s.kbdEmptyRound, s.accountDenied = []kbdQuestion{{text: "Password:", want: "right"}}, true, words(on)
		}},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(func(s *testServer) { tc.set(s, true) })
		a := &asker{fn: func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			return "right", true
		}}
		p := newTestPool(t, "", a)
		p.turnAwayPause = time.Millisecond // production: 15 s (TestATurnedAwayLoginWaitsBeforeTheNextTry)
		var later atomic.Int64             // how far the pool's clock is ahead
		start := time.Now()
		p.now = func() time.Time { return start.Add(time.Duration(later.Load())) }
		loc := srv.locator()
		logins := 0
		expect := func(step string, codes ...string) {
			t.Helper()
			for i, want := range codes {
				_, err := p.Open(ctxFor(t, 10*time.Second), loc)
				logins++
				if e := asError(t, err); e.Code != want || e.Sentence != sentence || e.Fix != "" {
					t.Fatalf("%s, %s, login %d: %+v\nwant %s %q", tc.name, step, i+1, e, want, sentence)
				}
			}
		}
		expect("three, then two Reconnects", "closed_before_login", "closed_before_login", "login_refused", "login_refused", "login_refused")
		later.Add(int64(turnAwayMemory - time.Second))
		expect("a Reconnect within the memory of the last one", "login_refused")
		later.Add(int64(turnAwayMemory - time.Second)) // past the memory of the stop, within that of the last
		expect("a Reconnect within the memory of the one before", "login_refused")
		later.Add(int64(turnAwayMemory + time.Second))
		expect("once the host turned none away for the memory", "closed_before_login", "closed_before_login")
		// The host is up: the saved password logs in. The count stays: one try is left.
		srv.set(func(s *testServer) { tc.set(s, false) })
		roundTrip(t, mustOpen(t, p, loc), srv, tc.name+": after the boot")
		logins++
		srv.closeConns()
		waitFor(t, 5*time.Second, "the pool to see its connection end", func() bool { return !p.connected(loc) })
		srv.set(func(s *testServer) { tc.set(s, true) })
		expect("after a login, within the memory", "login_refused")
		// Two turned away, and none for the memory: the next is the first of a row again.
		later.Add(int64(turnAwayMemory + time.Second))
		expect("a new row", "closed_before_login", "closed_before_login")
		later.Add(int64(6 * time.Hour))
		expect("hours after two", "closed_before_login", "closed_before_login", "login_refused")
		// Asked for plainly every time: a Retry has Kotlin forget a right password.
		if got := kindsOf(a.questions()); len(got) != logins || slices.ContainsFunc(got, func(k string) bool { return k != "password" }) {
			t.Errorf("%s: the questions of the %d logins: %v, want plain password questions", tc.name, logins, got)
		}
	}
}

// Second review of #302: only time starts the count of turned-away logins again. A password
// the host refuses between two of them does not: its line is in the host's log as well.
func TestARefusalBetweenLeavesTheTurnAwayCount(t *testing.T) {
	const booting = "System is booting up."
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password = "right" })
	answers := []string{"right", "wrong", "", "right", "right"} // "": the Retry question, declined
	var asked atomic.Int32
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "trust", true
		}
		answer := answers[asked.Add(1)-1]
		return answer, answer != ""
	}}
	p := newTestPool(t, "", a)
	p.turnAwayPause = time.Millisecond
	var codes []string
	for _, denied := range []string{booting, "", booting, booting} { // the second login meets a host that is up
		srv.set(func(s *testServer) { s.accountDenied = denied })
		_, err := p.Open(ctxFor(t, 10*time.Second), srv.locator())
		codes = append(codes, asError(t, err).Code)
	}
	if want := []string{"closed_before_login", "password_refused", "closed_before_login", "login_refused"}; !slices.Equal(codes, want) {
		t.Errorf("the four logins ended %v, want %v", codes, want)
	}
	if got := kindsOf(a.questions()); !slices.Equal(got, []string{"password", "password", "password!", "password", "password"}) {
		t.Errorf("questions %v", got)
	}
}

// #302: the tries the core makes by itself after a login the host turned away are sshd log
// lines that fail2ban counts, and a host that boots needs a moment: the next login of the
// target waits until turnAwayPause has passed since the host turned the last one away,
// whatever the core's own backoff is (1 s after the first failure). Forget ends the wait. After
// the stop nothing waits: the next login is the user's Reconnect.
func TestATurnedAwayLoginWaitsBeforeTheNextTry(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password, s.accountDenied = "right", "System is booting up." })
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "trust", true
		}
		return "right", true
	}}
	p := newTestPool(t, "", a)
	const pause = time.Second
	p.turnAwayPause = pause
	loc := srv.locator()
	open := func(want string) (took time.Duration) {
		t.Helper()
		start := time.Now()
		_, err := p.Open(ctxFor(t, 10*time.Second), loc)
		if e := asError(t, err); e.Code != want {
			t.Fatalf("%+v, want %s", e, want)
		}
		return time.Since(start)
	}
	if took := open("closed_before_login"); took > pause/2 {
		t.Errorf("the first login took %v: nothing to wait for", took)
	}
	if took := open("closed_before_login"); took < pause*9/10 {
		t.Errorf("the second login ended %v after the first, want it to wait %v", took, pause)
	}
	// Only what is left of the pause: a try that comes later waits less.
	time.Sleep(pause * 6 / 10)
	if took := open("login_refused"); took < pause*3/10 || took > pause*8/10 {
		t.Errorf("the third login, tried %v after the second ended, took %v, want the rest of %v", pause*6/10, took, pause)
	}
	if took := open("login_refused"); took > pause/2 {
		t.Errorf("a login after the stop took %v: the user's Reconnect does not wait", took)
	}
	if n := count(srv.Auths(), "password"); n != 4 {
		t.Errorf("%d passwords reached the host, want 4", n)
	}

	// Forget ends the wait, and the login with it.
	p2 := newTestPool(t, "", a)
	p2.turnAwayPause = 30 * time.Second
	if _, err := p2.Open(ctxFor(t, 10*time.Second), loc); asError(t, err).Code != "closed_before_login" {
		t.Fatalf("%v, want closed_before_login", err)
	}
	done := make(chan error, 1)
	go func() {
		_, err := p2.Open(ctxFor(t, 10*time.Second), loc)
		done <- err
	}()
	time.Sleep(100 * time.Millisecond)
	p2.Forget(loc)
	select {
	case err := <-done:
		if e := asError(t, err); e.Code != "closed_before_login" {
			t.Errorf("a login forgotten while it waited: %+v, want closed_before_login", e)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Forget did not end the wait")
	}
	if n := count(srv.Auths(), "password"); n != 5 {
		t.Errorf("%d passwords reached the host, want 5: the forgotten login sent none", n)
	}

	// A clock that was set back makes the wait no longer than the pause.
	p3 := newTestPool(t, "", a)
	p3.turnAwayPause = 200 * time.Millisecond
	var back atomic.Int64
	begun := time.Now()
	p3.now = func() time.Time { return begun.Add(-time.Duration(back.Load())) }
	if _, err := p3.Open(ctxFor(t, 10*time.Second), loc); asError(t, err).Code != "closed_before_login" {
		t.Fatalf("%v, want closed_before_login", err)
	}
	back.Store(int64(3 * time.Second))
	start := time.Now()
	if _, err := p3.Open(ctxFor(t, 10*time.Second), loc); asError(t, err).Code != "closed_before_login" {
		t.Fatalf("%v, want closed_before_login", err)
	}
	if took := time.Since(start); took < 150*time.Millisecond || took > 2*time.Second {
		t.Errorf("the login after the clock went back 3 s took %v, want the pause of 200 ms", took)
	}
}

// #302: with keyboard-interactive, OpenSSH's PAM sends its words in a round without questions
// after the password's round, and then a real refusal (measured with OpenSSH 9.6p1). That login
// is login_refused at once, the host's first line under the sentence: a wrong password with a
// PAM module that has its say (pam_faillock, which locks the account at 3 failures by default)
// looks the same, so the core does not try again by itself, whether or not the connection
// closes with the refusal or instead of it. The refusal is not carried, so
// Kotlin keeps the saved password (#296 carried it, and Kotlin forgot a right one). No other
// method is tried after it: OpenSSH refuses the next keyboard-interactive attempt and then
// closes, and the host here would let the password method in.
func TestATurnedAwayKeyboardInteractiveLoginStopsAtOnce(t *testing.T) {
	const expired = "Your account has expired; please contact your system administrator."
	sentence := "127.0.0.1 didn't let rex log in.\n127.0.0.1 says: " + expired
	for _, tc := range []struct {
		name                           string
		closes, unrefused, passwordToo bool
		maxAuthTries                   int
	}{
		{"the refusal", false, false, false, 0},
		{"the refusal, then a close at once", true, false, false, 0},
		{"a close without the refusal", false, true, false, 0}, // no host is known to; the round's words decide
		{"the refusal, the password method offered too", false, false, true, 0},
		// Review of #302: the refusal that is the host's MaxAuthTries-th failure comes as a
		// disconnect ("Too many authentication failures"; measured with OpenSSH 9.6p1 at
		// MaxAuthTries 1), on which x/crypto ends the handshake without asking for another
		// method. It read as a refused password, and was carried.
		{"the hang-up of MaxAuthTries in place of the refusal", false, false, false, 1},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(func(s *testServer) {
			s.kbd, s.kbdDenied = []kbdQuestion{{text: "Password:", want: "right"}}, expired
			s.closeOnRefuse, s.kbdDeniedClose, s.maxAuthTries = tc.closes, tc.unrefused, tc.maxAuthTries
			if tc.passwordToo {
				s.password = "right"
			}
		})
		a := &asker{fn: func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			return "right", true
		}}
		p := newTestPool(t, "", a) // with the pause of production: these logins do not wait
		loc := srv.locator()
		var errs []error
		for range 3 {
			_, err := p.Open(ctxFor(t, 10*time.Second), loc)
			errs = append(errs, err)
		}
		// Each login is asked for the password plainly: a Retry has Kotlin forget a right one.
		if got := kindsOf(a.questions()); !slices.Equal(got, []string{"password", "password", "password"}) {
			t.Errorf("%s: the questions of the three logins: %v, want plain password questions", tc.name, got)
		}
		for i, err := range errs {
			if e, ok := err.(*Error); !ok || e.Code != "login_refused" || e.Sentence != sentence || e.Fix != "" {
				t.Fatalf("%s, login %d: %v\nwant login_refused %q", tc.name, i+1, err, sentence)
			}
		}
		if n := srv.KbdTries(); n != 3 {
			t.Errorf("%s: %d keyboard-interactive attempts in three logins, want one each", tc.name, n)
		}
		if tc.passwordToo && slices.Contains(srv.Auths(), "password") {
			t.Errorf("%s: the password method was tried after the host turned the login away", tc.name)
		}
		// The account is renewed: the saved password logs in, asked for plainly.
		srv.set(func(s *testServer) { s.kbdDenied, s.maxAuthTries = "", 0 })
		roundTrip(t, mustOpen(t, p, loc), srv, tc.name+": after the renewal")
		if got := kindsOf(a.questions()); len(got) != 4 || got[3] != "password" {
			t.Errorf("%s: the questions of the four logins: %v, want a plain password question last", tc.name, got)
		}
	}
}

// #302: a login the host turned away was answered: the host took the answer and said no to
// the account. That is its reply to the answer, so the stall mark goes, as after a refusal:
// the tries that follow by themselves ask plainly, and Kotlin answers them from the password
// the user just gave instead of asking again each time.
func TestATurnedAwayLoginClearsTheStallMark(t *testing.T) {
	for _, tc := range []struct {
		name string
		set  func(s *testServer)
		code string
	}{
		{"a banner, then a close", func(s *testServer) { s.password, s.accountDenied = "right", "System is booting up." }, "closed_before_login"},
		{"PAM's words, then a refusal", func(s *testServer) {
			s.kbd, s.kbdDenied = []kbdQuestion{{text: "Password:", want: "right"}}, "System is booting up."
		}, "login_refused"},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(tc.set)
		release := make(chan struct{})
		var answers atomic.Int32
		srv.set(func(s *testServer) {
			s.onAnswer = func() {
				if answers.Add(1) == 1 { // the first answer waits for its reply
					select {
					case <-release:
					case <-time.After(10 * time.Second):
					}
				}
			}
		})
		a := &asker{fn: func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			return "right", true
		}}
		p := newTestPool(t, "", a)
		p.stepTimeout = 3 * time.Second // the held reply waits 10 s
		p.turnAwayPause = time.Millisecond
		loc := srv.locator()
		var codes []string
		for i := range 3 {
			_, err := p.Open(ctxFor(t, 10*time.Second), loc)
			if i == 0 {
				close(release)
			}
			codes = append(codes, asError(t, err).Code)
		}
		if want := []string{"closed_before_login", tc.code, tc.code}; !slices.Equal(codes, want) {
			t.Errorf("%s: the three logins ended %v, want %v", tc.name, codes, want)
		}
		var marks []bool
		for _, q := range a.questions() {
			if q.Kind == "password" {
				marks = append(marks, q.AfterStall)
				if q.Retry {
					t.Errorf("%s: a Retry question: %+v", tc.name, q)
				}
			}
		}
		if !slices.Equal(marks, []bool{false, true, false}) {
			t.Errorf("%s: AfterStall of the three logins' questions = %v, want [false true false]", tc.name, marks)
		}
	}
}

// #302 (a limit #296 left): a banner between two keyboard-interactive rounds came before the
// answers of the later round, so it says nothing of them: a close after those answers, with
// bytes since, is the refusal of an answer, as without the banner, and not a login the host
// turned away (which the core would try again by itself).
func TestABannerBeforeARoundsAnswersIsNoWordOnThem(t *testing.T) {
	target, err := ParseTarget("ssh://rex@127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	a := &asker{fn: func(Question) (string, bool) { return "answer", true }}
	p := newTestPool(t, "", a)
	for _, tc := range []struct {
		name       string
		later      bool // a round with a question follows the banner
		turnedAway bool
	}{
		{"the banner, then a close", false, true},
		{"the banner, a round with a question, then a close", true, false},
	} {
		client, host := net.Pipe()
		defer client.Close()
		defer host.Close()
		conn := &sniffConn{Conn: client}
		l := &login{p: p, tg: p.target(target), t: target, ctx: context.Background(), conn: conn, watch: &watchdog{conn: client}}
		defer l.watch.finish()
		if _, err := l.challenge("", "", []string{"Password:"}, []bool{false}); err != nil {
			t.Fatal(err)
		}
		conn.read += 100 // the banner's bytes
		if err := l.banner("Maintenance at noon."); err != nil {
			t.Fatal(err)
		}
		if tc.later {
			conn.read += 60 // the round's request
			if _, err := l.challenge("", "", []string{"Verification code: "}, []bool{true}); err != nil {
				t.Fatal(err)
			}
		}
		conn.read += 52 // what the host sent after the last answer
		conn.fail()     // and the connection ended
		if _, err := l.nextAuth(&ssh.ClientAuthContext{AllowedMethods: []string{"keyboard-interactive"}}); !errors.Is(err, errLoginClosed) {
			t.Fatalf("%s: nextAuth: %v, want errLoginClosed", tc.name, err)
		}
		if l.turnedAway != tc.turnedAway || l.refused == tc.turnedAway {
			t.Errorf("%s: turnedAway %v, refused %v; want turnedAway %v, refused %v", tc.name, l.turnedAway, l.refused, tc.turnedAway, !tc.turnedAway)
		}
		if tc.turnedAway && l.said != "Maintenance at noon." {
			t.Errorf("%s: said %q, want the banner's words", tc.name, l.said)
		}
	}
}

// Review of #302: a login the host turned away was answered by the host, so it starts the
// count of closes after a password again, as a refusal does: a close before it and one after
// it are no two in a row.
func TestATurnedAwayLoginStartsTheCloseCountAgain(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) { s.password = "right" })
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "trust", true
		}
		return "right", true
	}}
	p := newTestPool(t, "", a)
	p.turnAwayPause = time.Millisecond
	loc := srv.locator()
	for i, step := range []struct {
		set  func(s *testServer)
		want string
	}{
		{func(s *testServer) { s.dropOnAnswer = true }, "The connection to 127.0.0.1 ended before SSH sign-in completed."},
		{func(s *testServer) { s.dropOnAnswer, s.accountDenied = false, "System is booting up." }, "127.0.0.1 didn't let rex log in.\n127.0.0.1 says: System is booting up."},
		{func(s *testServer) { s.dropOnAnswer, s.accountDenied = true, "" }, "The connection to 127.0.0.1 ended before SSH sign-in completed."},
	} {
		srv.set(step.set)
		_, err := p.Open(ctxFor(t, 10*time.Second), loc)
		if e := asError(t, err); e.Code != "closed_before_login" || e.Sentence != step.want {
			t.Fatalf("login %d: %+v, want closed_before_login %q", i+1, e, step.want)
		}
	}
}

// Review of #302: a password the host refused earlier in a login that it then turned away is
// still a refusal Kotlin must hear of, when no Retry question told it (the question between
// was a prompt's): it is carried, whatever the login's last word was. Without a refusal
// nothing is.
func TestAnUntoldRefusalInATurnedAwayLoginIsCarried(t *testing.T) {
	target, err := ParseTarget("ssh://rex@127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	for _, untold := range []bool{true, false} {
		p := newTestPool(t, "", trusting())
		tg := p.target(target)
		l := &login{p: p, tg: tg, t: target, ctx: context.Background(), conn: &sniffConn{}, watch: &watchdog{},
			turnedAway: true, inRound: true, refused: untold, pwRefused: untold, untold: untold, pastKeys: true}
		if e := p.ended(tg, l, errTurnedAway); e.Code != "login_refused" {
			t.Fatalf("untold %v: %+v, want login_refused", untold, e)
		}
		tg.mu.Lock()
		carried := tg.retryNext
		tg.mu.Unlock()
		if carried != untold {
			t.Errorf("untold %v: the refusal carried: %v", untold, carried)
		}
	}
}

// #311: PAM's auth step can turn a login away before it asks anything, and say why (a locked
// account, pam_nologin in the auth stack). With keyboard-interactive, OpenSSH then sends a
// first round without questions whose instruction is PAM's words, and a refusal (measured
// with OpenSSH 9.6p1). That is login_refused at once, the host's first line under the
// sentence, as with its words after the password (#302): no password was asked, so none is
// refused, and nothing is carried. No other method is tried after it: the password method
// would send a saved password to an account that may be locked, and have Kotlin forget it
// once PAM refused it. Before, the login went on, and a host with no other method was said to
// ask for a key.
func TestWordsBeforeAnyQuestionTurnTheLoginAway(t *testing.T) {
	const locked = "The account is locked due to 3 failed logins.\n(10 minutes left to unlock)\n"
	sentence := "127.0.0.1 didn't let rex log in.\n127.0.0.1 says: The account is locked due to 3 failed logins."
	for _, tc := range []struct {
		name                           string
		closes, unrefused, passwordToo bool
		maxAuthTries, keys             int
	}{
		{"the refusal", false, false, false, 0, 0},
		{"the refusal, then a close at once", true, false, false, 0, 0},
		{"a close without the refusal", false, true, false, 0, 0},
		{"the hang-up of MaxAuthTries in place of the refusal", false, false, false, 1, 0},
		{"the password method offered too", false, false, true, 0, 0},
		{"after two keys the host does not know", false, false, false, 0, 2},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		a := &asker{fn: func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			return "right", true
		}}
		p := newTestPool(t, "", a) // with the pause of production: these logins do not wait
		if tc.keys > 0 {
			pems, _, _ := newKeys(t, tc.keys)
			if err := p.SetIdentities(pems); err != nil {
				t.Fatal(err)
			}
			_, _, other := newKeys(t, 1)
			srv.set(func(s *testServer) { s.keys = other })
		}
		srv.set(func(s *testServer) {
			s.kbd, s.kbdWordsFirst = []kbdQuestion{{text: "Password:", want: "right"}}, locked
			s.closeOnRefuse, s.kbdDeniedClose, s.maxAuthTries = tc.closes, tc.unrefused, tc.maxAuthTries
			if tc.passwordToo {
				s.password = "right"
			}
		})
		loc := srv.locator()
		for i := range 3 {
			_, err := p.Open(ctxFor(t, 10*time.Second), loc)
			if e := asError(t, err); e.Code != "login_refused" || e.Sentence != sentence || e.Fix != "" {
				t.Fatalf("%s, login %d: %+v\nwant login_refused %q and no fix", tc.name, i+1, e, sentence)
			}
		}
		if n := srv.KbdTries(); n != 3 {
			t.Errorf("%s: %d keyboard-interactive attempts in three logins, want one each", tc.name, n)
		}
		if slices.Contains(srv.Auths(), "password") {
			t.Errorf("%s: a password went out to a host that turned the login away", tc.name)
		}
		if got := kindsOf(a.questions()); len(got) != 0 {
			t.Errorf("%s: questions %v, want none: the host asked nothing", tc.name, got)
		}
		// The account is unlocked: the password is asked for plainly, and logs in.
		srv.set(func(s *testServer) { s.kbdWordsFirst, s.maxAuthTries = "", 0 })
		roundTrip(t, mustOpen(t, p, loc), srv, tc.name+": once unlocked")
		if got := kindsOf(a.questions()); !slices.Equal(got, []string{"password"}) {
			t.Errorf("%s: questions %v, want one plain password question", tc.name, got)
		}
	}
}

// #311: a round without questions that the login goes on after is a notice, not the host's
// last word: the questions that follow are answered as ever, a wrong password among them is
// a refused password, and nothing turns the login away.
func TestANoticeRoundTurnsNoLoginAway(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	srv.set(func(s *testServer) {
		s.kbd, s.kbdNotice = []kbdQuestion{{text: "Password:", want: "right"}}, "Authorized use only."
	})
	var asked atomic.Int32
	a := &asker{fn: func(q Question) (string, bool) {
		if q.Kind == "hostkey" {
			return "trust", true
		}
		if asked.Add(1) == 1 {
			return "wrong", true
		}
		return "right", true
	}}
	p := newTestPool(t, "", a)
	roundTrip(t, mustOpen(t, p, srv.locator()), srv, "after the notice")
	if got := kindsOf(a.questions()); !slices.Equal(got, []string{"password", "password!"}) {
		t.Errorf("questions %v, want the password asked, and asked again once refused", got)
	}
}

// Review of #312: a keyboard-interactive attempt that asks nothing, with no words, is tried
// once and leads on to the password method, as before #311: only words turn a login away.
func TestKeyboardInteractiveThatAsksNothingLeadsToThePassword(t *testing.T) {
	for _, nothing := range []int{1, 2} {
		srv := newTestServer(t)
		srv.install("rex")
		srv.set(func(s *testServer) {
			s.kbd, s.kbdNothing, s.password = []kbdQuestion{{text: "Password:", want: "right"}}, nothing, "right"
		})
		a := &asker{fn: func(q Question) (string, bool) {
			if q.Kind == "hostkey" {
				return "trust", true
			}
			return "right", true
		}}
		p := newTestPool(t, "", a)
		roundTrip(t, mustOpen(t, p, srv.locator()), srv, "the password after an empty keyboard-interactive")
		if n := srv.KbdTries(); n != 1 {
			t.Errorf("kbdNothing %d: %d keyboard-interactive attempts, want one", nothing, n)
		}
		if got := kindsOf(a.questions()); !slices.Equal(got, []string{"password"}) {
			t.Errorf("kbdNothing %d: questions %v, want one plain password question", nothing, got)
		}
	}
}

// Review of #312: a login turned away before any answer (by a key, or by PAM's words before
// any question) answered nothing: what a login leaves about answers, the stall mark and the
// closes after a password, stays. One turned away in reply to an answer clears both.
func TestATurnAwayBeforeAnyAnswerLeavesTheAnswerMarks(t *testing.T) {
	target, err := ParseTarget("ssh://rex@127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	pems, _, _ := newKeys(t, 1)
	for _, tc := range []struct {
		name    string
		set     func(l *login)
		replied bool
	}{
		{"a key, then the banner and a close", func(l *login) { l.keysTrying, l.keyBanner = true, true }, false},
		{"words before any question, then a close", func(l *login) { l.wordsRound = true }, false},
		{"the password, then the banner and a close", func(l *login) {
			l.answered, l.pwAnswered, l.lastPw, l.answeredLive, l.bannerSince = true, true, true, true, true
		}, true},
		{"the password, then words, then a close", func(l *login) {
			l.answered, l.pwAnswered, l.answeredLive, l.wordsRound = true, true, true, true
		}, true},
	} {
		p := newTestPool(t, "", trusting())
		if err := p.SetIdentities(pems); err != nil {
			t.Fatal(err)
		}
		tg := p.target(target)
		tg.afterStall, tg.cutAnswers = true, 1
		client, host := net.Pipe()
		conn := &sniffConn{Conn: client}
		l := &login{p: p, tg: tg, t: target, ctx: context.Background(), conn: conn, watch: &watchdog{conn: client}, afterStall: true}
		tc.set(l)
		conn.read += 100 // what the host sent since
		conn.fail()
		if _, err := l.nextAuth(&ssh.ClientAuthContext{AllowedMethods: []string{"publickey", "keyboard-interactive", "password"}}); !errors.Is(err, errLoginClosed) {
			t.Fatalf("%s: nextAuth: %v, want errLoginClosed", tc.name, err)
		}
		if !l.turnedAway || l.replied != tc.replied {
			t.Fatalf("%s: turnedAway %v, replied %v; want a turn-away, replied %v", tc.name, l.turnedAway, l.replied, tc.replied)
		}
		p.ended(tg, l, errLoginClosed)
		tg.mu.Lock()
		stall, cuts := tg.afterStall, tg.cutAnswers
		tg.mu.Unlock()
		if want := !tc.replied; stall != want || (cuts == 1) != want {
			t.Errorf("%s: afterStall %v, cutAnswers %d; want them kept: %v", tc.name, stall, cuts, want)
		}
		l.watch.finish()
		client.Close()
		host.Close()
	}
}

// Review of #312: a try after a key login the host turned away waits out the pause too.
func TestAKeyTurnAwayWaitsBeforeTheNextTry(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	pems, _, pubs := newKeys(t, 1)
	srv.set(func(s *testServer) { s.keys, s.accountDenied = []ssh.PublicKey{pubs[0]}, "System is booting up." })
	p := newTestPool(t, "", trusting())
	if err := p.SetIdentities(pems); err != nil {
		t.Fatal(err)
	}
	const pause = time.Second
	p.turnAwayPause = pause
	loc := srv.locator()
	if _, err := p.Open(ctxFor(t, 10*time.Second), loc); asError(t, err).Code != "closed_before_login" {
		t.Fatalf("%v, want closed_before_login", err)
	}
	start := time.Now()
	if _, err := p.Open(ctxFor(t, 10*time.Second), loc); asError(t, err).Code != "closed_before_login" {
		t.Fatalf("%v, want closed_before_login", err)
	}
	if took := time.Since(start); took < pause*9/10 {
		t.Errorf("the second key login ended %v after the first, want it to wait %v", took, pause)
	}
}

// #312: sshd's PAM account step runs after a key login too. While the host boots it takes the
// key and its signature, sends PAM's words as a banner and closes, with no success and no
// failure (measured with OpenSSH 9.6p1). That is the turn-away of #302, met by a key: the same
// sentence with the host's first line, the first two closed_before_login, which the core
// retries, the third login_refused. It is no close of the key stage: no key_refused, no
// authorized_keys command (the host has the key), and no question.
//
// Unlike a password's, these logins are kept in no memory: sshd logs them as "Failed
// publickey", which fail2ban's sshd filter does not count (measured with fail2ban 1.0.2: 0 a
// login, where a password login turned away counts 1). So the stop starts the count again
// (the user's Reconnect is three tries), and so does a login that finishes. Two turned away
// long ago are no part of a row here either.
func TestAKeyLoginTheHostTurnsAway(t *testing.T) {
	const nologin = "System is booting up. Unprivileged users are not permitted to log in yet.\n\n"
	sentence := "127.0.0.1 didn't let rex log in.\n127.0.0.1 says: System is booting up. Unprivileged users are not permitted to log in yet."
	for _, tc := range []struct {
		name string
		keys int // the keys the phone offers; the host has the last
	}{
		{"the phone key", 1},
		{"an imported key, after two the host does not know", 3},
	} {
		srv := newTestServer(t)
		srv.install("rex")
		pems, _, pubs := newKeys(t, tc.keys)
		srv.set(func(s *testServer) { s.keys, s.accountDenied = []ssh.PublicKey{pubs[tc.keys-1]}, nologin })
		a := trusting()
		p := newTestPool(t, "", a)
		if err := p.SetIdentities(pems); err != nil {
			t.Fatal(err)
		}
		p.turnAwayPause = time.Millisecond
		var later atomic.Int64
		start := time.Now()
		p.now = func() time.Time { return start.Add(time.Duration(later.Load())) }
		loc := srv.locator()
		expect := func(step string, codes ...string) {
			t.Helper()
			for i, want := range codes {
				_, err := p.Open(ctxFor(t, 10*time.Second), loc)
				if e := asError(t, err); e.Code != want || e.Sentence != sentence || e.Fix != "" {
					t.Fatalf("%s, %s, login %d: %+v\nwant %s %q and no fix", tc.name, step, i+1, e, want, sentence)
				}
			}
		}
		expect("three, then a Reconnect's three", "closed_before_login", "closed_before_login", "login_refused",
			"closed_before_login", "closed_before_login", "login_refused")
		expect("two more", "closed_before_login", "closed_before_login")
		// The host is up: the key logs in, and the next boot has three tries again.
		srv.set(func(s *testServer) { s.accountDenied = "" })
		roundTrip(t, mustOpen(t, p, loc), srv, tc.name+": after the boot")
		srv.closeConns()
		waitFor(t, 5*time.Second, "the pool to see its connection end", func() bool { return !p.connected(loc) })
		srv.set(func(s *testServer) { s.accountDenied = nologin })
		expect("after a login", "closed_before_login", "closed_before_login")
		later.Add(int64(6 * time.Hour))
		expect("hours after two", "closed_before_login", "closed_before_login", "login_refused")
		srv.set(func(s *testServer) { s.accountDenied = "" })
		roundTrip(t, mustOpen(t, p, loc), srv, tc.name+": at last")
		if n := a.count("password") + a.count("prompt"); n != 0 {
			t.Errorf("%s: %d questions besides the host key", tc.name, n)
		}
	}
}

// #312: a key login the host turned away got past the keys: the host took one. It neither
// counts as a close of the key stage nor leaves that count standing, so two closes before it
// and two after it are no three in a row.
func TestAKeyTurnAwayIsNoCloseOfTheKeyStage(t *testing.T) {
	srv := newTestServer(t)
	srv.install("rex")
	pems, _, pubs := newKeys(t, 1)
	srv.set(func(s *testServer) { s.keys = []ssh.PublicKey{pubs[0]} })
	p := newTestPool(t, "", trusting())
	if err := p.SetIdentities(pems); err != nil {
		t.Fatal(err)
	}
	p.turnAwayPause = time.Millisecond
	loc := srv.locator()
	closed := "The connection to 127.0.0.1 ended before SSH sign-in completed."
	turned := "127.0.0.1 didn't let rex log in.\n127.0.0.1 says: System is booting up."
	for i, step := range []struct {
		drop           bool
		denied         string
		code, sentence string
	}{
		{true, "", "closed_before_login", closed},
		{true, "", "closed_before_login", closed},
		{false, "System is booting up.", "closed_before_login", turned},
		{true, "", "closed_before_login", closed},
		{true, "", "closed_before_login", closed},
		{true, "", "key_refused", "127.0.0.1 kept closing the connection while Velo offered this phone's key for rex. " +
			"On that host, append the public key to rex's ~/.ssh/authorized_keys file."},
	} {
		srv.set(func(s *testServer) { s.dropOnKey, s.accountDenied = step.drop, step.denied })
		_, err := p.Open(ctxFor(t, 10*time.Second), loc)
		if e := asError(t, err); e.Code != step.code || e.Sentence != step.sentence {
			t.Fatalf("login %d: %+v\nwant %s %q", i+1, e, step.code, step.sentence)
		}
	}
}

// #312: a banner while the keys go out is the host's word on the login only when the
// connection then closes. A host that refuses the keys after it (and stays) goes on to the
// password as before, and a later close is judged by what was answered then.
func TestABannerWithRefusedKeysIsNoTurnAway(t *testing.T) {
	target, err := ParseTarget("ssh://rex@127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	p := newTestPool(t, "", trusting())
	pems, _, _ := newKeys(t, 1)
	if err := p.SetIdentities(pems); err != nil {
		t.Fatal(err)
	}
	p.mu.Lock()
	ids := slices.Clone(p.identities)
	p.mu.Unlock()
	client, host := net.Pipe()
	defer client.Close()
	defer host.Close()
	conn := &sniffConn{Conn: client}
	l := &login{p: p, tg: p.target(target), t: target, ctx: context.Background(), ids: ids, conn: conn, watch: &watchdog{conn: client}}
	defer l.watch.finish()
	methods := &ssh.ClientAuthContext{AllowedMethods: []string{"publickey", "password"}, TriedMethods: []string{"none"}}
	if _, err := l.nextAuth(methods); err != nil || !l.keysTrying {
		t.Fatalf("nextAuth: %v, keysTrying %v; want the keys", err, l.keysTrying)
	}
	if err := l.banner("Authorized use only."); err != nil {
		t.Fatal(err)
	}
	methods.TriedMethods = []string{"none", "publickey"}
	if _, err := l.nextAuth(methods); err != nil || l.turnedAway || !l.pastKeys {
		t.Fatalf("after the keys were refused: %v, turnedAway %v, pastKeys %v; want the next method", err, l.turnedAway, l.pastKeys)
	}
	conn.fail() // the connection ends before any answer
	if _, err := l.nextAuth(methods); !errors.Is(err, errLoginClosed) || l.turnedAway {
		t.Fatalf("after the close: %v, turnedAway %v; want a dropped login", err, l.turnedAway)
	}
	if e := l.failure(); e.Code != "closed_before_login" || strings.Contains(e.Sentence, "says") {
		t.Errorf("%+v, want the plain closed_before_login", e)
	}
}

// #302: the host's words are text from the host: the sentence shows only its first line with
// visible text, cleaned as a key's comment is (no control, format or private characters, no
// line or paragraph separators, no invisible fillers, no pile of marks), its spaces folded,
// cut to maxHostWords characters. Text in any language stays.
func TestHostWordsAreOneCleanLine(t *testing.T) {
	long := strings.Repeat("booting ", 40)
	for _, tc := range []struct{ name, in, want string }{
		{"the first line", "System is booting up.\nPlease come back later.\n", "System is booting up."},
		{"blank lines first", "\r\n \t\n  Your account has expired.  \r\n", "Your account has expired."},
		{"control characters", "a\x00b\x07c\x7fd\u009be", "abcde"},
		{"terminal colours", "\x1b[1;31mDown for maintenance\x1b[0m.", "Down for maintenance."},
		{"other escape sequences", "\x1b(BSystem down\x1b[?25h\x1b=\x1b]0;a title\x07 now\x1b]8;;http://x\x1b\\.", "System down now."},
		{"an escape sequence that never ends", "Down\x1b]0;title", "Down"},
		{"a string sequence cut short by another escape", "\x1b]0;title\x1b[31m한글\x1b]0;t\x1b🚧 tail", "한글🚧 tail"},
		{"a lone escape at the end", "Down\x1b", "Down"},
		{"tabs and runs of spaces", "a\t\tb  \u00a0 c", "a b c"},
		{"format characters", "abc\u202edef\u200b\u2066g\ufeff", "abcdefg"},
		{"a line separator ends the line", "one\u2028two", "one"},
		{"so does a next-line character", "one\u0085two", "one"},
		{"and a form feed", "one\ftwo\vthree", "one"},
		{"private use and unassigned", "a\ue000b\U000F0000c\u0378d", "abcd"},
		{"bytes that are no UTF-8", "a\xffb\xc3", "ab"},
		{"invisible fillers", "a\u3164b\u115fc", "abc"},
		{"marks pile up to three", "e" + strings.Repeat("\u0301", 8) + "x", "e\u0301\u0301\u0301x"},
		{"a mark needs a letter to sit on", "\u0301a \u0301b\u034f \u0301", "a b"},
		{"a spacing mark too", "\u0903\u0915\u093e\u0902 \u093e", "\u0915\u093e\u0902"},
		{"a blank mark is none of the three", "a\u034f\u034f\u034f\u0301", "a\u0301"},
		{"a spacing mark begins three more", "\u0915\u0301\u0301\u0301\u093e\u0301", "\u0915\u0301\u0301\u0301\u093e\u0301"},
		{"nothing but marks", "\u0301\u0302\u034f\nSecond line", "Second line"},
		{"a blank that is a symbol", "a\u2800b", "ab"},
		{"Korean", "시스템이 부팅 중입니다. 잠시 뒤에 다시 접속하세요.", "시스템이 부팅 중입니다. 잠시 뒤에 다시 접속하세요."},
		{"Thai, two marks on a letter", "ระบบกำลังเริ่มต้น ที่นี่", "ระบบกำลังเริ่มต้น ที่นี่"},
		{"an emoji with its variation selector", "⚠\ufe0f Maintenance", "⚠\ufe0f Maintenance"},
		{"nothing visible", "\x1b[0m\u200b \n\t\u3164\n\u2800\u0301\n", ""},
		{"too long: cut at a space", long, strings.TrimSpace(long[:len("booting ")*19]) + "…"},
		{"too long without a space", strings.Repeat("x", 300), strings.Repeat("x", maxHostWords-1) + "…"},
	} {
		got := hostWords(tc.in)
		if got != tc.want {
			t.Errorf("%s: hostWords(%q) = %q, want %q", tc.name, tc.in, got, tc.want)
		}
		if n := len([]rune(got)); n > maxHostWords {
			t.Errorf("%s: %d characters, more than %d", tc.name, n, maxHostWords)
		}
	}
}

// #302: without words the host gave, or none left once cleaned, the sentence has no second line.
func TestLoginRefusedSentence(t *testing.T) {
	target, err := ParseTarget("ssh://rex@Studio.example")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ said, want string }{
		{"", "studio.example didn't let rex log in."},
		{"Your account has expired.", "studio.example didn't let rex log in.\nstudio.example says: Your account has expired."},
	} {
		if e := errLoginRefused(target, tc.said); e.Code != "login_refused" || e.Sentence != tc.want || e.Fix != "" {
			t.Errorf("errLoginRefused(%q) = %+v, want login_refused %q", tc.said, e, tc.want)
		}
	}
}

// Second review: a disconnect that says "no tries left" is the host's refusal of an answer
// only when it came after that answer. One the host sent before (right after its refusal,
// while the Retry question waited) reached x/crypto first, and the answer never got to the
// host: nothing new was refused.
func TestAHangUpBeforeTheAnswerRefusesNothingNew(t *testing.T) {
	target, err := ParseTarget("ssh://rex@127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name         string
		read, mark   int64
		untold, want bool
	}{
		{"the hang-up came after the answer", 900, 800, false, true},
		{"the hang-up was there before the answer", 800, 800, false, false},
		{"an earlier refusal is still untold", 800, 800, true, true},
	} {
		l := &login{t: target, ctx: context.Background(), conn: &sniffConn{read: tc.read}, watch: &watchdog{},
			answered: true, pwAnswered: true, answeredAt: tc.mark, hungUp: true, noTriesLeft: true, untold: tc.untold}
		if got := l.refusalUntold(); got != tc.want {
			t.Errorf("%s: refusalUntold = %v, want %v", tc.name, got, tc.want)
		}
	}
}
