// Package sshexport is what the C exports in ../ssh.go do, kept out of package main (which
// only builds as libtailscale's scratch copy) so the host can test it: the one
// process-wide rexssh pool and question queue, the routing of the pool's TCP dials, the
// JSON the native core and Kotlin read, the errno-style codes, the C shapes of the exports
// with out-parameters, and the hand-off of a stream to C as a socket.
//
// Codes: 0 is success. -1 is a dial that failed, explained by its error JSON. Anything
// else is an errno value:
//   - EPERM: the call needs Init first.
//   - EEXIST: Init again with another state directory.
//   - EINVAL: a malformed argument (locator, key, key list, NULL pointer).
//   - EACCES: an imported key needs its passphrase, or the passphrase is wrong.
//   - ENOENT: the question answered is no longer waiting.
//   - ETIMEDOUT: installing the key ran out of time.
//   - EIO: installing or generating the key failed otherwise.
//   - what socketpair(2) returns (EMFILE and the like) when a dial cannot hand its stream over.
package sshexport

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/json/jsontext"
	"errors"
	"net"
	"net/netip"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/crypto/ssh"
	"velo/tailscale/rexssh"
	"tailscale.com/ipn/ipnstate"
)

// connectTimeout bounds a TCP connect over the system network, as rexssh bounds the login's.
const connectTimeout = 10 * time.Second

// Tailnet is the app's tailnet node as ssh.go reaches it through libtailscale.
type Tailnet interface {
	// Status is the node's status with its peers; an error when sd is no started node.
	Status(ctx context.Context, sd int) (*ipnstate.Status, error)
	// Dial dials addr through the node.
	Dial(ctx context.Context, sd int, network, addr string) (net.Conn, error)
}

// Exports holds the process-wide pool once Init has made it.
type Exports struct {
	tailnet Tailnet // nil: the system network only

	mu    sync.Mutex
	dir   string
	pool  *rexssh.Pool
	queue *rexssh.Queue
	// routes maps the address the pool dials (host:port) to the tailnet node the last
	// Dial of that host named (-1: none). The pool logs in on its own goroutine, shared by
	// every caller and outliving them, so a caller's context never reaches its DialFunc.
	routes map[string]int
	// dialed maps each target a Dial named (canonical) to its host:port, so Forget drops
	// a route once no target that is left uses it.
	dialed map[string]string
}

func New(tailnet Tailnet) *Exports {
	return &Exports{tailnet: tailnet, routes: map[string]int{}, dialed: map[string]string{}}
}

// Init makes the pool, which remembers server sockets in stateDir. Init again with the
// same directory does nothing.
func (e *Exports) Init(stateDir string) int {
	if stateDir != "" {
		stateDir = filepath.Clean(stateDir)
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.pool != nil {
		if stateDir == e.dir {
			return 0
		}
		return int(syscall.EEXIST)
	}
	e.dir = stateDir
	e.queue = rexssh.NewQueue()
	e.pool = rexssh.NewPool(stateDir, e.dial, e.queue)
	return 0
}

func (e *Exports) state() (*rexssh.Pool, *rexssh.Queue) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.pool, e.queue
}

// Dial opens a stream to the Rex server of the ssh:// locator within timeout (0: no limit
// beyond the pool's own) and returns the C end of a socket carrying it. sd is the app's
// tailnet node, or -1 for the system network only. On failure the error JSON is
// {"code","sentence","fix"} with rc -1; rc is an errno value, without JSON, when the call
// itself fails.
func (e *Exports) Dial(sd int, locator string, timeout time.Duration) (fd int, errJSON []byte, rc int) {
	pool, _ := e.state()
	if pool == nil {
		return -1, nil, int(syscall.EPERM)
	}
	t, err := rexssh.ParseTarget(locator)
	if err != nil {
		return -1, errorJSON(parseError(err)), -1
	}
	e.mu.Lock()
	e.routes[hostPort(t)] = sd
	e.dialed[t.String()] = hostPort(t)
	e.mu.Unlock()
	ctx := context.Background()
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}
	conn, err := pool.Open(ctx, locator)
	if err != nil {
		return -1, errorJSON(ErrorFor(t, err)), -1
	}
	fd, _, err = bridge(conn)
	if err != nil {
		conn.Close()
		var errno syscall.Errno
		if errors.As(err, &errno) {
			return -1, nil, int(errno)
		}
		return -1, nil, int(syscall.EIO)
	}
	return fd, nil, 0
}

