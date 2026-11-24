package rexssh

// An in-process SSH host for the tests: x/crypto/ssh on 127.0.0.1:0, behaving like an
// OpenSSH host with Rex installed. It records every exec command, answers the desktop's
// probe with a status JSON whose endpoint is a Unix socket it serves (an echo server),
// accepts or refuses direct-streamlocal@openssh.com (refusing as OpenSSH does: "open
// failed", the answer for a missing socket too), can move its socket, bridges `REX -S SOCKET __internal
// dial-stdio` to that socket, logs in by password or public key (recording the keys a
// login offers, in order; MaxAuthTries is x/crypto's default 6, as OpenSSH's, unless set:
// then it hangs up with a disconnect once a login has that many failures, as sshd does),
// can drop the TCP connection when a key is offered (a network that goes away during the
// login) or when an answer arrives (before its reply), hold an answer's reply (a slow PAM),
// close the TCP connection right after it refuses an answer (its exit, no disconnect), send
// an empty keyboard-interactive round after the answers (OpenSSH's PAM on PAM_SUCCESS) and
// reset the connection in place of a keyboard-interactive login's success,
// and can stop answering
// global requests (keepalives) as a host behind a dead network does, or channel opens as a
// wedged host does, keep a probe running (a server that takes long to start), or offer
// only ciphers or host keys the client refuses. It can allow a number of sessions at a time
// (OpenSSH's MaxSessions), freeing one a while after it has processed the client's close, as
// sshd does in its next turn, or refuse every session. Any other command runs in /bin/sh with
// HOME in the test's temporary directory, never the real one.

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

type testServer struct {
	t       *testing.T
	port    int
	hostKey ssh.Signer
	home    string // HOME of the commands it runs in a shell

	mu            sync.Mutex
	installed     map[string]bool // probe candidate → answers with the status JSON
	hung          map[string]bool // probe candidate → runs until the client gives up
	noise         bool            // a banner line (with a brace) before the JSON, a warning on stderr
	refuseForward bool
	refuseExec    bool
	silent        bool                // stop answering global requests
	ignoreOpens   bool                // never answer a channel open (neither accept nor reject)
	maxSessions   int                 // sessions open at a time; 0: no limit
	sessionLag    time.Duration       // how long after processing a session's close it frees the session
	refuseSession ssh.RejectionReason // refuse every session with this reason; 0: none
	sessions      int                 // sessions open now (until freed)
	refusedOpens  int                 // session opens refused
	ciphers       []string            // the only ciphers it offers; nil: x/crypto's defaults
	hostKeyOnly   ssh.Signer          // the host key it offers instead of hostKey
	keepalives    int                 // keepalive requests answered
	password      string              // "" turns password login off
	maxAuthTries  int                 // failed login attempts before it hangs up; 0: x/crypto's 6
	dropOnKey     bool                // close the TCP connection, without a disconnect, when a key is offered
	onKey         func()              // runs when a key is offered, before the answer
	dropOnAnswer  bool                // reset the TCP connection, without a disconnect, when a password or prompt answer arrives
	onAnswer      func()              // runs when a password or prompt answer arrives, before the reply
	closeOnRefuse bool                // close the TCP connection right after sending a refusal of an answer
	closeWhen     <-chan struct{}     // with closeOnRefuse: the connection closes when this is closed, not at once
	kbdRounds     bool                // each keyboard-interactive question in a round of its own, as PAM asks a password and then a code
	kbdAfterPass  bool                // the right password is a partial success: keyboard-interactive must follow it
	passAfterKbd  bool                // the right keyboard-interactive answers are a partial success: the password must follow
	accountDenied string              // after the right password, or a key it accepts: this banner, then the connection closes (PAM's account step, as pam_nologin while the host boots)
	kbdEmptyRound bool                // after the keyboard-interactive answers, a round with no prompts, as OpenSSH's PAM sends on PAM_SUCCESS
	kbdResetLogin bool                // reset the TCP connection where a keyboard-interactive login would succeed (the link dies before SUCCESS)
	keys          []ssh.PublicKey     // public keys it accepts
	kbd           []kbdQuestion       // keyboard-interactive questions; none turns it off
	commands      []string            // every exec command, in order
	auths         []string            // login attempts in order: a key's SHA256 fingerprint, or "password"
	handshakes    int
	forwards      int // direct-streamlocal channel opens, refused or not
	closed        int // connections that ended after the handshake
	socketBytes   bytes.Buffer
	conns         []ssh.Conn
	raw           []net.Conn // every TCP connection accepted, those still logging in too
	sockPath      string     // the Rex server socket it serves now
	sockLn        net.Listener
	sockDir       string
	moves         int

	// kbdDenied: after the right keyboard-interactive answers, a round with no prompts whose
	// instruction is this text, then a refusal: OpenSSH's PAM when its account step turns the
	// login away (pam_nologin, an expired account; measured with OpenSSH 9.6p1, #302). The
	// next keyboard-interactive attempt on that connection is refused with no round, and the
	// one after closes it. kbdDeniedClose: the connection closes after that round instead,
	// with no refusal.
	kbdDenied      string
	kbdDeniedClose bool
	kbdTries       int // keyboard-interactive attempts
	// kbdWordsFirst: every keyboard-interactive attempt is a round with no prompts whose
	// instruction is this text, then a refusal, before anything is asked: OpenSSH's PAM when
	// its auth step turns the login away (a locked account; measured with OpenSSH 9.6p1, #311).
	kbdWordsFirst string
	// kbdNotice: a round with no prompts whose instruction is this text comes before the
	// questions, and the login goes on (a notice some hosts show).
	kbdNotice string
	// kbdNothing: every keyboard-interactive attempt asks nothing and is refused: 1 at once,
	// 2 after a round with no prompts and no words.
	kbdNothing int
}

