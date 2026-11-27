package rexssh

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// Timings of the desktop's helper (ConnectTimeout=10, ServerAliveInterval=15,
// ServerAliveCountMax=3).
const (
	connectTimeout = 10 * time.Second // TCP connect, then the SSH banner and key exchange
	// handshakeFloor is the least time the banner and key exchange get when the TCP
	// connect used up the connect timeout: a host that answered late is still heard.
	handshakeFloor    = 5 * time.Second
	keepaliveInterval = 15 * time.Second
	keepaliveMax      = 3
	// loginStepTimeout bounds each wait for the host once the login is past a question,
	// as OpenSSH bounds its packet reads by ServerAliveInterval × ServerAliveCountMax.
	loginStepTimeout = keepaliveInterval * keepaliveMax
	// channelTimeout bounds the opening of a stream's channel (the forward, or dial-stdio's
	// exec channel and its start): a host that keeps the connection alive but never answers
	// the open would hold the dial for good. No question is asked meanwhile.
	channelTimeout = 30 * time.Second
	maxSecretTries = 3 // password and prompt attempts per login (OpenSSH's NumberOfPasswordPrompts)
	openAttempts   = 3
	// keyStageCloses is how many logins in a row the host may close during the key stage
	// before the core stops: two can be a flapping network, the third (TCP and the key
	// exchange got through each time) is the host.
	keyStageCloses = 3
	// answerCloses is how many logins the host may close after a password, without a reply to
	// it, before the core stops; a login that finishes, or a reply to an answer, starts the
	// count again (a login that ends before any answer does not: it says nothing about the
	// password). One can be the network. The second sends a saved password that may be stale
	// once more, and each one counts as a failed login on the host (fail2ban, a lockout), so
	// there is no third.
	answerCloses = 2
	// loginRefusals is how many logins the host may turn away after a right password, saying
	// why, before the core stops: sshd's PAM account step does while the host boots or
	// shuts down (pam_nologin), and a little later the login gets in. The first two are
	// closed_before_login, which the core retries (turnAwayPause apart at least); the third is
	// login_refused, since an expired account stays so. Three, where answerCloses is two: there
	// the host never said whether the password was right; here it took it, and the block is
	// most often a boot. The same holds after a key the host took. See turnAwayCount for how
	// long each count is kept. A login turned away by the words of a keyboard-interactive
	// round is not counted: it stops at once (see login.turnedAway).
	loginRefusals = 3
	// turnAwayPause is the least time between a login the host turned away after a right
	// password or a key and the target's next login while the core tries again by itself: the core's
	// own backoff starts at 1 s, and sshd logs each such login after a password as "Failed
	// password", which fail2ban counts (measured with OpenSSH 9.6p1 and fail2ban 1.0.2; its
	// default bans at 5 within 10 minutes). Three tries then span 30 s, which a boot's nologin
	// rarely outlasts.
	turnAwayPause = 15 * time.Second
	// turnAwayMemory is how long such logins are counted. After a password: from the
	// loginRefusals-th on, every further one stops at once (the three lines in the host's log
	// are spent, and each Reconnect adds one), until the host turned no login away for this
	// long (fail2ban's default window, which slides: the time runs from the last one, not from
	// the stop). The next then is the first of three again. A login that finishes between
	// does not give them back: two boots within the time would write five lines. After a key
	// only this much holds: logins turned away longer ago than this are no part of a row
	// (see turnAwayCount).
	turnAwayMemory = 10 * time.Minute
)

// Algorithms: OpenSSH's client defaults (OpenSSH 10), in its order, limited to what
// x/crypto/ssh implements. That leaves out SHA-1 RSA signatures (ssh-rsa), DSA, CBC
// ciphers, diffie-hellman-group14-sha1 and hmac-sha1-96. Ed25519 host keys come first,
// so the fingerprint asked about is the one `ssh-keygen -lf
// ${PREFIX:-}/etc/ssh/ssh_host_ed25519_key.pub` prints on the host.
var (
	kexAlgos = []string{
		ssh.KeyExchangeMLKEM768X25519, ssh.KeyExchangeCurve25519,
		ssh.KeyExchangeECDHP256, ssh.KeyExchangeECDHP384, ssh.KeyExchangeECDHP521,
		ssh.KeyExchangeDHGEXSHA256, ssh.KeyExchangeDH16SHA512, ssh.KeyExchangeDH14SHA256,
	}
	cipherAlgos = []string{
		ssh.CipherChaCha20Poly1305, ssh.CipherAES128GCM, ssh.CipherAES256GCM,
		ssh.CipherAES128CTR, ssh.CipherAES192CTR, ssh.CipherAES256CTR,
	}
	macAlgos = []string{
		ssh.HMACSHA256ETM, ssh.HMACSHA512ETM, ssh.HMACSHA256, ssh.HMACSHA512, ssh.HMACSHA1,
	}
	hostKeyAlgos = []string{
		ssh.CertAlgoED25519v01, ssh.CertAlgoECDSA256v01, ssh.CertAlgoECDSA384v01, ssh.CertAlgoECDSA521v01,
		ssh.CertAlgoRSASHA512v01, ssh.CertAlgoRSASHA256v01,
		ssh.KeyAlgoED25519, ssh.KeyAlgoECDSA256, ssh.KeyAlgoECDSA384, ssh.KeyAlgoECDSA521,
		ssh.KeyAlgoRSASHA512, ssh.KeyAlgoRSASHA256,
	}
)

// DialFunc opens the TCP connection to host:port (the system network, or the tailnet).
type DialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// Pool keeps one SSH connection per target and opens streams to the target's Rex server.
// Opens of one target share its login and its probe: one question of each kind, however
// many streams wait for them.
type Pool struct {
	dial  DialFunc
	ask   Asker
	store *endpointStore

	// The constants above (stepTimeout is loginStepTimeout) and probeTimeout, channelTimeout;
	// tests shorten them.
	turnAwayPause     time.Duration
	keepaliveInterval time.Duration
	keepaliveMax      int
	connectTimeout    time.Duration
	probeTimeout      time.Duration
	channelTimeout    time.Duration
	stepTimeout       time.Duration
	now               func() time.Time

	mu         sync.Mutex
	identities []identity // in the order logins offer them; SetIdentities replaces the slice whole
	targets    map[string]*target
}

// NewPool remembers server sockets in stateDir/host-endpoints.json ("" keeps them in
// memory), dials with dial (nil: the system network) and asks ask (nil declines all).
func NewPool(stateDir string, dial DialFunc, ask Asker) *Pool {
	if dial == nil {
		var d net.Dialer
		dial = d.DialContext
	}
	p := &Pool{
		dial:              dial,
		ask:               ask,
		turnAwayPause:     turnAwayPause,
		keepaliveInterval: keepaliveInterval,
		keepaliveMax:      keepaliveMax,
		connectTimeout:    connectTimeout,
		probeTimeout:      probeTimeout,
		channelTimeout:    channelTimeout,
		stepTimeout:       loginStepTimeout,
		now:               time.Now,
		targets:           map[string]*target{},
	}
	p.store = newEndpointStore(stateDir, func() time.Time { return p.now() })
	return p
}

// SetIdentities sets the keys logins offer, in order: the phone key first, then imported
// keys in the order they were imported, as OpenSSH offers its identities. A login offers
// the first maxKeysOffered of them; a key given twice counts once. Each is unencrypted
// OpenSSH or PEM; one that does not parse is left out, as OpenSSH skips an identity it
// cannot load, and the others are set: the error is then a *KeysLeftOut naming the keys
// left out by their places. nil clears them. A login takes its keys when it starts: one
// under way, and the connections already open, keep theirs. The comment an OpenSSH key
// carries ends its public line in the key_refused fix, as on the key page.
func (p *Pool) SetIdentities(pemKeys [][]byte) error {
	ids, err := parseIdentities(pemKeys)
	p.mu.Lock()
	p.identities = ids
	p.mu.Unlock()
	return err
}