// ErrorFor is the error a failed Open reports to the core: a *rexssh.Error as it is,
// anything else (the caller's time ran out) as the host not answering, so the core always
// has a sentence.
func ErrorFor(t rexssh.Target, err error) *rexssh.Error {
	var e *rexssh.Error
	if errors.As(err, &e) {
		return e
	}
	return &rexssh.Error{Code: "unreachable", Sentence: "Velo couldn't reach " + t.Host + " on port " + strconv.Itoa(t.Port) + "."}
}

// parseError is ParseTarget's error, which is always a *rexssh.Error.
func parseError(err error) *rexssh.Error {
	var e *rexssh.Error
	if errors.As(err, &e) {
		return e
	}
	return &rexssh.Error{Code: "malformed", Sentence: "Invalid SSH address. Use ssh://user@host."}
}

func errorJSON(e *rexssh.Error) []byte {
	b, err := json.Marshal(e)
	if err != nil { // a struct of three strings always marshals
		return []byte(`{"code":"unreachable","sentence":"","fix":""}`)
	}
	return b
}

// hostPort is the address the pool dials for t (rexssh's Target.hostPort).
func hostPort(t rexssh.Target) string { return net.JoinHostPort(t.Host, strconv.Itoa(t.Port)) }

// dial is the pool's DialFunc: through the tailnet node the host's last Dial named, when
// the node is Running and the host is one of its peers, else the system network.
func (e *Exports) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	e.mu.Lock()
	sd, ok := e.routes[addr]
	e.mu.Unlock()
	if ok && sd >= 0 && e.tailnet != nil {
		if st, err := e.tailnet.Status(ctx, sd); err == nil {
			if peer, ok := tailnetAddr(st, addr); ok {
				return e.tailnet.Dial(ctx, sd, network, peer)
			}
		}
	}
	d := net.Dialer{Timeout: connectTimeout}
	return d.DialContext(ctx, network, addr)
}

// tailnetAddr returns the address to dial through the node for addr (host:port), and
// whether host is one of the node's peers while the node is Running: a peer's Tailscale
// IP (in any form: IPv4-mapped, with a zone), its MagicDNS name (FQDN), or its short name
// in the node's own tailnet. The address is the peer's own, an IP in its canonical form; a
// name becomes the peer's address (IPv4 first), so the dial does not depend on resolving it.
func tailnetAddr(st *ipnstate.Status, addr string) (string, bool) {
	if st == nil || st.BackendState != "Running" { // ipn.Running
		return "", false
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", false
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		// The peer's own form: an IPv4-mapped address as IPv4, no zone, IPv6 canonical.
		ip = ip.Unmap().WithZone("")
		for _, p := range st.Peer {
			for _, a := range p.TailscaleIPs {
				if a == ip {
					return net.JoinHostPort(ip.String(), port), true
				}
			}
		}
		return "", false
	}
	name := canonName(host)
	suffix := ""
	if st.CurrentTailnet != nil {
		suffix = canonName(st.CurrentTailnet.MagicDNSSuffix)
	}
	for _, p := range st.Peer {
		dns := canonName(p.DNSName)
		if dns == "" || len(p.TailscaleIPs) == 0 {
			continue
		}
		if name == dns || (suffix != "" && name+"."+suffix == dns) {
			return net.JoinHostPort(preferIPv4(p.TailscaleIPs).String(), port), true
		}
	}
	return "", false
}

func canonName(s string) string { return strings.ToLower(strings.TrimSuffix(s, ".")) }

func preferIPv4(ips []netip.Addr) netip.Addr {
	for _, ip := range ips {
		if ip.Is4() {
			return ip
		}
	}
	return ips[0]
}

// Questions is the JSON array of the questions waiting for an answer, oldest first.
func (e *Exports) Questions() ([]byte, int) {
	_, queue := e.state()
	if queue == nil {
		return nil, int(syscall.EPERM)
	}
	b, err := json.Marshal(queue.Pending())
	if err != nil {
		return nil, int(syscall.EIO)
	}
	return b, 0
}

// Answer answers question id; ok false declines it.
func (e *Exports) Answer(id int64, answer string, ok bool) int {
	_, queue := e.state()
	if queue == nil {
		return int(syscall.EPERM)
	}
	if !queue.Answer(id, answer, ok) {
		return int(syscall.ENOENT)
	}
	return 0
}

// KeyLeftOut plus N is what SetIdentities returns when it left keys out because they do not
// parse, N being the place of the first one (from 1); the others are set.
const KeyLeftOut = 1000