// kbdQuestion is one keyboard-interactive question and the answer the server wants.
type kbdQuestion struct {
	text, want string
	echo       bool
}

func newTestServer(t *testing.T) *testServer {
	t.Helper()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	// A short directory: a Unix socket path must fit in 108 bytes.
	dir, err := os.MkdirTemp("", "velo-ssh")
	if err != nil {
		t.Fatal(err)
	}
	s := &testServer{
		t:         t,
		port:      ln.Addr().(*net.TCPAddr).Port,
		hostKey:   signer,
		home:      t.TempDir(),
		installed: map[string]bool{},
		hung:      map[string]bool{},
		sockDir:   dir,
	}
	s.moveSocket()
	go s.serve(ln)
	t.Cleanup(func() {
		ln.Close()
		s.mu.Lock()
		conns := s.conns
		s.sockLn.Close()
		s.mu.Unlock()
		for _, c := range conns {
			c.Close()
		}
		os.RemoveAll(dir)
	})
	return s
}

// moveSocket serves the Rex server socket at a new path (the probe answers it from now
// on); the old path is gone, as when the server restarted elsewhere.
func (s *testServer) moveSocket() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sockLn != nil {
		s.sockLn.Close() // removes the old socket file
	}
	s.moves++
	path := filepath.Join(s.sockDir, fmt.Sprintf("server%d.sock", s.moves))
	ul, err := net.Listen("unix", path)
	if err != nil {
		s.t.Fatal(err)
	}
	s.sockLn, s.sockPath = ul, path
	go s.serveSocket(ul)
}

func (s *testServer) socket() (p string) { s.get(func(s *testServer) { p = s.sockPath }); return }

func (s *testServer) locator() string { return fmt.Sprintf("ssh://rex@127.0.0.1:%d", s.port) }

func (s *testServer) install(candidate string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.installed[candidate] = true
}

func (s *testServer) set(f func(s *testServer)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f(s)
}

func (s *testServer) get(f func(s *testServer)) { s.set(f) }

func (s *testServer) Commands() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.commands...)
}

// Auths lists the login attempts in order: each key offered (its SHA256 fingerprint, once
// per connection, as x/crypto's server asks its callback once per key), and "password".
func (s *testServer) Auths() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.auths...)
}

// closeConns ends every connection that got past the handshake, as a host whose sshd
// restarted: the next stream logs in again.
func (s *testServer) closeConns() {
	s.mu.Lock()
	conns := s.conns
	s.conns = nil
	s.mu.Unlock()
	for _, c := range conns {
		c.Close()
	}
}

