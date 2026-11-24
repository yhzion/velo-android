package rexssh

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"maps"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// candidates are where the desktop looks for rex on a host, in its order: the PATH of a
// non-interactive shell, ~/.local/bin, and the server inside Rex.app on a Mac. They are
// shell words as the desktop writes them (the Mac path escapes its spaces).
var candidates = []string{
	"rex",
	"~/.local/bin/rex",
	`~/Library/Application\ Support/com.superlogical.rex/RexServer/Versions/Current/Rex\ Server.app/Contents/MacOS/rex`,
}

// probeCommand is the desktop's probe, byte for byte: start the server unless
// REX_AUTOSTART says not to, and print its status JSON.
func probeCommand(rex string) string {
	return `sh -c 'case "${REX_AUTOSTART-}" in ""|1|t|T|true|TRUE|True) ` + rex +
		` server start --json;; *) ` + rex + ` server status --json;; esac'`
}

// dialStdioCommand bridges the exec channel to the server socket, for hosts that refuse
// the forward. The socket goes in as one shell word (quoted only when it has to be).
func dialStdioCommand(rex, socket string) string {
	return rex + " -S " + shellWord(socket) + " __internal dial-stdio"
}

var plainWord = regexp.MustCompile(`^[A-Za-z0-9_@%+=:,./-]+$`)