// Remembered is what the pool remembers for a target, as the SSH details panel shows it:
// the server socket ("" when none), and until when streams go through dial-stdio because
// the host refused the forward (unix seconds; 0 when no refusal is within its day).
type Remembered struct {
	Socket       string `json:"socket"`
	RefusedUntil int64  `json:"refused_until"`
}

// Remembered reads what host-endpoints.json holds for the ssh:// locator. Errors are
// ParseTarget's.
func (p *Pool) Remembered(locator string) (Remembered, error) {
	t, err := ParseTarget(locator)
	if err != nil {
		return Remembered{}, err
	}
	key := t.String()
	r := Remembered{Socket: p.store.socket(key)}
	if at, ok := p.store.refusedAt(key, p.now()); ok {
		r.RefusedUntil = at.Add(refusalMemory).Unix()
	}
	return r, nil
}

// ForgetSocket forgets the target's server socket and a refusal of the forward: the next
// stream probes again (which also starts the server) and tries the forward again. The
// connection and the streams open on it stay. A probe already under way may still
// remember the socket it finds. Errors are ParseTarget's.
func (p *Pool) ForgetSocket(locator string) error {
	t, err := ParseTarget(locator)
	if err != nil {
		return err
	}
	p.store.forget(t.String())
	return nil
}