func (s *testServer) Handshakes() (n int) { s.get(func(s *testServer) { n = s.handshakes }); return }
func (s *testServer) KbdTries() (n int)   { s.get(func(s *testServer) { n = s.kbdTries }); return }
func (s *testServer) Forwards() (n int)   { s.get(func(s *testServer) { n = s.forwards }); return }
func (s *testServer) Closed() (n int)     { s.get(func(s *testServer) { n = s.closed }); return }
func (s *testServer) Keepalives() (n int) { s.get(func(s *testServer) { n = s.keepalives }); return }
func (s *testServer) RefusedSessions() (n int) {
	s.get(func(s *testServer) { n = s.refusedOpens })
	return
}
func (s *testServer) SocketBytes() (b string) {
	s.get(func(s *testServer) { b = s.socketBytes.String() })
	return
}

// serveSocket is the remote Rex server: it echoes what it reads and remembers it.
func (s *testServer) serveSocket(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			buf := make([]byte, 4096)
			for {
				n, err := c.Read(buf)
				if n > 0 {
					s.mu.Lock()
					s.socketBytes.Write(buf[:n])
					s.mu.Unlock()
					if _, werr := c.Write(buf[:n]); werr != nil {
						return
					}
				}
				if err != nil {
					return
				}
			}
		}()
	}
}

func (s *testServer) serve(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go s.handle(c)
	}
}

// closeLogins ends every TCP connection of the server, those still logging in too, without
// an SSH disconnect: the host went away.
func (s *testServer) closeLogins() {
	s.mu.Lock()
	raw := s.raw
	s.raw = nil
	s.mu.Unlock()
	for _, c := range raw {
		c.Close()
	}
}

