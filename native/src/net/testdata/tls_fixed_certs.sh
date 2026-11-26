#!/bin/sh
# The fixed certificates of the malformed-length tests in net/TlsClient.zig: fixed_rsa.der
# and fixed_ec.der in this directory, embedded by the tests. Not run at test time: the
# tests need the same bytes on every run, and take the time of the handshake from a
# constant inside the 100 years these certificates are valid for. Run it again only to
# replace both files: `sh tls_fixed_certs.sh` (needs `openssl`). The keys are thrown away.
#
# Both are self-signed for velo.test and 192.0.2.1, with only the extensions the client
# reads (subjectAltName, basicConstraints, keyUsage, extendedKeyUsage), so that every
# extension value in them is parsed during a handshake.
set -eu
cd "$(dirname "$0")"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/req.cnf" <<'EOF'
[req]
distinguished_name = dn
[dn]
[v3]
subjectAltName = DNS:velo.test, IP:192.0.2.1
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, digitalSignature, keyCertSign
extendedKeyUsage = serverAuth
subjectKeyIdentifier = none
authorityKeyIdentifier = none
EOF

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/rsa.key" 2>/dev/null
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tmp/ec.key" 2>/dev/null
for kind in rsa ec; do
    openssl req -new -x509 -key "$tmp/$kind.key" -subj "/O=Velo test/CN=velo.test" -days 36500 \
        -set_serial 0x1001 -extensions v3 -config "$tmp/req.cnf" -outform DER -out "fixed_$kind.der"
done
