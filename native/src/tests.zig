//! Root of the host unit tests for net/ and rex/ (`zig build test`). It lives in src/
//! so that rex/Core.zig can reach ../net: a module only imports files below the
//! directory of its root file. term/ has its own root (term/test_pane.zig).
test {
    _ = @import("net/test_net.zig");
    _ = @import("net/test_endpoint.zig");
    _ = @import("net/test_tls.zig");
    _ = @import("rex/test_model.zig");
    _ = @import("rex/test_core.zig");
    _ = @import("rex/Download.zig");
    // Its pure part only: the rest needs the Android library.
    _ = @import("sys/ssh.zig");
    // Its pure part only: the node needs the Android library.
    _ = @import("sys/tailnet.zig");
    _ = @import("rex/Tunnel.zig");
}