func (s *testServer) handle(nc net.Conn) {
	s.mu.Lock()
	s.raw = append(s.raw, nc)
	password, keys, kbd := s.password, append([]ssh.PublicKey(nil), s.keys...), s.kbd
	ciphers, hostKey := s.ciphers, s.hostKey
	if s.hostKeyOnly != nil {
		hostKey = s.hostKeyOnly
	}
	maxAuthTries, dropOnKey, onKey := s.maxAuthTries, s.dropOnKey, s.onKey
	onAnswer, closeOnRefuse, closeWhen := s.onAnswer, s.closeOnRefuse, s.closeWhen
	kbdRounds, kbdAfterPass, passAfterKbd := s.kbdRounds, s.kbdAfterPass, s.passAfterKbd
	accountDenied := s.accountDenied
	kbdEmptyRound, kbdResetLogin := s.kbdEmptyRound, s.kbdResetLogin
	kbdDenied, kbdDeniedClose, kbdWordsFirst, kbdNotice := s.kbdDenied, s.kbdDeniedClose, s.kbdWordsFirst, s.kbdNotice
	kbdNothing := s.kbdNothing
	s.mu.Unlock()
	// The connection the SSH server uses; with closeOnRefuse, refused arms it to close right
	// after the next packet it writes, the refusal.
	var conn net.Conn = nc
	refused := func() {}
	if closeOnRefuse || accountDenied != "" {
		c := &closeAfterWrite{Conn: nc, when: closeWhen}
		conn, refused = c, func() { c.armed.Store(true) }
	}
	// answered runs when a password or the prompts' answers arrive; false: the connection
	// was reset before the reply. onAnswer may set dropOnAnswer for this very answer.
	answered := func() bool {
		if onAnswer != nil {
			onAnswer()
		}
		s.mu.Lock()
		drop := s.dropOnAnswer
		s.mu.Unlock()
		if drop {
			if tc, ok := nc.(*net.TCPConn); ok {
				tc.SetLinger(0) // a reset, as a network that died
			}
			nc.Close()
			return false
		}
		return true
	}
	cfg := &ssh.ServerConfig{Config: ssh.Config{Ciphers: ciphers}, MaxAuthTries: maxAuthTries}
	var kbdCallback func(ssh.ConnMetadata, ssh.KeyboardInteractiveChallenge) (*ssh.Permissions, error)
	var passwordCallback func(ssh.ConnMetadata, []byte) (*ssh.Permissions, error)
	if password != "" {
		passwordCallback = func(_ ssh.ConnMetadata, pw []byte) (*ssh.Permissions, error) {
			s.mu.Lock()
			s.auths = append(s.auths, "password")
			s.mu.Unlock()
			if !answered() {
				return nil, errors.New("dropped")
			}
			if string(pw) == password {
				if accountDenied != "" { // sshd sends PAM's message as a banner and exits: no success, no failure
					refused() // the connection closes right after the next write, the banner
					return nil, &ssh.BannerError{Err: errors.New("account denied"), Message: accountDenied}
				}
				if kbdAfterPass { // sshd's AuthenticationMethods password,keyboard-interactive
					return nil, &ssh.PartialSuccessError{Next: ssh.ServerAuthCallbacks{KeyboardInteractiveCallback: kbdCallback}}
				}
				return nil, nil
			}
			refused()
			return nil, errors.New("wrong password")
		}
		if !passAfterKbd {
			cfg.PasswordCallback = passwordCallback
		}
	}
	if len(keys) > 0 {
		cfg.PublicKeyCallback = func(_ ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			s.mu.Lock()
			s.auths = append(s.auths, ssh.FingerprintSHA256(key))
			s.mu.Unlock()
			if onKey != nil {
				onKey()
			}
			if dropOnKey {
				nc.Close()
				return nil, errors.New("dropped")
			}
			for _, k := range keys {
				if bytes.Equal(k.Marshal(), key.Marshal()) {
					// sshd takes the key and its signature first (measured, #312); here the banner
					// comes on the key's query. The client sees the same: a banner while its keys go
					// out, then the close.
					if accountDenied != "" {
						refused() // the connection closes right after the next write, the banner
						return nil, &ssh.BannerError{Err: errors.New("account denied"), Message: accountDenied}
					}
					return nil, nil
				}
			}
			return nil, errors.New("unknown key")
		}
	}
	if len(kbd) > 0 {
		deniedTries := 0 // the attempts on this connection after kbdDenied turned it away
		kbdCallback = func(_ ssh.ConnMetadata, ask ssh.KeyboardInteractiveChallenge) (*ssh.Permissions, error) {
			s.mu.Lock()
			s.kbdTries++
			s.mu.Unlock()
			if deniedTries > 0 { // OpenSSH: refused with no round, then its monitor ends the connection
				if deniedTries++; deniedTries > 2 {
					nc.Close()
				}
				return nil, errors.New("account denied")
			}
			if kbdWordsFirst != "" {
				if _, err := ask("", kbdWordsFirst, nil, nil); err != nil {
					return nil, err
				}
				if kbdDeniedClose {
					nc.Close()
					return nil, errors.New("dropped")
				}
				refused() // with closeOnRefuse the connection closes right after the refusal
				return nil, errors.New("account locked")
			}
			if kbdNotice != "" {
				if _, err := ask("", kbdNotice, nil, nil); err != nil {
					return nil, err
				}
			}
			if kbdNothing > 0 {
				if kbdNothing == 2 {
					if _, err := ask("", "", nil, nil); err != nil {
						return nil, err
					}
				}
				return nil, errors.New("nothing to ask")
			}
			var texts []string
			var echos []bool
			var answers []string
			for _, q := range kbd {
				texts, echos = append(texts, q.text), append(echos, q.echo)
				if kbdRounds {
					one, err := ask("", "", []string{q.text}, []bool{q.echo})
					if err != nil {
						return nil, err
					}
					answers = append(answers, one...)
				}
			}
			if !kbdRounds {
				all, err := ask("", "", texts, echos)
				if err != nil {
					return nil, err
				}
				answers = all
			}
			if !answered() {
				return nil, errors.New("dropped")
			}
			if kbdEmptyRound {
				if _, err := ask("", "", nil, nil); err != nil {
					return nil, err
				}
			}
			for i, q := range kbd {
				if i >= len(answers) || answers[i] != q.want {
					refused()
					return nil, errors.New("wrong answer")
				}
			}
			if kbdResetLogin {
				if tc, ok := nc.(*net.TCPConn); ok {
					tc.SetLinger(0)
				}
				nc.Close()
				return nil, errors.New("dropped")
			}
			if kbdDenied != "" { // PAM's words in a round of their own, then a refusal
				deniedTries = 1
				if _, err := ask("", kbdDenied, nil, nil); err != nil {
					return nil, err
				}
				if kbdDeniedClose {
					nc.Close()
					return nil, errors.New("dropped")
				}
				refused() // with closeOnRefuse the connection closes right after the refusal
				return nil, errors.New("account denied")
			}
			if accountDenied != "" { // as after the right password of the password method
				refused()
				return nil, &ssh.BannerError{Err: errors.New("account denied"), Message: accountDenied}
			}
			if passAfterKbd { // sshd's AuthenticationMethods keyboard-interactive,password
				return nil, &ssh.PartialSuccessError{Next: ssh.ServerAuthCallbacks{PasswordCallback: passwordCallback}}
			}
			return nil, nil
		}
		if !kbdAfterPass {
			cfg.KeyboardInteractiveCallback = kbdCallback
		}
	}
	if password == "" && len(keys) == 0 && len(kbd) == 0 {
		cfg.NoClientAuth = true
	}
	cfg.AddHostKey(hostKey)
	sc, chans, reqs, err := ssh.NewServerConn(conn, cfg)
	if err != nil {
		nc.Close()
		return
	}
	s.mu.Lock()
	s.handshakes++
	s.conns = append(s.conns, sc)
	s.mu.Unlock()
	go func() {
		sc.Wait()
		s.mu.Lock()
		s.closed++
		s.mu.Unlock()
	}()
	go func() {
		for req := range reqs {
			s.mu.Lock()
			silent := s.silent
			s.mu.Unlock()
			if silent {
				continue // never answered, as behind a network that dropped
			}
			if req.Type == "keepalive@openssh.com" {
				s.mu.Lock()
				s.keepalives++
				s.mu.Unlock()
			}
			if req.WantReply {
				req.Reply(false, nil) // what OpenSSH answers to keepalive@openssh.com
			}
		}
	}()
	for nch := range chans {
		s.mu.Lock()
		ignore := s.ignoreOpens
		s.mu.Unlock()
		if ignore {
			continue // the opener waits for an answer that never comes
		}
		switch nch.ChannelType() {
		case "session":
			s.mu.Lock()
			reason := s.refuseSession
			if reason == 0 && s.maxSessions > 0 && s.sessions >= s.maxSessions {
				reason = ssh.ConnectionFailed // sshd's "no more sessions", as OpenSSH 9.6 answers it
			}
			if reason != 0 {
				s.refusedOpens++
			} else {
				s.sessions++
			}
			s.mu.Unlock()
			if reason != 0 {
				nch.Reject(reason, "open failed")
				continue
			}
			go s.session(nch)
		case "direct-streamlocal@openssh.com":
			go s.forward(nch)
		default:
			nch.Reject(ssh.UnknownChannelType, "unknown channel type")
		}
	}
}

