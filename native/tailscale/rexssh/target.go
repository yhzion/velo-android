// Package rexssh reaches a remote user's Rex server over SSH the way the desktop's
// `rex __internal ssh-tunnel` helper does: one SSH connection per target, the server
// socket found with the desktop's probe and remembered in host-endpoints.json, and one
// direct-streamlocal@openssh.com channel per stream, or `rex -S SOCKET __internal
// dial-stdio` in an exec channel when the host refuses that forward.
//
// It holds no secrets of its own: host keys, passwords and prompts are questions for an
// Asker (Kotlin, which keeps them), and the phone key lives here in memory only.
package rexssh

import (
	"net"
	"net/url"
	"strconv"
	"strings"
)

// Target is a parsed ssh:// locator.
type Target struct {
	User, Host string
	Port       int
}

// ParseTarget accepts ssh://USER@HOST[:PORT] and nothing else: no password, no path, no
// query. A user or host starting with "-" is malformed (it would read as an option to an
// ssh command). So is a user of anything but letters, digits, ".", "_" and "-", and a host
// that is neither such a name nor an IPv6 address (the core's and the app's rule): a
// target goes into commands the user copies into a shell. The host comes back in lower
// case, as the app keeps it (Kotlin's usableUrl): a name is one host whatever its case, so
// the pool's key, the name its host key is kept under and the app's own agree. The user is
// as written. Errors are *Error with code no_user or malformed.
func ParseTarget(locator string) (Target, error) {
	if len(locator) < len("ssh://") || !strings.EqualFold(locator[:len("ssh://")], "ssh://") ||
		strings.ContainsFunc(locator, isSpaceOrControl) {
		return Target{}, errMalformed()
	}
	u, err := url.Parse(locator)
	if err != nil {
		return Target{}, errMalformed()
	}
	if u.Opaque != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return Target{}, errMoreThanTarget()
	}
	host := u.Hostname()
	if host == "" {
		return Target{}, errNoHost()
	}
	if u.User == nil || u.User.Username() == "" {
		return Target{}, errNoUser()
	}
	if _, ok := u.User.Password(); ok {
		return Target{}, errHasPassword()
	}
	user := u.User.Username()
	if strings.HasPrefix(user, "-") || strings.HasPrefix(host, "-") || !validUser(user) || !validHost(host) {
		return Target{}, errMalformed()
	}
	port := 22
	if p := u.Port(); p != "" {
		n, err := strconv.Atoi(p)
		if err != nil || n < 1 || n > 65535 {
			return Target{}, errMalformed()
		}
		port = n
	}
	return Target{User: user, Host: strings.ToLower(host), Port: port}, nil
}

// String is the canonical form, the key of host-endpoints.json and of questions:
// ssh://user@host[:port], the port left out when it is 22.
func (t Target) String() string {
	host := t.Host
	if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	if t.port() != 22 {
		host = net.JoinHostPort(t.Host, strconv.Itoa(t.port()))
	}
	return "ssh://" + t.User + "@" + host
}

func (t Target) port() int {
	if t.Port == 0 {
		return 22
	}
	return t.Port
}

// hostPort is the address to dial, and the name a host key is remembered under.
func (t Target) hostPort() string { return net.JoinHostPort(t.Host, strconv.Itoa(t.port())) }

func isSpaceOrControl(r rune) bool { return r <= ' ' || r == 0x7f }

const (
	maxName = 253 // a DNS name
	maxIPv6 = 45  // the longest text form of an IPv6 address
)

func isNameByte(c byte) bool {
	return 'a' <= c && c <= 'z' || 'A' <= c && c <= 'Z' || '0' <= c && c <= '9' || c == '.' || c == '_' || c == '-'
}

// validUser: letters, digits, ".", "_" and "-".
func validUser(user string) bool {
	for i := range len(user) {
		if !isNameByte(user[i]) {
			return false
		}
	}
	return user != ""
}

// validHost is the core's rule (Endpoint.zig): a name of letters, digits, ".", "_" and
// "-", or an IPv6 address of hex digits, ":" and ".".
func validHost(host string) bool {
	if strings.Contains(host, ":") {
		if len(host) > maxIPv6 {
			return false
		}
		for i := range len(host) {
			c := host[i]
			if !('0' <= c && c <= '9' || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F' || c == ':' || c == '.') {
				return false
			}
		}
		return true
	}
	if len(host) > maxName {
		return false
	}
	for i := range len(host) {
		if !isNameByte(host[i]) {
			return false
		}
	}
	return true
}
