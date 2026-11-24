package sshexport

import (
	"io"
	"net"
	"os"
	"sync"
	"syscall"
)

// bridge hands conn to C as a socket: it returns one end of a new socketpair (blocking,
// close-on-exec), and two goroutines copy between the other end and conn. When either
// side ends, both are closed, so the goroutines never outlive the stream:
//
//   - The core closes its fd (or shuts it down both ways, all the core ever does): the
//     copy to conn drains what the core wrote, then closes conn and the Go end. Meanwhile
//     the copy from conn keeps reading conn and drops what it reads: a remote that sends
//     before it reads again (or an SSH window that is full) would otherwise never take
//     what the core wrote last, and the stream would never end.
//   - conn ends (the remote closed it, or the pool dropped the connection): the core reads
//     what was copied, then the end of the stream.
//
// libtailscale's newConn half-closes instead and closes when both directions have ended,
// which leaves a copy waiting on a remote that stays quiet after the core is gone. One
// bridge serves both routes: the stream is an SSH channel either way (the tailnet only
// carries the SSH connection under it), so newConn's tailnet server is not involved.
//
// done is closed when both goroutines have returned.
func bridge(conn net.Conn) (fd int, done <-chan struct{}, err error) {
	fds, err := socketpair()
	if err != nil {
		return -1, nil, err
	}
	f := os.NewFile(uintptr(fds[1]), "rexssh-bridge")
	// FileConn dups the fd as non-blocking on the poller, so Close wakes a blocked Read;
	// the core's end is another socket and stays blocking.
	c, err := net.FileConn(f)
	f.Close()
	if err != nil {
		syscall.Close(fds[0])
		return -1, nil, err
	}
	return fds[0], pipe(c, conn), nil
}

func socketpair() ([2]int, error) {
	syscall.ForkLock.RLock()
	defer syscall.ForkLock.RUnlock()
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		return fds, err
	}
	syscall.CloseOnExec(fds[0])
	syscall.CloseOnExec(fds[1])
	return fds, nil
}

// pipe copies between local (the Go end of the socketpair) and remote until either ends.
func pipe(local, remote net.Conn) <-chan struct{} {
	var once sync.Once
	closeBoth := func() {
		once.Do(func() {
			local.Close()
			remote.Close()
		})
	}
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { // remote → core
		defer wg.Done()
		buf := make([]byte, 32<<10)
		coreGone := false
		for {
			n, err := remote.Read(buf)
			if n > 0 && !coreGone {
				if _, werr := local.Write(buf[:n]); werr != nil {
					// The core's end is gone: the other copy drains what the core wrote
					// before it went, then closes both. Until then the remote is read and
					// dropped, so it can get to reading what the core wrote.
					coreGone = true
				}
			}
			if err != nil {
				closeBoth()
				return
			}
		}
	}()
	go func() { // core → remote
		defer wg.Done()
		io.Copy(remote, local)
		closeBoth()
	}()
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	return done
}
