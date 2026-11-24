// What libtailscale lacks to run inside an Android app. build.sh adds this file to the
// libtailscale package (package main) at the pinned commit.
//
// Interfaces: Android denies app processes the netlink route socket and /proc/net, so
// net.Interfaces always fails there, and tsnet cannot start its network monitor without
// an interface list. The Tailscale app registers a Java-backed getter; tailscale.com's
// own fallback (feature/androidbin) is compiled out of cgo Android builds. This registers
// the same synthetic interface that fallback reports: the source addresses the kernel
// picks for outbound sockets, which is enough for magicsock to find local endpoints.
//
// Environment: tailscale.com reads its knobs with os.Getenv, whose copy of the
// environment Go took when the library loaded; tailscale_setenv sets one in that copy.
//
// Bounded calls: tailscale_dial has no deadline (a node that lost the tailnet blocks the
// caller), so the app dials with tailscale_dial_timeout. tailscale_logout and
// tailscale_https_get (the discovery sweep) are not in libtailscale either.

package main

//#include <errno.h>
//#include <stdlib.h>
import "C"

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"time"

	"tailscale.com/net/netmon"
)

//export tailscale_setenv
func tailscale_setenv(key, value *C.char) C.int {
	if err := os.Setenv(C.GoString(key), C.GoString(value)); err != nil {
		return -1
	}
	return 0
}

// tailscale_dial_timeout is tailscale_dial("tcp", addr) that gives up after timeoutMs,
// including the wait for the node to be Running.
//
//export tailscale_dial_timeout
func tailscale_dial_timeout(sd C.int, addr *C.char, timeoutMs C.int, connOut *C.int) C.int {
	s := getServer(sd)
	if s == nil {
		return C.EBADF
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	netConn, err := s.s.Dial(ctx, "tcp", C.GoString(addr))
	if err != nil {
		return s.recErr(err)
	}
	if err := newConn(s, netConn, connOut); err != nil {
		netConn.Close()
		return s.recErr(err)
	}
	return 0
}

// tailscale_logout logs the node out of the tailnet (it then needs a new login).
//
//export tailscale_logout
func tailscale_logout(sd C.int) C.int {
	s := getServer(sd)
	if s == nil {
		return C.EBADF
	}
	lc, err := s.s.LocalClient()
	if err != nil {
		return s.recErr(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return s.recErr(lc.Logout(ctx))
}

// tailscale_https_get GETs url over the tailnet within timeoutMs and writes the HTTP
// status, and at most 64 KiB of the body as a malloc'd NUL-terminated string the caller
// frees, to *status_out and *body_out.
//
//export tailscale_https_get
func tailscale_https_get(sd C.int, url *C.char, timeoutMs C.int, statusOut *C.int, bodyOut **C.char) C.int {
	*bodyOut = nil
	s := getServer(sd)
	if s == nil {
		return C.EBADF
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "GET", C.GoString(url), nil)
	if err != nil {
		return s.recErr(err)
	}
	resp, err := s.s.HTTPClient().Do(req)
	if err != nil {
		return s.recErr(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return s.recErr(err)
	}
	*statusOut = C.int(resp.StatusCode)
	*bodyOut = (*C.char)(C.CBytes(append(body, 0)))
	return 0
}

func init() {
	netmon.RegisterInterfaceGetter(outboundInterfaces)
}

func outboundInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	// Connecting a UDP socket sends nothing; the kernel only picks a route and a source.
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, errors.New("no outbound route")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{Index: 1, MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}