// SetIdentities sets the keys logins offer, in order, from a JSON array of PEM strings
// (unencrypted OpenSSH or PEM): the phone key first, then imported keys as imported; "[]"
// clears them. EINVAL: not such an array (the keys stay as they were). KeyLeftOut+N: a key
// the pool cannot use was left out, N the place of the first (from 1); the others are
// set. The keys decoded on the way are cleared before it returns.
func (e *Exports) SetIdentities(js []byte) int {
	pool, _ := e.state()
	if pool == nil {
		return int(syscall.EPERM)
	}
	keys, err := decodeKeys(js)
	if err != nil {
		return int(syscall.EINVAL)
	}
	defer clearAll(keys)
	var left *rexssh.KeysLeftOut
	switch err := pool.SetIdentities(keys); {
	case err == nil:
		return 0
	case errors.As(err, &left) && len(left.Places) > 0:
		return KeyLeftOut + left.Places[0]
	}
	return int(syscall.EINVAL)
}

// decodeKeys reads a JSON array of strings into byte slices, so no key passes through a Go
// string, which could not be cleared; the copies it makes on the way are cleared.
func decodeKeys(js []byte) ([][]byte, error) {
	var raws []json.RawMessage
	err := json.Unmarshal(js, &raws)
	defer clearAll(raws)
	if err != nil {
		return nil, err
	}
	if raws == nil { // JSON null
		return nil, errors.New("sshexport: the keys are not a JSON array")
	}
	keys := make([][]byte, 0, len(raws))
	for _, raw := range raws {
		if len(raw) == 0 || raw[0] != '"' {
			clearAll(keys)
			return nil, errors.New("sshexport: a key is not a JSON string")
		}
		// The unquoted key is never longer than its quoted form: no growth, no stray copy.
		key, err := jsontext.AppendUnquote(make([]byte, 0, len(raw)), raw)
		if err != nil {
			clear(key)
			clearAll(keys)
			return nil, err
		}
		keys = append(keys, key)
	}
	return keys, nil
}

func clearAll[S ~[]byte](bs []S) {
	for _, b := range bs {
		clear(b)
	}
}

// InstallKey appends publicLine to ~/.ssh/authorized_keys on the target within timeout
// (0: no limit beyond the pool's own).
func (e *Exports) InstallKey(locator, publicLine string, timeout time.Duration) int {
	pool, _ := e.state()
	if pool == nil {
		return int(syscall.EPERM)
	}
	if _, err := rexssh.ParseTarget(locator); err != nil {
		return int(syscall.EINVAL)
	}
	ctx := context.Background()
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}
	err := pool.InstallKey(ctx, locator, publicLine)
	switch {
	case err == nil:
		return 0
	case ctx.Err() != nil:
		return int(syscall.ETIMEDOUT)
	}
	return int(syscall.EIO)
}

// Forget drops the target's connection, its remembered socket and refusal, and the route
// of its host:port unless another target the app dialed still uses it. The route goes
// first: a Dial of the target that comes in while the pool drops the connection sets it
// again, and keeps it for its login.
func (e *Exports) Forget(locator string) int {
	pool, _ := e.state()
	if pool == nil {
		return int(syscall.EPERM)
	}
	t, err := rexssh.ParseTarget(locator)
	if err != nil {
		return int(syscall.EINVAL)
	}
	e.pruneRoute(t)
	pool.Forget(locator)
	return 0
}

// pruneRoute forgets that t was dialed, and the route of its host:port unless another
// target the app dialed still uses it.
func (e *Exports) pruneRoute(t rexssh.Target) {
	e.mu.Lock()
	defer e.mu.Unlock()
	hp, ok := e.dialed[t.String()]
	if !ok {
		return
	}
	delete(e.dialed, t.String())
	for _, other := range e.dialed {
		if other == hp {
			return
		}
	}
	delete(e.routes, hp)
}

// Remembered is what the pool remembers for the target, as JSON {"socket","refused_until"}:
// the server socket ("" when none) and until when the host's refusal of the forward
// holds (unix seconds, 0 when none).
func (e *Exports) Remembered(locator string) ([]byte, int) {
	pool, _ := e.state()
	if pool == nil {
		return nil, int(syscall.EPERM)
	}
	r, err := pool.Remembered(locator)
	if err != nil {
		return nil, int(syscall.EINVAL)
	}
	b, err := json.Marshal(r)
	if err != nil {
		return nil, int(syscall.EIO)
	}
	return b, 0
}