func (s *testServer) forward(nch ssh.NewChannel) {
	var m struct {
		SocketPath string
		Reserved0  string
		Reserved1  uint32
	}
	if err := ssh.Unmarshal(nch.ExtraData(), &m); err != nil {
		nch.Reject(ssh.ConnectionFailed, "bad request")
		return
	}
	s.mu.Lock()
	s.forwards++
	refuse := s.refuseForward
	s.mu.Unlock()
	if refuse {
		nch.Reject(ssh.ConnectionFailed, "open failed") // OpenSSH's answer to a refused forward
		return
	}
	c, err := net.Dial("unix", m.SocketPath)
	if err != nil {
		nch.Reject(ssh.ConnectionFailed, "open failed")
		return
	}
	ch, reqs, err := nch.Accept()
	if err != nil {
		c.Close()
		return
	}
	go ssh.DiscardRequests(reqs)
	bridge(ch, c)
	ch.Close()
}

const probePrefix = `sh -c 'case "${REX_AUTOSTART-}" in ""|1|t|T|true|TRUE|True) `

var dialStdioRe = regexp.MustCompile(`^(.+) -S (.+) __internal dial-stdio$`)

// session serves one session channel. It counts as open until its requests end, that is
// until the server has processed the client's close, and sessionLag after that: sshd frees
// it in its next turn, after the packets read with the close (often the client's next open).
func (s *testServer) session(nch ssh.NewChannel) {
	defer func() {
		s.mu.Lock()
		lag := s.sessionLag
		s.mu.Unlock()
		time.AfterFunc(lag, func() {
			s.mu.Lock()
			s.sessions--
			s.mu.Unlock()
		})
	}()
	ch, reqs, err := nch.Accept()
	if err != nil {
		return
	}
	started := false
	closed := make(chan struct{}) // the client closed the channel
	defer close(closed)
	for req := range reqs {
		if req.Type != "exec" || started {
			if req.WantReply {
				req.Reply(false, nil)
			}
			continue
		}
		var m struct{ Command string }
		if err := ssh.Unmarshal(req.Payload, &m); err != nil {
			req.Reply(false, nil)
			continue
		}
		s.mu.Lock()
		s.commands = append(s.commands, m.Command)
		refuse := s.refuseExec
		s.mu.Unlock()
		if refuse {
			req.Reply(false, nil)
			continue
		}
		started = true
		req.Reply(true, nil)
		go func() {
			status := s.exec(ch, m.Command, closed)
			ch.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{status}))
			ch.Close()
		}()
	}
}