func shellWord(s string) string {
	if plainWord.MatchString(s) {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

const (
	probeTimeout = 30 * time.Second // one candidate, its channel open too; starting the server is quick
	probeOutput  = 64 << 10
)

// errCandidateHung is a candidate whose probe ran out of time (its channel open included).
var errCandidateHung = errors.New("rexssh: the probe ran out of time")

// probe runs the probe with each candidate until one prints a status JSON with a socket
// endpoint. A candidate that is missing (exit 127), fails, prints no endpoint or runs out
// of time is skipped. None left is not_installed, unless one ran out of time: a server
// slow to start is worth another try, so that is transient (errProbeSlow). An error that
// is not about a candidate (the connection ended) is returned as is.
func probe(ctx context.Context, client *ssh.Client, t Target, timeout time.Duration) (socket, rex string, err error) {
	hung := false
	for _, rex := range candidates {
		out, err := runProbe(ctx, client, probeCommand(rex), timeout)
		if errors.Is(err, errCandidateHung) {
			hung = true
			continue
		}
		if err != nil {
			return "", "", err
		}
		if socket := endpointSocket(out); socket != "" {
			return socket, rex, nil
		}
	}
	if hung {
		return "", "", errProbeSlow(t)
	}
	return "", "", errNotInstalled(t)
}

func runProbe(ctx context.Context, client *ssh.Client, cmd string, timeout time.Duration) ([]byte, error) {
	pctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	type result struct {
		out  []byte
		err  error
		hung bool // the time ran out before the command ended
	}
	res := make(chan result, 1)
	// The work goes on without the caller once the time is up: a host that answers neither
	// the channel open nor its close cannot hold the probe.
	go func() {
		s, err := newSession(pctx, client)
		if err != nil {
			res <- result{nil, err, pctx.Err() != nil}
			return
		}
		defer s.Close()
		var out bytes.Buffer
		s.Stdout = &limitWriter{w: &out, n: probeOutput}
		s.Stderr = io.Discard // warnings, "not found": the JSON on stdout is what counts
		stop := context.AfterFunc(pctx, func() { s.Close() })
		defer stop()
		err = s.Run(cmd)
		res <- result{out.Bytes(), err, pctx.Err() != nil}
	}()
	r := result{hung: true}
	select {
	case r = <-res:
	case <-pctx.Done():
	}
	var exit *ssh.ExitError
	var missing *ssh.ExitMissingError
	switch {
	case ctx.Err() != nil:
		return nil, ctx.Err()
	case r.hung:
		return nil, errCandidateHung // try the next
	case r.err == nil, errors.As(r.err, &exit), errors.As(r.err, &missing):
		return r.out, nil
	}
	return nil, r.err
}

// sessionRetries are the waits before each new try to open a session the host refused. A
// host at its session limit (OpenSSH's MaxSessions) frees a session in its next turn after
// it has read the client's close of it, and the client's next open often comes in with that
// close: sshd then refuses it ("no more sessions" in its log). The probe's next candidate,
// dial-stdio right after the probe and a key install all open a session right after another
// one. The waits add up to 750 ms, far under channelTimeout and probeTimeout.
var sessionRetries = []time.Duration{50 * time.Millisecond, 100 * time.Millisecond, 200 * time.Millisecond, 400 * time.Millisecond}

// atSessionLimit reports whether err is a refused session open that the host's session limit
// can explain. OpenSSH 9.6 refuses it as "connect failed", "open failed" (measured against
// the e2e mock with MaxSessions 1; its default reason, the same answer as for a refused
// forward), and the session limit is its only reason to refuse a session. Other servers may
// say "administratively prohibited" or, as RFC 4254 suggests, "resource shortage". A host
// that does not know the session channel type is not asked again.
func atSessionLimit(err error) bool {
	var refused *ssh.OpenChannelError
	return errors.As(err, &refused) && refused.Reason != ssh.UnknownChannelType
}

// openSession runs open, a session channel's open, and again after each of sessionRetries
// while the host refuses it as at its session limit and ctx lasts. Any other error, and the
// last refusal, is returned as it is, so a host that refuses sessions for good fails as
// before, under a second later. Forwards (direct-streamlocal) never come here: a refused
// forward goes to dial-stdio at once.
func openSession[T any](ctx context.Context, open func() (T, error)) (T, error) {
	v, err := open()
	for _, wait := range sessionRetries {
		if err == nil || !atSessionLimit(err) {
			break
		}
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return v, err
		case <-timer.C:
		}
		v, err = open()
	}
	return v, err
}

// newSession opens a session channel (waiting out the host's session limit, openSession),
// or gives up when ctx ends (a host can leave the open unanswered); a session opened after
// that is closed.
func newSession(ctx context.Context, client *ssh.Client) (*ssh.Session, error) {
	type result struct {
		s   *ssh.Session
		err error
	}
	res := make(chan result, 1)
	go func() {
		s, err := openSession(ctx, client.NewSession)
		res <- result{s, err}
	}()
	select {
	case r := <-res:
		return r.s, r.err
	case <-ctx.Done():
		go func() {
			if r := <-res; r.s != nil {
				r.s.Close()
			}
		}()
		return nil, ctx.Err()
	}
}

// endpointSocket finds the status JSON in the probe's output, which may carry other lines
// (a login banner, a greeting) before or after it, and returns its endpoint minus unix://.
// Only an absolute path counts. The JSON starts a line (rex prints it whole, often over
// several lines); at most maxStatusTries such lines are tried, so the work stays linear in
// the output whatever the host prints.
func endpointSocket(out []byte) string {
	tries := 0
	for start := 0; start < len(out) && tries < maxStatusTries; {
		end := bytes.IndexByte(out[start:], '\n')
		if end < 0 {
			end = len(out) - start
		}
		line := bytes.TrimLeft(out[start:start+end], " \t\r")
		if len(line) > 0 && line[0] == '{' {
			tries++
			var status struct {
				Endpoint string `json:"endpoint"`
			}
			from := start + end - len(line)
			if json.NewDecoder(bytes.NewReader(out[from:])).Decode(&status) == nil {
				socket, ok := strings.CutPrefix(status.Endpoint, "unix://")
				if ok && strings.HasPrefix(socket, "/") && !strings.ContainsFunc(socket, isControl) {
					return socket
				}
			}
		}
		start += end + 1
	}
	return ""
}

const maxStatusTries = 16

func isControl(r rune) bool { return r < ' ' || r == 0x7f }

type limitWriter struct {
	w io.Writer
	n int
}

func (l *limitWriter) Write(p []byte) (int, error) {
	if l.n > 0 {
		k := min(len(p), l.n)
		l.w.Write(p[:k])
		l.n -= k
	}
	return len(p), nil // the rest is dropped, not an error the remote command would see
}

// endpointFile is host-endpoints.json: the server socket of each target, and when a host
// refused the forward to it (forwarding is not tried again for a day).
type endpointFile struct {
	Version    int               `json:"version"`
	SSHSockets map[string]string `json:"ssh_sockets"`
	Refused    map[string]int64  `json:"refused"`
}

const refusalMemory = 24 * time.Hour

// endpointStore keeps endpointFile in memory and on disk, written atomically (a temporary
// file renamed over the old one). A missing or unreadable file starts empty; a file that
// cannot be written leaves the memory copy in use. The load and each write drop the
// refusals older than refusalMemory, which no longer count; a load that dropped one writes
// the file back, so the file does not keep it until some other change. The load also moves
// what an earlier build kept under another case of a host's name to the target's key
// (canonicalKeys).
type endpointStore struct {
	path string           // "" keeps it in memory only
	now  func() time.Time // the pool's clock

	mu     sync.Mutex
	loaded bool
	f      endpointFile
}

func newEndpointStore(stateDir string, now func() time.Time) *endpointStore {
	s := &endpointStore{now: now}
	if stateDir != "" {
		s.path = filepath.Join(stateDir, "host-endpoints.json")
	}
	return s
}

func (s *endpointStore) load() {
	if s.loaded {
		return
	}
	s.loaded = true
	if s.path != "" {
		if b, err := os.ReadFile(s.path); err == nil {
			var f endpointFile
			if json.Unmarshal(b, &f) == nil && f.Version == 1 {
				s.f = f
			}
		}
	}
	s.f.Version = 1
	if s.f.SSHSockets == nil {
		s.f.SSHSockets = map[string]string{}
	}
	if s.f.Refused == nil {
		s.f.Refused = map[string]int64{}
	}
	rekeyed := canonicalKeys(s.f.SSHSockets)
	rekeyed = canonicalKeys(s.f.Refused) || rekeyed
	now := s.now()
	expired := slices.ContainsFunc(slices.Collect(maps.Values(s.f.Refused)), func(at int64) bool { return !within(at, now) })
	if rekeyed || expired {
		s.save() // drops the expired ones
	}
}

// canonicalKeys moves the entries of m kept under another spelling of a target (a host's
// name in another case, as the pool kept it before ParseTarget lowered it) to the target's
// key (Target.String), and reports whether m changed. Where that key has an entry already,
// it stays and the other spelling goes: a socket or a refusal forgotten is found again by
// the next probe. A key that is no target is left alone.
func canonicalKeys[V any](m map[string]V) (changed bool) {
	for _, key := range slices.Sorted(maps.Keys(m)) {
		t, err := ParseTarget(key)
		if err != nil || t.String() == key {
			continue
		}
		if _, taken := m[t.String()]; !taken {
			m[t.String()] = m[key]
		}
		delete(m, key)
		changed = true
	}
	return changed
}

func (s *endpointStore) save() {
	now := s.now()
	for key, at := range s.f.Refused {
		if !within(at, now) {
			delete(s.f.Refused, key)
		}
	}
	if s.path == "" {
		return
	}
	b, err := json.MarshalIndent(s.f, "", "  ")
	if err != nil {
		return
	}
	dir := filepath.Dir(s.path)
	if os.MkdirAll(dir, 0o700) != nil {
		return
	}
	tmp, err := os.CreateTemp(dir, ".host-endpoints-*.json")
	if err != nil {
		return
	}
	_, err = tmp.Write(append(b, '\n'))
	if err == nil {
		err = tmp.Sync()
	}
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(tmp.Name(), s.path)
	}
	if err != nil {
		os.Remove(tmp.Name())
	}
}