// ForgetSocket forgets the target's server socket and refusal; its connection stays, and
// the next stream probes again.
func (e *Exports) ForgetSocket(locator string) int {
	pool, _ := e.state()
	if pool == nil {
		return int(syscall.EPERM)
	}
	if pool.ForgetSocket(locator) != nil {
		return int(syscall.EINVAL)
	}
	return 0
}

// GenerateKey makes the phone key (it needs no Init).
func GenerateKey(comment string) (pemKey []byte, publicLine string, rc int) {
	pemKey, publicLine, err := rexssh.GenerateKey(comment)
	if err != nil {
		return nil, "", int(syscall.EIO)
	}
	return pemKey, publicLine, 0
}

// DecryptKey opens an imported key with its passphrase (it needs no Init): EACCES when
// the key needs a passphrase that is missing or wrong, EINVAL when it is no key this app
// can log in with.
func DecryptKey(pemKey []byte, passphrase string) ([]byte, int) {
	out, err := rexssh.DecryptKey(pemKey, passphrase)
	var missing *ssh.PassphraseMissingError
	switch {
	case err == nil:
		return out, 0
	case errors.As(err, &missing), errors.Is(err, x509.IncorrectPasswordError):
		return nil, int(syscall.EACCES)
	}
	return nil, int(syscall.EINVAL)
}

// The C shapes of the exports with out-parameters. ssh.go passes its arguments on as they
// came: a C string as goBytes' copy (nil for NULL), an int* as *int32 and a char** as
// *unsafe.Pointer (nil for NULL); alloc is its cString. Each sets every out-parameter it
// was given (-1 or NULL) before it checks anything, so a caller that reads one after an
// error never reads what the memory held before.

// Alloc hands a string to C: a malloc'd NUL-terminated copy of b, which C frees.
type Alloc func(b []byte) unsafe.Pointer

func setNull(p *unsafe.Pointer) {
	if p != nil {
		*p = nil
	}
}

// DialC is rexssh_dial: the stream's fd in *connOut, or -1 and the error JSON in *errOut.
func (e *Exports) DialC(sd int, locator []byte, timeout time.Duration, connOut *int32, errOut *unsafe.Pointer, alloc Alloc) int {
	setNull(errOut)
	if connOut != nil {
		*connOut = -1
	}
	if connOut == nil || locator == nil {
		return int(syscall.EINVAL)
	}
	fd, js, rc := e.Dial(sd, string(locator), timeout)
	if rc != 0 {
		if js != nil && errOut != nil {
			*errOut = alloc(js)
		}
		return rc
	}
	*connOut = int32(fd)
	return 0
}

// QuestionsC is rexssh_questions.
func (e *Exports) QuestionsC(jsonOut *unsafe.Pointer, alloc Alloc) int {
	setNull(jsonOut)
	if jsonOut == nil {
		return int(syscall.EINVAL)
	}
	js, rc := e.Questions()
	if rc != 0 {
		return rc
	}
	*jsonOut = alloc(js)
	return 0
}

// RememberedC is rexssh_remembered.
func (e *Exports) RememberedC(locator []byte, jsonOut *unsafe.Pointer, alloc Alloc) int {
	setNull(jsonOut)
	if jsonOut == nil || locator == nil {
		return int(syscall.EINVAL)
	}
	js, rc := e.Remembered(string(locator))
	if rc != 0 {
		return rc
	}
	*jsonOut = alloc(js)
	return 0
}

// GenerateKeyC is rexssh_generate_key (a NULL comment is none).
func GenerateKeyC(comment []byte, pemOut, publicOut *unsafe.Pointer, alloc Alloc) int {
	setNull(pemOut)
	setNull(publicOut)
	if pemOut == nil || publicOut == nil {
		return int(syscall.EINVAL)
	}
	key, line, rc := GenerateKey(string(comment))
	defer clear(key)
	if rc != 0 {
		return rc
	}
	*pemOut = alloc(key)
	*publicOut = alloc([]byte(line))
	return 0
}

// DecryptKeyC is rexssh_decrypt_key (a NULL passphrase is none). The caller clears pem.
func DecryptKeyC(pem, passphrase []byte, pemOut *unsafe.Pointer, alloc Alloc) int {
	setNull(pemOut)
	if pemOut == nil || pem == nil {
		return int(syscall.EINVAL)
	}
	out, rc := DecryptKey(pem, string(passphrase))
	defer clear(out)
	if rc != 0 {
		return rc
	}
	*pemOut = alloc(out)
	return 0
}