func (s *testServer) exec(ch ssh.Channel, cmd string, closed <-chan struct{}) uint32 {
	if i := strings.Index(cmd, " server start --json"); strings.HasPrefix(cmd, probePrefix) && i > len(probePrefix) {
		rex := cmd[len(probePrefix):i]
		s.mu.Lock()
		installed, hung, noise, sockPath := s.installed[rex], s.hung[rex], s.noise, s.sockPath
		s.mu.Unlock()
		if hung {
			<-closed // until the client gives up and closes the channel
			return 1
		}
		if !installed {
			fmt.Fprintf(ch.Stderr(), "sh: 1: %s: not found\n", rex)
			return 127
		}
		if noise {
			io.WriteString(ch, "Welcome to {sandbox}\n")
			io.WriteString(ch.Stderr(), "warning: the locale is not set\n")
		}
		// Pretty-printed over several lines, as rex prints it.
		fmt.Fprintf(ch, "{\n  \"endpoint\": \"unix://%s\",\n  \"listeners\": [{\"network\": \"unix\", \"address\": %q}]\n}\n",
			sockPath, sockPath)
		return 0
	}
	if m := dialStdioRe.FindStringSubmatch(cmd); m != nil {
		s.mu.Lock()
		installed := s.installed[m[1]]
		s.mu.Unlock()
		if !installed {
			return 127
		}
		c, err := net.Dial("unix", m[2])
		if err != nil {
			fmt.Fprintln(ch.Stderr(), err)
			return 1
		}
		bridge(ch, c)
		return 0
	}
	sh := exec.Command("/bin/sh", "-c", cmd)
	sh.Env = []string{"HOME=" + s.home, "PATH=/usr/bin:/bin"}
	sh.Stdin, sh.Stdout, sh.Stderr = ch, ch, ch.Stderr()
	if err := sh.Run(); err != nil {
		var exit *exec.ExitError
		if errors.As(err, &exit) {
			return uint32(exit.ExitCode())
		}
		return 255
	}
	return 0
}

// closeAfterWrite closes the TCP connection right after the next write once armed, or when
// its channel closes after that write: a host that sends USERAUTH_FAILURE and exits without
// an SSH disconnect.
type closeAfterWrite struct {
	net.Conn
	armed atomic.Bool
	when  <-chan struct{}
}

func (c *closeAfterWrite) Write(b []byte) (int, error) {
	n, err := c.Conn.Write(b)
	switch {
	case !c.armed.Load():
	case c.when != nil:
		go func() {
			select {
			case <-c.when:
			case <-time.After(10 * time.Second):
			}
			c.Conn.Close()
		}()
	default:
		c.Conn.Close()
	}
	return n, err
}

// bridge copies both ways until both sides finished.
func bridge(ch ssh.Channel, c net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		io.Copy(c, ch)
		if u, ok := c.(*net.UnixConn); ok {
			u.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		io.Copy(ch, c)
		ch.CloseWrite()
		done <- struct{}{}
	}()
	<-done
	<-done
	c.Close()
}