// Open returns a stream to the Rex server of the ssh:// locator: a forward to its socket,
// or dial-stdio where the host refuses the forward. Failures are *Error, except a ctx that
// ends first (ctx.Err()); the login goes on without the caller and its connection stays
// for the next Open.
func (p *Pool) Open(ctx context.Context, locator string) (net.Conn, error) {
	t, err := ParseTarget(locator)
	if err != nil {
		return nil, err
	}
	tg := p.target(t)
	last := errConnectionClosed()
	for range openAttempts {
		c, err := p.loggedIn(ctx, tg)
		if err != nil {
			return nil, err
		}
		conn, err := p.openOn(ctx, tg, c)
		if err == nil {
			return conn, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		var retry *retryError
		var final *Error
		var channel *ssh.OpenChannelError
		switch {
		case errors.As(err, &retry):
			last = retry.last
		case errors.As(err, &final):
			return nil, final
		case errors.As(err, &channel):
			return nil, errChannelFailed(channel.Message)
		default:
			if !c.endedWithin(time.Second) { // the connection works: the host said no to something
				return nil, errChannelFailed(err.Error())
			}
			last = errConnectionClosed() // the connection ended under the stream: log in again
		}
	}
	return nil, last
}

// retryError asks Open for another round: the route changed (a refused forward, a socket
// that is gone). last is the error to report if no round is left.
type retryError struct{ last *Error }

func (e *retryError) Error() string { return e.last.Error() }

func (p *Pool) openOn(ctx context.Context, tg *target, c *sshConn) (net.Conn, error) {
	r, err := p.routeFor(ctx, tg, c)
	if err != nil {
		return nil, err
	}
	// The channel open gets channelTimeout: no question waits in it, and a host that never
	// answers would hold the dial for good. Its end is transient: the core tries again.
	cctx, cancel := context.WithTimeout(ctx, p.channelTimeout)
	defer cancel()
	timedOut := func() bool { return ctx.Err() == nil && cctx.Err() != nil }
	if r.refused {
		conn, err := dialStdio(cctx, c.client, r.rex, r.socket)
		var channel *ssh.OpenChannelError
		switch {
		case err != nil && timedOut():
			return nil, errChannelTimeout(tg.t)
		case errors.As(err, &channel):
			return nil, errForwardRefused(channel.Message)
		}
		return conn, err
	}
	conn, err := c.client.DialContext(cctx, "unix", r.socket)
	if err != nil && timedOut() {
		return nil, errChannelTimeout(tg.t)
	}
	if err == nil {
		p.store.clearRefused(tg.key)
		return conn, nil
	}
	var channel *ssh.OpenChannelError
	if !errors.As(err, &channel) {
		return nil, err
	}
	// OpenSSH answers "open failed" both when it refuses forwarding (AllowStreamLocalForwarding
	// no, DisableForwarding, restrict) and when the socket is gone, so the reason does not tell
	// them apart. A remembered socket may be gone: probe again, which also starts the server.
	// A socket the probe just named is there: the host refuses the forward, so dial-stdio
	// from now on and no forward for a day (the desktop probes once more, then does the same).
	if !r.fresh {
		p.store.forgetSocket(tg.key, r.socket)
		return nil, &retryError{errChannelFailed(channel.Message)}
	}
	p.store.setRefused(tg.key, p.now())
	return nil, &retryError{errForwardRefused(channel.Message)}
}

// InstallKey appends publicLine to ~/.ssh/authorized_keys on the target, once, over the
// pool's connection (convenience A). It needs the login, not Rex.
func (p *Pool) InstallKey(ctx context.Context, locator, publicLine string) error {
	t, err := ParseTarget(locator)
	if err != nil {
		return err
	}
	line := strings.TrimSpace(publicLine)
	if strings.ContainsAny(line, "\r\n") {
		return errors.New("rexssh: the public key is more than one line")
	}
	_, _, options, _, err := ssh.ParseAuthorizedKey([]byte(line))
	if err != nil {
		return fmt.Errorf("rexssh: not a public key line: %w", err)
	}
	if len(options) > 0 { // command=, from= and the like would restrict or run things on the host
		return errors.New("rexssh: the public key line has options")
	}
	tg := p.target(t)
	for range 2 {
		c, err := p.loggedIn(ctx, tg)
		if err != nil {
			return err
		}
		err = installKey(ctx, c.client, line)
		if err == nil || c.alive() || ctx.Err() != nil {
			return err
		}
	}
	return errConnectionClosed()
}

// installKeyCommand appends the line it reads on stdin to ~/.ssh/authorized_keys, on a line
// of its own, unless the file has the key already: its type and base64, side by side on a
// line, whatever comment or options that line has (the key_refused fix pasted by hand, a
// comment edited on the host). A line whose first word starts with "#" is a comment, which
// sshd ignores (a key revoked by hand), so it does not count. It creates ~/.ssh (700) and
// the file (600) when missing.
// The key travels on stdin: nothing from the phone is in the command line. Globbing is
// off, so the file's lines split into words and nothing else.
const installKeyCommand = `sh -c 'umask 077 && mkdir -p ~/.ssh && chmod 700 ~/.ssh && : >> ~/.ssh/authorized_keys && ` +
	`IFS= read -r line && set -f && set -- $line && type=$1 && key=$2 && have= && { ` +
	`while IFS= read -r l || [ -n "$l" ]; do set -- $l; case "$1" in "#"*) continue;; esac; while [ $# -ge 2 ]; do ` +
	`if [ "$1" = "$type" ] && [ "$2" = "$key" ]; then have=1; fi; shift; done; done < ~/.ssh/authorized_keys; ` +
	`[ -n "$have" ] || { if [ -n "$(tail -c 1 ~/.ssh/authorized_keys)" ]; then echo >> ~/.ssh/authorized_keys; fi; ` +
	`printf "%s\n" "$line" >> ~/.ssh/authorized_keys; }; }'`

func installKey(ctx context.Context, client *ssh.Client, line string) error {
	s, err := newSession(ctx, client)
	if err != nil {
		return err
	}
	defer s.Close()
	s.Stdin = strings.NewReader(line + "\n")
	var stderr bytes.Buffer
	s.Stderr = &limitWriter{w: &stderr, n: 4096}
	stop := context.AfterFunc(ctx, func() { s.Close() })
	defer stop()
	if err := s.Run(installKeyCommand); err != nil {
		return fmt.Errorf("rexssh: adding the key to ~/.ssh/authorized_keys: %v %s", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

// Forget drops the target's connection (and a login in progress), its remembered socket and
// refusal, and what its logins carry over to the next (the stall mark, the refusal to tell
// of, the closes counted).
func (p *Pool) Forget(locator string) {
	t, err := ParseTarget(locator)
	if err != nil {
		return
	}
	key := t.String()
	p.mu.Lock()
	tg := p.targets[key]
	delete(p.targets, key)
	p.mu.Unlock()
	if tg != nil {
		tg.shut()
	}
	p.store.forget(key)
}

// CloseAll drops every connection and login in progress. The pool stays usable.
func (p *Pool) CloseAll() {
	p.mu.Lock()
	targets := p.targets
	p.targets = map[string]*target{}
	p.mu.Unlock()
	for _, tg := range targets {
		tg.shut()
	}
}

// connected reports whether the pool holds a live connection for locator.
func (p *Pool) connected(locator string) bool {
	t, err := ParseTarget(locator)
	if err != nil {
		return false
	}
	p.mu.Lock()
	tg := p.targets[t.String()]
	p.mu.Unlock()
	if tg == nil {
		return false
	}
	tg.mu.Lock()
	defer tg.mu.Unlock()
	return tg.conn != nil && tg.conn.alive()
}

// target is the state of one canonical target.
type target struct {
	t      Target
	key    string
	ctx    context.Context // ends when the target is forgotten or the pool closed
	cancel context.CancelFunc

	mu        sync.Mutex
	conn      *sshConn
	login     *flight[*sshConn]
	probe     *flight[route]
	candidate string // the rex that answered the probe, while this process runs
	// What a login leaves for the next. afterStall: a host left an answer without a reply for
	// stepTimeout, or closed after a password answerCloses times, so the next logins' password
	// and prompt questions carry AfterStall until a login finishes. keyCloses: the logins in
	// a row the host closed during the key stage. turnAways, keyTurnAways: the logins the host
	// turned away after a right password, and after a key it took (see turnAwayCount).
	// retryNext: a login ended with the refusal of a password that no Retry question told
	// Kotlin of, so the next password question
	// carries Retry. retryKeep, with it: the user answered that login (it followed a stalled
	// answer), so the login that tells of the refusal is the user's too: its questions carry
	// AfterStall, and Kotlin keeps the saved password. It stays with retryNext, whatever
	// becomes of afterStall meanwhile. cutAnswers: the logins the host closed after a
	// password, without a reply (see answerCloses).
	afterStall   bool
	keyCloses    int
	turnAways    turnAwayCount
	keyTurnAways turnAwayCount
	retryNext    bool
	retryKeep    bool
	cutAnswers   int
}

// turnAwayCount counts the logins a host turned away after it took the password or a key,
// saying why (login.turnedAway): n of them, at most loginRefusals, the last one at at, since
// the host last turned none away for turnAwayMemory. The first loginRefusals-1 are tried
// again by the core, turnAwayPause apart; the loginRefusals-th stops it.
//
// A target has two, because sshd's log line differs. After a password it is "Failed
// password", which fail2ban counts (measured with fail2ban 1.0.2: 1 a login), so that count
// is kept by time alone: from the loginRefusals-th on every one stops at once, and neither
// the stop nor a finished login gives the tries back. After a key it is "Failed publickey",
// which fail2ban does not count (measured: 0), and key logins are the common ones: the stop
// starts that count again (the user's Reconnect is three tries), and so does a login that
// finishes.
type turnAwayCount struct {
	n  int
	at time.Time
}

// add counts a login turned away at now, and reports whether tries are left.
func (c *turnAwayCount) add(now time.Time) (triesLeft bool) {
	if now.Sub(c.at) >= turnAwayMemory {
		c.n = 0
	}
	c.at = now
	if c.n < loginRefusals {
		c.n++
	}
	return c.n < loginRefusals
}

// wait is how long the next login waits at now: what is left of pause since the last login
// turned away, while tries are left (after the stop the next login is the user's Reconnect).
func (c *turnAwayCount) wait(now time.Time, pause time.Duration) time.Duration {
	if c.n == 0 || c.n >= loginRefusals {
		return 0
	}
	return min(pause-now.Sub(c.at), pause) // a clock set back adds nothing
}

// route is how streams reach the server: the socket, and dial-stdio with rex when the
// host refused the forward within the last day. fresh marks a socket the probe just
// named, as opposed to one remembered from earlier.
type route struct {
	socket, rex string
	refused     bool
	fresh       bool
}

// flight is work every caller waits for and none owns: a caller that gives up leaves it
// running for the others and the next caller.
type flight[T any] struct {
	done chan struct{}
	val  T
	err  error
}

func (f *flight[T]) wait(ctx context.Context) (T, error) {
	select {
	case <-f.done:
		return f.val, f.err
	case <-ctx.Done():
		var zero T
		return zero, ctx.Err()
	}
}

func (p *Pool) target(t Target) *target {
	key := t.String()
	p.mu.Lock()
	defer p.mu.Unlock()
	tg := p.targets[key]
	if tg == nil {
		ctx, cancel := context.WithCancel(context.Background())
		tg = &target{t: t, key: key, ctx: ctx, cancel: cancel}
		p.targets[key] = tg
	}
	return tg
}

func (tg *target) shut() {
	tg.mu.Lock()
	tg.cancel()
	c := tg.conn
	tg.conn = nil
	tg.mu.Unlock()
	if c != nil {
		c.close()
	}
}

// loggedIn returns the target's live connection, joining or starting its login.
func (p *Pool) loggedIn(ctx context.Context, tg *target) (*sshConn, error) {
	tg.mu.Lock()
	if c := tg.conn; c != nil && c.alive() {
		tg.mu.Unlock()
		return c, nil
	}
	f := tg.login
	if f == nil {
		f = &flight[*sshConn]{done: make(chan struct{})}
		tg.login = f
		go p.runLogin(tg, f)
	}
	tg.mu.Unlock()
	return f.wait(ctx)
}

func (p *Pool) runLogin(tg *target, f *flight[*sshConn]) {
	c, err := p.login(tg)
	tg.mu.Lock()
	if err == nil && tg.ctx.Err() != nil { // forgotten or closed meanwhile
		c.close()
		c, err = nil, errClosedBeforeLogin(tg.t)
	}
	if err == nil {
		tg.conn = c
	}
	tg.login = nil
	tg.mu.Unlock()
	if c != nil {
		go p.keepalive(c)
		go func() {
			<-c.done
			tg.mu.Lock()
			if tg.conn == c {
				tg.conn = nil
			}
			tg.mu.Unlock()
		}()
	}
	f.val, f.err = c, err
	close(f.done)
}

// routeFor returns the remembered route, or joins or starts the target's probe.
func (p *Pool) routeFor(ctx context.Context, tg *target, c *sshConn) (route, error) {
	if r, ok := p.knownRoute(tg); ok {
		return r, nil
	}
	tg.mu.Lock()
	f := tg.probe
	if f == nil {
		f = &flight[route]{done: make(chan struct{})}
		tg.probe = f
		go p.runProbe(tg, c, f)
	}
	tg.mu.Unlock()
	return f.wait(ctx)
}

func (p *Pool) knownRoute(tg *target) (route, bool) {
	socket := p.store.socket(tg.key)
	if socket == "" {
		return route{}, false
	}
	refused := p.store.refused(tg.key, p.now())
	tg.mu.Lock()
	rex := tg.candidate
	tg.mu.Unlock()
	if refused && rex == "" {
		return route{}, false // dial-stdio needs the rex that answers: probe for it
	}
	return route{socket: socket, rex: rex, refused: refused}, true
}

func (p *Pool) runProbe(tg *target, c *sshConn, f *flight[route]) {
	socket, rex, err := probe(tg.ctx, c.client, tg.t, p.probeTimeout)
	if err == nil {
		p.store.setSocket(tg.key, socket)
		f.val = route{socket: socket, rex: rex, refused: p.store.refused(tg.key, p.now()), fresh: true}
	}
	tg.mu.Lock()
	if err == nil {
		tg.candidate = rex
	}
	tg.probe = nil
	tg.mu.Unlock()
	f.err = err
	close(f.done)
}

// sshConn is one logged-in connection.
type sshConn struct {
	client *ssh.Client
	done   chan struct{} // closed when the connection has ended
}

func newSSHConn(client *ssh.Client) *sshConn {
	c := &sshConn{client: client, done: make(chan struct{})}
	go func() {
		client.Wait()
		close(c.done)
	}()
	return c
}

func (c *sshConn) alive() bool {
	select {
	case <-c.done:
		return false
	default:
		return true
	}
}

func (c *sshConn) close() { c.client.Close() }

// endedWithin reports whether the connection has ended, or ends within d (an error on a
// channel can come just before the connection's own end is noticed).
func (c *sshConn) endedWithin(d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-c.done:
		return true
	case <-t.C:
		return false
	}
}

// keepalive sends keepalive@openssh.com every interval and drops the connection when
// keepaliveMax of them in a row went unanswered, as OpenSSH does with ServerAliveInterval
// and ServerAliveCountMax: 45 s after the first unanswered one with the desktop's values.
// Any reply counts, a refusal too (OpenSSH refuses this request).
func (p *Pool) keepalive(c *sshConn) {
	tick := time.NewTicker(p.keepaliveInterval)
	defer tick.Stop()
	answered := make(chan struct{}, 1)
	inflight, missed := false, 0
	for {
		select {
		case <-c.done:
			return
		case <-answered:
			inflight, missed = false, 0
		case <-tick.C:
			if missed >= p.keepaliveMax {
				c.close()
				return
			}
			missed++
			if !inflight { // x/crypto sends one global request at a time anyway
				inflight = true
				go func() {
					if _, _, err := c.client.SendRequest("keepalive@openssh.com", true, nil); err == nil {
						answered <- struct{}{}
					}
				}()
			}
		}
	}
}

// dialStdio runs `REX -S SOCKET __internal dial-stdio` in an exec channel and returns the
// channel as the stream.
func dialStdio(ctx context.Context, client *ssh.Client, rex, socket string) (net.Conn, error) {
	type result struct {
		ch  ssh.Channel
		err error
	}
	res := make(chan result, 1)
	go func() {
		type opened struct {
			ch   ssh.Channel
			reqs <-chan *ssh.Request
		}
		o, err := openSession(ctx, func() (opened, error) {
			ch, reqs, err := client.OpenChannel("session", nil)
			return opened{ch, reqs}, err
		})
		if err != nil {
			res <- result{nil, err}
			return
		}
		ch, reqs := o.ch, o.reqs
		go ssh.DiscardRequests(reqs) // exit-status: the stream just ends
		ok, err := ch.SendRequest("exec", true, ssh.Marshal(struct{ Command string }{dialStdioCommand(rex, socket)}))
		if err == nil && !ok {
			err = errForwardRefused("the host refused to run " + rex)
		}
		if err != nil {
			ch.Close()
			res <- result{nil, err}
			return
		}
		go io.Copy(io.Discard, ch.Stderr())
		res <- result{ch, nil}
	}()
	select {
	case r := <-res:
		if r.err != nil {
			return nil, r.err
		}
		return &stdioConn{Channel: r.ch, socket: socket}, nil
	case <-ctx.Done():
		go func() {
			if r := <-res; r.ch != nil {
				r.ch.Close()
			}
		}()
		return nil, ctx.Err()
	}
}

// stdioConn is an exec channel running dial-stdio, as a net.Conn without deadlines (like
// the streams x/crypto returns for forwards).
type stdioConn struct {
	ssh.Channel
	socket string
}

var errNoDeadline = errors.New("rexssh: ssh streams have no deadlines")

func (c *stdioConn) LocalAddr() net.Addr              { return &net.UnixAddr{Name: "@", Net: "unix"} }
func (c *stdioConn) RemoteAddr() net.Addr             { return &net.UnixAddr{Name: c.socket, Net: "unix"} }
func (c *stdioConn) SetDeadline(time.Time) error      { return errNoDeadline }
func (c *stdioConn) SetReadDeadline(time.Time) error  { return errNoDeadline }
func (c *stdioConn) SetWriteDeadline(time.Time) error { return errNoDeadline }

// The login.

var (
	errAnswerDeclined = errors.New("rexssh: a question was declined")
	errKeyChanged     = errors.New("rexssh: the host key changed")
	errNoAuthLeft     = errors.New("rexssh: no login method left")
	errLoginClosed    = errors.New("rexssh: the pool closed the connection during the login")
	errTurnedAway     = errors.New("rexssh: the host turned the login away")
)

// login is one connection's handshake: its questions and what the host did with the
// answers, which decide the error when it fails.
type login struct {
	p     *Pool
	tg    *target
	t     Target
	ctx   context.Context
	ids   []identity // the keys it offers, in order, taken when it started
	conn  *sniffConn
	watch *watchdog

	kex      bool   // the host got as far as showing its key
	hostKey  []byte // the key trusted for this connection (a later key exchange must match)
	keyType  string
	changed  bool
	declined bool
	refused  bool // the host refused a password or prompt answer
	// answered: an answer went out in the attempt that just ended. Still set when the login
	// fails, it means the host hung up in reply to that answer (x/crypto asks nextAuth for
	// another method after any other failure, and nextAuth clears it). answeredAt: the bytes
	// read from the host when the answer went out; more since is the host's reply.
	answered   bool
	answeredAt int64
	// answeredLive: the connection had not ended when that answer went out (an answer to a
	// question that outlived its connection reached no host). pwAnswered: the attempt that
	// just ended carried the answer to a password question (the password method, or PAM's
	// hidden "Password:"); lastPw: the last answer that went out was such a one (a later
	// round with questions ends it; PAM's empty round does not). nextAuth clears both with
	// answered.
	// cutAfterAnswer: the connection ended after a password that went out last, on a live
	// connection, with no reply to it.
	answeredLive   bool
	pwAnswered     bool
	lastPw         bool
	cutAfterAnswer bool
	// bannerSince: the host sent a banner after the answers of the attempt under way
	// (nextAuth clears it with answered). Its bytes are no verdict on them. wordsRound: the
	// last keyboard-interactive round of the attempt under way had no questions and the
	// host's words (nextAuth clears it with answered).
	bannerSince bool
	wordsRound  bool
	// turnedAway: the host turned the login away after the answers of the attempt that just
	// ended and said why, as sshd's PAM account step does (pam_nologin while the host boots,
	// an expired account; measured with OpenSSH 9.6p1, #302). said: the first line of the
	// words it sent last after an answer or while the keys went out, cleaned (hostWords);
	// they count only with turnedAway. With the password method the words come as a banner and the
	// connection closes, with no refusal: the password was right, so it is not refused and
	// the core may try again (loginRefusals). inRound: the words came in a keyboard-interactive
	// round without questions, as OpenSSH sends them there, with a real refusal after it. A
	// wrong password with a PAM module that has its say looks the same, so that login stops
	// at once; it is not carried either, so Kotlin keeps the saved password. Such a round can
	// also come first, before any question (#311: PAM's auth step turning a locked account
	// away): the same turn-away, with nothing answered. replied: the host turned the login
	// away in reply to an answer, and not before any was given.
	//
	// byKey: the host turned a key login away (#312): the PAM account step runs after a key
	// too, and sshd then takes the key and its signature, sends the banner and closes
	// (measured). keyBanner: a banner came while the keys of the attempt under way went out
	// (nextAuth clears it with keysTrying). The key was right, as the password is in the
	// banner's case, so tries follow as well (keyTurnAways); nothing was answered, so what a
	// login leaves about answers (the stall mark, the closes after a password) stays as it was.
	turnedAway bool
	inRound    bool
	replied    bool
	byKey      bool
	keyBanner  bool
	said       string
	// pwRefused: the host refused a password in this login (refused: any answer). untold: it
	// did, and no password question with Retry that the Asker gave back since has told
	// Kotlin so. Only passwords count: Kotlin keeps nothing of a prompt's answer.
	// retryCarried: the target's mark when the login started (target.retryNext): its password
	// questions carry Retry until the Asker gives one back.
	pwRefused    bool
	untold       bool
	retryCarried bool
	// keysTrying: the keys went out in the attempt that just ended (nextAuth clears it, as
	// it does answered). Still set when the login fails, the host ended the login during
	// the key stage.
	keysTrying bool
	// hungUp: the host ended the handshake with an SSH disconnect, not a dropped connection;
	// noTriesLeft: one that says the login has no tries left (sshd's "Too many authentication
	// failures", NO_MORE_AUTH_METHODS_AVAILABLE).
	hungUp      bool
	noTriesLeft bool
	// dropped: the connection failed (a read or write) and x/crypto went on to the next
	// method: the network, which another try may get past. droppedInKeys: while the keys
	// went out.
	dropped       bool
	droppedInKeys bool
	// stalled: the step watchdog closed the connection while the host owed the reply to an
	// answer.
	stalled bool
	// pastKeys: the login got past the key stage (the host refused the keys, or there were
	// none to offer).
	pastKeys   bool
	afterStall bool // the target's mark when the login started: its questions carry AfterStall
	partials   int
	kbdTrying  bool
	kbdAsked   bool
	kbdEmpty   bool // keyboard-interactive asked nothing: not worth another try
	exhausted  bool // no login method was left to try
	// noAlgorithm: the host offered no key exchange, cipher, MAC or host key type the pool takes.
	noAlgorithm bool
}

func (p *Pool) login(tg *target) (*sshConn, error) {
	t := tg.t
	// A try that follows a login the host turned away after a right password or a key waits
	// out what is left of turnAwayPause (see there). The target forgotten, or the pool
	// closed, ends the wait.
	tg.mu.Lock()
	now := p.now()
	wait := max(tg.turnAways.wait(now, p.turnAwayPause), tg.keyTurnAways.wait(now, p.turnAwayPause))
	tg.mu.Unlock()
	if wait > 0 {
		timer := time.NewTimer(wait)
		select {
		case <-timer.C:
		case <-tg.ctx.Done():
			timer.Stop()
			return nil, errClosedBeforeLogin(t)
		}
	}
	start := time.Now()
	dctx, cancel := context.WithTimeout(tg.ctx, p.connectTimeout)
	raw, err := p.dial(dctx, "tcp", t.hostPort())
	cancel()
	if err != nil {
		if tg.ctx.Err() != nil {
			return nil, errClosedBeforeLogin(t)
		}
		return nil, errUnreachable(t)
	}
	// The keys as they are now: removing one later does not reach this login (SetIdentities
	// replaces the slice, and this is a copy anyway).
	p.mu.Lock()
	ids := slices.Clone(p.identities[:min(len(p.identities), maxKeysOffered)])
	p.mu.Unlock()
	tg.mu.Lock()
	// A carried refusal of an answer the user gave makes this login the user's as well.
	afterStall, retryCarried := tg.afterStall || tg.retryNext && tg.retryKeep, tg.retryNext
	tg.mu.Unlock()
	l := &login{p: p, tg: tg, t: t, ctx: tg.ctx, ids: ids, conn: &sniffConn{Conn: raw}, watch: &watchdog{conn: raw},
		afterStall: afterStall, retryCarried: retryCarried}
	// The banner and key exchange, within what is left of the connect timeout, but never
	// less than handshakeFloor: a slow connect is a slow network, not a host that says nothing.
	l.watch.arm(max(p.connectTimeout-time.Since(start), handshakeFloor))
	stop := context.AfterFunc(tg.ctx, func() { raw.Close() })
	defer stop()
	cc, chans, reqs, err := ssh.NewClientConn(l.conn, t.hostPort(), &ssh.ClientConfig{
		Config:            ssh.Config{KeyExchanges: kexAlgos, Ciphers: cipherAlgos, MACs: macAlgos},
		User:              t.User,
		HostKeyCallback:   l.checkHostKey,
		HostKeyAlgorithms: hostKeyAlgos,
		AuthCallback:      l.nextAuth,
		BannerCallback:    l.banner,
	})
	l.watch.finish()
	if err != nil {
		raw.Close()
		return nil, p.ended(tg, l, err)
	}
	tg.mu.Lock()
	tg.afterStall, tg.keyCloses, tg.retryNext, tg.retryKeep, tg.cutAnswers = false, 0, false, false, 0 // the login finished
	tg.keyTurnAways = turnAwayCount{}
	tg.mu.Unlock()
	return newSSHConn(ssh.NewClient(cc, chans, reqs)), nil
}

// ended is the error of a login whose handshake failed with err, and records what it
// leaves for the target's next login. A stalled answer marks the target; a host that
// answered (refused the answer or the keys) clears the mark. A login the host closed
// during the key stage counts, and the keyStageCloses-th in a row stops as key_refused
// (the count starts over then); a login past the key stage resets the count.
//
// The refusal of a password that no Retry question told Kotlin of (refusalUntold) is carried
// to the next login's password question, on which Kotlin forgets the saved password and asks
// the user; otherwise a saved password the host no longer takes would stay and go out again
// on every Reconnect. A login whose questions carried AfterStall was answered by the user,
// so the refusal is carried as the user's (retryKeep): the login that tells of it carries
// AfterStall too, and on Retry with AfterStall Kotlin drops the answer it kept for the login
// and keeps the saved password (its ruling R4).
//
// A login the host closed after a password, without a reply, counts too, and the
// answerCloses-th stops as password_refused: a host that hangs up before it sends its
// refusal (Dropbear with a low -T) would otherwise get the saved password every 15 s. The
// host never said that the password was wrong, so the target is marked as after a stall:
// the next login asks the user and Kotlin keeps the saved password. The host's reply to an
// answer, or a finished login, starts that count again. A close after a prompt's answer is
// not counted: the user answers each of those logins, so nothing goes out by itself. A close
// after the password and PAM's round without questions is: no question waits for the user.
//
// A login the host turned away after a right password, saying why (login.turnedAway), is
// counted as well, and the first loginRefusals-1 report its sentence as closed_before_login,
// which the core retries (the next login waits out turnAwayPause); the loginRefusals-th
// stops as login_refused, and so does each further one, until the host has turned none away
// for turnAwayMemory: sshd logs every one as a failed password. Nothing else starts that
// count again, a finished login neither (a key login's count is another: see
// turnAwayCount). One whose words came in a round (inRound) stops at
// once and leaves that count alone. Either way the host replied to the answer: the stall
// mark goes, and the count of closes after a password starts again. It did not refuse the
// password, so nothing is carried (a password it refused earlier in the login, and no Retry
// question told of, still is). A key login turned away the same way (byKey) has a count of
// its own, and it is no close of the key stage: that count starts again, since the host
// took a key. It answered nothing, and neither did a login turned away by a round of words
// before any question: both leave the stall mark and the closes after a password.
//
// A disconnect ends the handshake at once, without another nextAuth: the attempt's marks are
// then as its last round left them. The host's words in a round without questions and then
// its hang-up, as sshd's when that refusal is its MaxAuthTries-th failure, are the turn-away
// that the refusal would have been.
func (p *Pool) ended(tg *target, l *login, err error) *Error {
	var negotiation *ssh.AlgorithmNegotiationError
	l.noAlgorithm = errors.As(err, &negotiation)
	if reason, message, ok := disconnectOf(err); ok {
		l.hungUp = true
		l.noTriesLeft = reason == disconnectNoMoreAuthMethods || strings.Contains(strings.ToLower(message), "too many authentication")
		if l.wordsRound && l.ctx.Err() == nil && !l.watch.expired() {
			l.turnedAway, l.inRound, l.replied = true, true, l.answered
		}
	}
	e := l.failure()
	tg.mu.Lock()
	defer tg.mu.Unlock()
	switch {
	case l.stalled:
		tg.afterStall = true
	case l.refused || l.replied || e.Code == "password_refused" || e.Code == "key_refused":
		tg.afterStall = false
	}
	if (e.Code == "password_refused" || l.turnedAway) && l.refusalUntold() {
		tg.retryNext, tg.retryKeep = true, l.afterStall
	}
	switch {
	case !l.turnedAway || l.inRound:
	case l.byKey:
		if tg.keyTurnAways.add(p.now()) {
			e.Code = "closed_before_login" // its sentence all the same: the user sees why
		} else {
			tg.keyTurnAways = turnAwayCount{} // the stop starts a key's count again (see turnAwayCount)
		}
	case tg.turnAways.add(p.now()):
		e.Code = "closed_before_login"
	}
	switch {
	case e.Code == "password_refused" || l.replied: // the host replied to an answer
		tg.cutAnswers = 0
	case l.cutAfterAnswer && l.ctx.Err() == nil && !l.watch.expired():
		tg.cutAnswers++
		if tg.cutAnswers >= answerCloses {
			tg.cutAnswers, tg.afterStall = 0, true
			e = errAnswersClosed(l.t)
		}
	}
	switch {
	case l.pastKeys || l.turnedAway: // also a key login the host turned away: it took a key
		tg.keyCloses = 0
	case l.closedInKeys():
		tg.keyCloses++
		if tg.keyCloses >= keyStageCloses {
			tg.keyCloses = 0
			e = errKeysClosed(l.t, l.ids)
		}
	}
	return e
}

// refusalUntold reports whether the login ended with the refusal of a password that no
// Retry question told Kotlin of: the host refused it and no password question followed (the
// tries ran out, the connection had ended) or the one that did was withdrawn, or the host
// hung up in reply to it saying that no tries are left (sshd's MaxAuthTries), which is its
// refusal, also after an earlier refusal that was told (but not when it hung up after its
// words in a round: that is a turn-away, see ended). A Retry question the user declined, or
// left unanswered, did tell Kotlin.
//
// The hang-up counts when it came after the answer (bytes since it went out). One the host
// sent before, right after a refusal and while the Retry question waited, reached x/crypto
// first: it reads no further, the connection does not fail, and the answer then never gets
// to the host.
func (l *login) refusalUntold() bool {
	hungUpOnIt := !l.turnedAway && l.answered && l.pwAnswered && l.hungUp && l.noTriesLeft && l.conn.bytesRead() > l.answeredAt &&
		l.ctx.Err() == nil && !l.watch.expired()
	return l.untold || hungUpOnIt
}

// banner hears a banner of the host (sshd's Banner before the login, or PAM's word when its
// account step turns a login away after a right password or a key it took, as pam_nologin
// does while the host boots). One that comes after an answer, or while the keys go out, is
// noted: sshd then exits without a success or a failure, and after an answer the banner's
// bytes would read as its reply. Its first line, cleaned, is what the host said
// (login.said); one before the login (sshd sends its Banner in reply to the first request,
// before any key goes out) is not shown.
func (l *login) banner(message string) error {
	l.bannerSince, l.keyBanner = l.answered, l.keysTrying
	if l.answered || l.keysTrying {
		l.said = hostWords(message)
	}
	return nil
}

// refuse records that the host refused the answers of the attempt that just ended.
func (l *login) refuse() {
	l.refused = true
	if l.pwAnswered {
		l.pwRefused, l.untold = true, true
	}
}

// closedInKeys reports whether the host closed the connection while the keys went out: it
// dropped, or hung up for another reason than the login's tries (a host shutting down). The
// pool's own close (the target forgotten, the login stalled) is not the host's.
func (l *login) closedInKeys() bool {
	if l.ctx.Err() != nil || l.watch.expired() {
		return false
	}
	return l.droppedInKeys || l.keysTrying && l.hungUp && !l.noTriesLeft
}

func (l *login) failure() *Error {
	switch {
	case l.noAlgorithm:
		return errNoCommonAlgorithm(l.t)
	case l.changed:
		return errHostKeyChanged(l.keyType)
	// The host turned the login away and said why (after the answer, after a key it took, or
	// before any question): its last word, also after an answer it refused earlier.
	case l.turnedAway:
		return errLoginRefused(l.t, l.said)
	// The host refused an answer in this login, also when the connection ended after the
	// refusal, before or after a Retry went out.
	case l.refused: // declining after a refusal is still a refused password
		return errPasswordRefused(l.t)
	case l.declined: // also a declined password after the host refused the key
		return errDeclined()
	// The connection dropped: what went out last was cut off, not refused, and another try
	// may get through.
	case l.dropped:
		return errClosedBeforeLogin(l.t)
	// The host hung up in reply to an answer, as OpenSSH's sshd does once a wrong one is its
	// MaxAuthTries-th failure (after refused keys, the first answer may be): a refusal, which
	// the core does not retry, so a saved password the host no longer takes is not sent
	// again and again until the account locks. A connection the pool closed itself (the
	// target forgotten, the login stalled) refused nothing.
	case l.answered && l.ctx.Err() == nil && !l.watch.expired():
		return errPasswordRefused(l.t)
	// The host hung up while the keys went out, before any answer, saying the login has no
	// tries left: sshd does once the refused keys reach its MaxAuthTries (3 or 4 on hardened
	// hosts), so it never gets to the password. Trying again meets the same limit and adds
	// failures to the host's log (and fail2ban's count): the core stops, as OpenSSH does.
	// Another disconnect (a host shutting down), a dropped connection or one the pool closed
	// itself is not the host's limit (but see keyStageCloses).
	case l.keysTrying && l.noTriesLeft && l.ctx.Err() == nil && !l.watch.expired():
		return errKeysHungUp(l.t, l.ids)
	case l.exhausted: // the keys were refused (or there is none) and the host asks no password
		if len(l.ids) == 0 {
			return errKeyRefused(l.t, nil, "")
		}
		return errKeyRefused(l.t, l.ids[0].signer, l.ids[0].comment) // the phone key, first
	case !l.kex && !l.conn.sawSSH():
		return errNotSSH(l.t)
	}
	return errClosedBeforeLogin(l.t)
}

// ask puts q to the Asker; the login's stall timer waits with it. withdrawn: the question
// was not asked, or was taken back unanswered, because the connection has ended.
//
// That happens only once the host has refused an answer in this login. The login then ends
// as password_refused whatever comes, so on a connection that has ended no answer can
// change anything: the question is not asked, and one that waits is withdrawn (#292: a host
// that closes right after its refusal left a Retry dialog up for a dead connection). A
// first question stays when its connection ends: the login has refused nothing, and Kotlin
// may keep the answer for the next login.
func (l *login) ask(q Question) (answer string, ok, withdrawn bool) {
	q.Target = l.t.String()
	if l.p.ask == nil {
		return "", false, false
	}
	l.watch.pause()
	defer l.watch.arm(l.p.stepTimeout)
	ctx, cancel := context.WithTimeout(l.ctx, questionTimeout)
	defer cancel()
	if !l.refused {
		answer, ok = l.p.ask.Ask(ctx, q)
		return answer, ok, false
	}
	if l.conn.failed() {
		return "", false, true
	}
	stop := context.AfterFunc(l.conn.ended(), cancel)
	defer stop()
	answer, ok = l.p.ask.Ask(ctx, q)
	return answer, ok, !ok && l.conn.failed()
}

// secret asks a password or prompt question, marked as the login stands. Retry: on a
// prompt when the host refused an answer in this login; on a password question when it
// refused a password in this login (a code refused before the first password question says
// nothing of the password), or in a login before with no question to say so (retryCarried).
// AfterStall: after a stalled answer. A password question with Retry that the Asker gave back
// itself, answered or declined, means that Kotlin has heard of the refusal (told); one the
// pool withdrew may never have been read. A prompt's Retry tells Kotlin nothing about a
// password, so it leaves the mark for the password question.
func (l *login) secret(q Question) (string, error) {
	password := q.Kind == "password"
	if password {
		q.Retry = l.pwRefused || l.retryCarried
	} else {
		q.Retry = l.refused
	}
	q.AfterStall = l.afterStall
	answer, ok, withdrawn := l.ask(q)
	if withdrawn {
		return "", errLoginClosed
	}
	if q.Retry && password {
		l.told()
	}
	if !ok {
		l.declined = true
		return "", errAnswerDeclined
	}
	if password {
		l.pwAnswered, l.lastPw = true, true
	}
	return answer, nil
}

// told: the Asker gave back a password question with Retry, so Kotlin has heard that the
// host refused the password before it. A carried refusal is used up, in the target too.
func (l *login) told() {
	l.untold = false
	if l.retryCarried {
		l.retryCarried = false
		l.tg.mu.Lock()
		l.tg.retryNext, l.tg.retryKeep = false, false
		l.tg.mu.Unlock()
	}
}

// checkHostKey asks about every key a new connection meets; Kotlin answers from the host
// keys it keeps (known and equal → "trust", known and different → "changed") or asks the user.
func (l *login) checkHostKey(_ string, _ net.Addr, key ssh.PublicKey) error {
	if cert, ok := key.(*ssh.Certificate); ok {
		key = cert.Key // no certificate authorities here: the host's own key is what is checked
	}
	blob := key.Marshal()
	if l.hostKey != nil { // a later key exchange on the same connection
		if bytes.Equal(blob, l.hostKey) {
			return nil
		}
		l.changed, l.keyType = true, key.Type()
		return errKeyChanged
	}
	l.kex = true
	answer, ok, _ := l.ask(Question{
		Kind:        "hostkey",
		Text:        l.t.hostPort(),
		KeyType:     key.Type(),
		Fingerprint: ssh.FingerprintSHA256(key),
	})
	switch {
	case ok && answer == "trust":
		l.hostKey = blob
		return nil
	case ok && answer == "changed":
		l.changed, l.keyType = true, key.Type()
		return errKeyChanged
	}
	l.declined = true
	return errAnswerDeclined
}

// nextAuth picks the next login method as OpenSSH does: the keys in order (x/crypto asks
// the host about each and signs with the first it takes), then keyboard-interactive, then
// password, with at most maxSecretTries answers refused.
func (l *login) nextAuth(ac *ssh.ClientAuthContext) (ssh.AuthMethod, error) {
	keysEnded, keyBanner := l.keysTrying, l.keyBanner
	l.keysTrying, l.keyBanner = false, false
	if l.ctx.Err() != nil || l.watch.expired() {
		// The pool closed the connection (x/crypto goes on after such an error): the
		// attempt that just ended was cut off, not refused, and nothing more is asked. The
		// step watchdog closing it while an answer waited for its reply is a stalled answer.
		l.stalled = l.answered && l.ctx.Err() == nil
		l.answered, l.pwAnswered, l.lastPw, l.bannerSince, l.wordsRound = false, false, false, false, false
		return nil, errLoginClosed
	}
	if l.conn.failed() && !l.declined {
		// The connection ended (a read or write failed): x/crypto goes on after such an error,
		// but a question now would wait on a dead connection. An answer the host replied to
		// first was refused: sshd sends the refusal and may exit at once, and x/crypto reads
		// both before this runs (bytes after the answer are that reply; nothing else comes
		// unasked during the login). Without a reply the attempt was cut off, not refused: the
		// answer is not called wrong (no Retry, so Kotlin keeps a saved password), and keys it
		// could not offer are not refused ones.
		replied := l.conn.bytesRead() > l.answeredAt
		samePartials := len(ac.PartialSuccessMethods) == l.partials
		switch {
		case keysEnded && keyBanner:
			// A banner while the keys went out, then the close: sshd's PAM account step turned
			// the login away after a key the host took (it sends no banner for a key it refuses).
			l.turnedAway, l.byKey = true, true
		case l.wordsRound && samePartials:
			// PAM's words in a round without questions, then the close, with or without the
			// refusal OpenSSH sends between them.
			l.turnedAway, l.inRound, l.replied = true, true, l.answered
		case !l.answered:
		case replied && samePartials && l.bannerSince:
			// A banner came after the answer, so the bytes may be the banner alone: sshd's PAM
			// account step turned the login away after a right password. No refusal to carry
			// (Kotlin would forget a password the host took).
			l.turnedAway, l.replied = true, true
		case replied && samePartials:
			l.refuse()
		case !replied && l.answeredLive && l.lastPw:
			l.cutAfterAnswer = true // the host got the password and closed without a word (see ended)
		}
		l.dropped, l.droppedInKeys = true, keysEnded
		l.answered, l.pwAnswered, l.lastPw, l.bannerSince, l.wordsRound = false, false, false, false, false
		return nil, errLoginClosed
	}
	if l.kbdTrying {
		l.kbdTrying = false
		l.kbdEmpty = !l.kbdAsked
	}
	switch { // the attempt that just ended did not log in
	// Not when the login ended the attempt itself: the user declined a later question of it
	// (PAM's code after its password), so the host gave no verdict on what was answered.
	case l.declined || len(ac.PartialSuccessMethods) != l.partials:
	// PAM's words in a round without questions, then the refusal: the host turned the login
	// away, and said why (see login.turnedAway), after the answers or before any question.
	case l.wordsRound:
		l.turnedAway, l.inRound, l.replied = true, true, l.answered
	case l.answered: // it carried an answer
		l.refuse()
	}
	l.answered, l.pwAnswered, l.lastPw, l.bannerSince, l.wordsRound = false, false, false, false, false
	l.partials = len(ac.PartialSuccessMethods)
	switch {
	case l.declined:
		return nil, errAnswerDeclined
	// No other method: the host said no to the account, not to the answer (OpenSSH refuses
	// the next keyboard-interactive attempt and then closes).
	case l.turnedAway:
		return nil, errTurnedAway
	}
	allowed := func(m string) bool { return slices.Contains(ac.AllowedMethods, m) }
	used := func(m string) int { return count(ac.TriedMethods, m) + count(ac.PartialSuccessMethods, m) }
	if len(l.ids) > 0 && allowed("publickey") && used("publickey") == 0 {
		signers := make([]ssh.Signer, len(l.ids))
		for i, id := range l.ids {
			signers[i] = id.signer
		}
		l.keysTrying = true
		return ssh.PublicKeys(signers...), nil
	}
	l.pastKeys = true // the host refused the keys, or there were none to offer
	secrets := used("keyboard-interactive") + used("password")
	if l.kbdEmpty {
		secrets-- // that attempt asked nothing
	}
	if secrets < maxSecretTries {
		if allowed("keyboard-interactive") && !l.kbdEmpty {
			l.kbdTrying, l.kbdAsked = true, false
			return ssh.KeyboardInteractive(l.challenge), nil
		}
		if allowed("password") {
			return ssh.PasswordCallback(l.password), nil
		}
	}
	l.exhausted = true
	return nil, errNoAuthLeft
}

func count(list []string, s string) (n int) {
	for _, v := range list {
		if v == s {
			n++
		}
	}
	return n
}

func passwordText(t Target) string {
	return "Enter the SSH password for " + t.User + " on " + t.Host + "."
}

func (l *login) password() (string, error) {
	answer, err := l.secret(Question{Kind: "password", Text: passwordText(l.t)})
	if err != nil {
		return "", err
	}
	l.answered, l.answeredAt, l.answeredLive = true, l.conn.bytesRead(), !l.conn.failed()
	return answer, nil
}

// challenge answers keyboard-interactive. A lone hidden "Password:" (PAM, as macOS hosts
// ask) is the password question, so a saved password answers it.
func (l *login) challenge(_, instruction string, questions []string, echos []bool) ([]string, error) {
	// A round's request is the host's answer to the round before, and no verdict on it: the
	// mark moves past it before anything is asked, so a connection that ends while this
	// round's question waits neither refused the answers before nor left them without a
	// reply. A round without questions (PAM's, after a right password) asks the user nothing:
	// a password that went out before it is still the last thing the user's side said.
	l.answeredAt = l.conn.bytesRead()
	if len(questions) > 0 {
		// What the host said before this round's answers (a banner, a round of words) is no
		// word on them.
		l.lastPw, l.bannerSince, l.wordsRound = false, false, false
	} else if l.wordsRound = strings.TrimSpace(instruction) != ""; l.wordsRound {
		// A round without questions is PAM's: OpenSSH sends one once the password is right,
		// with no words on success. With words, PAM turns the login away, and the refusal
		// follows the reply (see login.turnedAway): after the answers its account step, before
		// any question its auth step. A notice that the login goes on after is followed by a
		// round with questions, which takes the mark back.
		l.said = hostWords(instruction)
	}
	answers := make([]string, len(questions))
	for i, text := range questions {
		l.kbdAsked = true
		q := Question{Kind: "prompt", Text: text, Echo: echos[i]}
		if i == 0 && strings.TrimSpace(instruction) != "" {
			q.Text = strings.TrimSpace(instruction) + "\n" + text
		}
		if len(questions) == 1 && !echos[i] && strings.HasSuffix(strings.ToLower(strings.TrimSpace(text)), "password:") {
			q = Question{Kind: "password", Text: passwordText(l.t)}
		}
		answer, err := l.secret(q)
		if err != nil {
			return nil, err
		}
		answers[i] = answer
		l.answered = true
	}
	// Every round moves the mark, one without prompts too: OpenSSH's PAM sends an empty
	// round once the password is right, and only after the reply its SUCCESS, so the bytes
	// of that round are no verdict on the answer.
	l.answeredAt, l.answeredLive = l.conn.bytesRead(), !l.conn.failed()
	return answers, nil
}

// sniffConn remembers the first bytes the host sent, to tell a host that is not an SSH
// server from one that closed the connection during the handshake, how many bytes it read,
// and whether a read or write failed (the connection dropped or closed).
type sniffConn struct {
	net.Conn
	mu     sync.Mutex
	head   []byte
	read   int64
	broken bool
	gone   context.CancelFunc // ends the context of ended, once made
	goneC  context.Context
}

// fail records that a read or write failed. With c.mu held.
func (c *sniffConn) fail() {
	c.broken = true
	if c.gone != nil {
		c.gone()
	}
}

// ended is a context that ends once a read or write on the connection has failed.
func (c *sniffConn) ended() context.Context {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.goneC == nil {
		c.goneC, c.gone = context.WithCancel(context.Background())
		if c.broken {
			c.gone()
		}
	}
	return c.goneC
}

func (c *sniffConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 || err != nil {
		c.mu.Lock()
		if room := 256 - len(c.head); room > 0 && n > 0 {
			c.head = append(c.head, b[:min(n, room)]...)
		}
		c.read += int64(n)
		if err != nil {
			c.fail()
		}
		c.mu.Unlock()
	}
	return n, err
}

// bytesRead is how many bytes the host has sent so far.
func (c *sniffConn) bytesRead() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.read
}

func (c *sniffConn) Write(b []byte) (int, error) {
	n, err := c.Conn.Write(b)
	if err != nil {
		c.mu.Lock()
		c.fail()
		c.mu.Unlock()
	}
	return n, err
}

// failed reports whether a read or write on the connection failed.
func (c *sniffConn) failed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.broken
}

// disconnectNoMoreAuthMethods is SSH_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE (RFC 4253
// 11.1). OpenSSH's sshd sends its MaxAuthTries disconnect as SSH2_DISCONNECT_PROTOCOL_ERROR
// (2) with "Too many authentication failures" (OpenSSH 9.6p1, measured); x/crypto's server
// the same in lower case.
const disconnectNoMoreAuthMethods = 14

// disconnectOf reads the reason and message of x/crypto's error for an SSH disconnect message
// from the host in err or what it wraps (`ssh: disconnect, reason N: "MESSAGE"`; its type is
// not exported). ok is false when err is no disconnect.
func disconnectOf(err error) (reason uint32, message string, ok bool) {
	for ; err != nil; err = errors.Unwrap(err) {
		rest, found := strings.CutPrefix(err.Error(), "ssh: disconnect, reason ")
		if !found {
			continue
		}
		code, quoted, _ := strings.Cut(rest, ": ")
		n, perr := strconv.ParseUint(code, 10, 32)
		if perr != nil {
			return 0, "", true // a disconnect still, whatever its reason
		}
		message, _ = strconv.Unquote(quoted)
		return uint32(n), message, true
	}
	return 0, "", false
}

func (c *sniffConn) sawSSH() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return bytes.Contains(c.head, []byte("SSH-"))
}

// watchdog closes the connection when the host keeps the login waiting: a server that
// never sends its banner, or a login that stalls between two answers. It pauses while a
// question waits for the user.
type watchdog struct {
	conn  net.Conn
	mu    sync.Mutex
	timer *time.Timer
	done  bool
	fired bool // it closed the connection
}

func (w *watchdog) arm(d time.Duration) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.done {
		return
	}
	if w.timer != nil {
		w.timer.Stop()
	}
	w.timer = time.AfterFunc(d, func() {
		w.mu.Lock()
		w.fired = true
		w.mu.Unlock()
		w.conn.Close()
	})
}

// expired reports whether the watchdog closed the connection.
func (w *watchdog) expired() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.fired
}

func (w *watchdog) pause() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.timer != nil {
		w.timer.Stop()
		w.timer = nil
	}
}

func (w *watchdog) finish() {
	w.pause()
	w.mu.Lock()
	w.done = true
	w.mu.Unlock()
}