func (s *endpointStore) socket(key string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	return s.f.SSHSockets[key]
}

func (s *endpointStore) setSocket(key, socket string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	if s.f.SSHSockets[key] != socket {
		s.f.SSHSockets[key] = socket
		s.save()
	}
}

// forgetSocket drops the socket of key if it is still socket (another stream may have
// probed a new one meanwhile).
func (s *endpointStore) forgetSocket(key, socket string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	if s.f.SSHSockets[key] == socket {
		delete(s.f.SSHSockets, key)
		s.save()
	}
}

// refused reports whether the host refused the forward for key within the last day.
func (s *endpointStore) refused(key string, now time.Time) bool {
	_, ok := s.refusedAt(key, now)
	return ok
}

// refusedAt is when the host refused the forward for key, if that is within the last day.
func (s *endpointStore) refusedAt(key string, now time.Time) (time.Time, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	at, ok := s.f.Refused[key]
	if !ok || !within(at, now) {
		return time.Time{}, false
	}
	return time.Unix(at, 0), true
}

// within reports whether a refusal at unix time at still counts at now.
func within(at int64, now time.Time) bool { return now.Sub(time.Unix(at, 0)) < refusalMemory }

func (s *endpointStore) setRefused(key string, at time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	s.f.Refused[key] = at.Unix()
	s.save()
}

// clearRefused drops an old refusal once the forward works again.
func (s *endpointStore) clearRefused(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	if _, ok := s.f.Refused[key]; ok {
		delete(s.f.Refused, key)
		s.save()
	}
}

func (s *endpointStore) forget(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.load()
	_, hadSocket := s.f.SSHSockets[key]
	_, hadRefusal := s.f.Refused[key]
	if hadSocket || hadRefusal {
		delete(s.f.SSHSockets, key)
		delete(s.f.Refused, key)
		s.save()
	}
}
