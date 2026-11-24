#!/usr/bin/env bash
# Builds libtailscale.so (tsnet as a C library) for one Android ABI.
#
#   native/tailscale/build.sh <abi> <out-dir>      abi: arm64-v8a | x86_64
#
# Run by the Gradle tasks tailscale_<abi>. The libtailscale commit and every Go module
# are pinned by go.mod/go.sum next to this script; the Go toolchain by go.mod's `go` line
# (GOTOOLCHAIN=auto fetches at least that version when the installed Go is older). After
# the first run filled the module cache, the build needs no network.
# Android NDK: ANDROID_NDK_HOME, else the newest under ANDROID_HOME/ndk.
set -euo pipefail

abi=${1:?abi}
out=${2:?out dir}
here=$(cd "$(dirname "$0")" && pwd)

case $abi in
arm64-v8a) goarch=arm64 clang=aarch64-linux-android30-clang ;;
x86_64) goarch=amd64 clang=x86_64-linux-android30-clang ;;
*) echo "unsupported ABI $abi" >&2; exit 2 ;;
esac

ndk=${ANDROID_NDK_HOME:-}
if [[ -z $ndk ]]; then
	sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
	ndk=$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -1)
fi
cc=${ndk%/}/toolchains/llvm/prebuilt/linux-x86_64/bin/$clang
[[ -x $cc ]] || { echo "Android NDK clang not found: $cc" >&2; exit 1; }

cd "$here"
export GOTOOLCHAIN=auto GOFLAGS=-mod=readonly CGO_ENABLED=1 GOOS=android GOARCH=$goarch CC=$cc
# libtailscale's sources (pinned commit, from the module cache) plus android.go and ssh.go
# (what an Android app needs besides) make one package, built in a scratch copy: `go build
# -overlay` cannot add files to a package under GOMODCACHE. ssh.go's packages rexssh/ and
# sshexport/ come along as subdirectories, so velo/tailscale/... resolves there.
pkg_dir=$(go list -f '{{.Dir}}' github.com/tailscale/libtailscale)
src=$(mktemp -d)
trap 'rm -rf "$src"' EXIT
cp "$pkg_dir"/tailscale.go "$pkg_dir"/fd_linux.go "$pkg_dir"/fd_other.go "$pkg_dir"/tailscale.c "$pkg_dir"/tailscale.h "$src"/
cp "$here"/go.mod "$here"/go.sum "$here"/android.go "$here"/ssh.go "$src"/
cp -R "$here"/rexssh "$here"/sshexport "$src"/
chmod -R u+w "$src"

mkdir -p "$out"
cd "$src"
# A soname so the loader recognises the library loaded by name with dlopen; 16 KB
# pages (Android 15+) are Go's default alignment on these targets.
go build -buildmode=c-shared -trimpath -buildvcs=false \
	-ldflags='-s -w -extldflags=-Wl,-soname,libtailscale.so' -o "$out/libtailscale.so" .
# The header is not needed by the app (native/src/sys/tailnet.zig declares the functions).
rm -f "$out/libtailscale.h"
