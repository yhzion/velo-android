#!/bin/sh
# Certificate chains for the TLS chain-walk tests in net/test_tls.zig, which runs this
# script (embedded) as `sh -c SCRIPT sh DIR` and serves each chain with `openssl s_server`.
# Everything is generated into DIR, valid for one day, with throwaway P-256 keys.
#
# DIR/ca/root.pem          trusted root for most cases
# DIR/ca-pathlen0/          a root with pathlen 0 (trust store of its own)
# DIR/ca-leaf/              a CA:FALSE certificate used as a trust anchor
# DIR/ca-self/              a self-signed server certificate trusted directly
# DIR/ca-v1root/            a self-signed version 1 root
# DIR/ca-v1signed/          a version 1 certificate that is not self-signed, trusted
# DIR/ca-nc/                a root with nameConstraints, as the sandbox test CA has
# DIR/NAME.pem, NAME.key   the server certificate of case NAME and its key
# DIR/NAME.issuers.pem     the certificates the server sends after it (root excluded),
#                          when there are any
set -eu
cd "$1"
mkdir -p ca ca-pathlen0 ca-leaf ca-self ca-v1root ca-v1signed ca-nc

key() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1.key" 2>/dev/null; }

# cert NAME SUBJECT ISSUER EXTENSIONS: NAME.pem signed by ISSUER.key (ISSUER.pem's name).
cert() {
    key "$1"
    printf '%b\n' "$4" > "$1.ext"
    openssl req -new -key "$1.key" -subj "$2" -out "$1.csr" 2>/dev/null
    openssl x509 -req -in "$1.csr" -CA "$3.pem" -CAkey "$3.key" -set_serial "0x$(openssl rand -hex 8)" \
        -days 1 -extfile "$1.ext" -out "$1.pem" 2>/dev/null
}

# selfsigned NAME SUBJECT EXTENSIONS
selfsigned() {
    key "$1"
    printf '%b\n' "$3" > "$1.ext"
    openssl req -new -x509 -key "$1.key" -subj "$2" -days 1 -set_serial "0x$(openssl rand -hex 8)" \
        -extensions v3 -config /dev/stdin -out "$1.pem" 2>/dev/null <<EOF
[req]
distinguished_name = dn
[dn]
[v3]
$(cat "$1.ext")
EOF
}

# v1 NAME SUBJECT ISSUER: NAME.pem, a version 1 certificate (no extensions) signed by
# ISSUER.key, or self-signed when ISSUER is NAME. OpenSSL 3 always writes version 3, so
# the TBSCertificate is rewritten without its version and extensions and signed again.
v1() {
    key "$1"
    openssl req -new -key "$1.key" -subj "$2" -out "$1.csr" 2>/dev/null
    if [ "$1" = "$3" ]; then
        openssl x509 -req -in "$1.csr" -key "$1.key" -days 1 -out "$1.v3.pem" 2>/dev/null
    else
        openssl x509 -req -in "$1.csr" -CA "$3.pem" -CAkey "$3.key" -set_serial "0x$(openssl rand -hex 8)" \
            -days 1 -out "$1.v3.pem" 2>/dev/null
    fi
    python3 - "$1.v3.pem" "$3.key" "$1.pem" <<'PY'
import base64, subprocess, sys
def read(b, i):  # one DER element at b[i:]: (tag, body start, end)
    tag, n = b[i], b[i + 1]
    j = i + 2
    if n & 0x80:
        k = n & 0x7f
        n = int.from_bytes(b[j:j + k], "big")
        j += k
    return tag, j, j + n
def enc(tag, body):
    n = len(body)
    head = bytes([n]) if n < 0x80 else bytes([0x80 | ((n.bit_length() + 7) // 8)]) + n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([tag]) + head + body
pem, key, out = sys.argv[1:]
der = base64.b64decode("".join(l for l in open(pem).read().splitlines() if "-----" not in l))
_, c, _ = read(der, 0)
_, t, t_end = read(der, c)
fields, i = [], t
while i < t_end:
    tag, _, end = read(der, i)
    if tag not in (0xa0, 0xa3):  # version and extensions go: version 1
        fields.append(der[i:end])
    i = end
tbs = enc(0x30, b"".join(fields))
_, a, a_end = read(der, t_end)
alg = der[t_end:a_end]
sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=tbs, capture_output=True, check=True).stdout
cert = enc(0x30, tbs + alg + enc(0x03, b"\0" + sig))
b64 = base64.b64encode(cert).decode()
open(out, "w").write("-----BEGIN CERTIFICATE-----\n" + "\n".join(b64[k:k + 64] for k in range(0, len(b64), 64)) + "\n-----END CERTIFICATE-----\n")
PY
    rm -f "$1.v3.pem"
}

ca='basicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign'
ca0='basicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign'
leaf='basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth'

selfsigned root "/CN=Rex Test Root" "$ca"
cp root.pem ca/
selfsigned root0 "/CN=Rex Test Root pathlen 0" "$ca0"
cp root0.pem ca-pathlen0/

# valid: root > inter (CA, pathlen 0) > leaf for localhost and 127.0.0.1.
cert inter "/CN=Rex Test Intermediate" root "$ca0"
cert valid "/CN=localhost" inter "$leaf\nsubjectAltName=DNS:localhost,IP:127.0.0.1"
cp inter.pem valid.issuers.pem

# forged: root > a.test (a server certificate, CA:FALSE) > localhost, signed with the
# key of a.test. Anyone with a publicly issued certificate holds such a key.
cert atest "/CN=a.test" root "$leaf\nsubjectAltName=DNS:a.test"
cert forged "/CN=localhost" atest "$leaf\nsubjectAltName=DNS:localhost"
cp atest.pem forged.issuers.pem

# nobc: an issuer without basicConstraints.
cert inter_nobc "/CN=Rex Test No Basic Constraints" root 'keyUsage=critical,keyCertSign,cRLSign'
cert nobc "/CN=localhost" inter_nobc "$leaf\nsubjectAltName=DNS:localhost"
cp inter_nobc.pem nobc.issuers.pem

# pathlen: root > inter (pathlen 0) > inter2 (CA) > leaf: inter may not sign a CA.
cert inter2 "/CN=Rex Test Intermediate 2" inter "$ca"
cert pathlen "/CN=localhost" inter2 "$leaf\nsubjectAltName=DNS:localhost"
cat inter2.pem inter.pem > pathlen.issuers.pem

# nocertsign: an issuer that is a CA but whose keyUsage lacks keyCertSign.
cert inter_nocs "/CN=Rex Test No Cert Sign" root 'basicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,cRLSign'
cert nocertsign "/CN=localhost" inter_nocs "$leaf\nsubjectAltName=DNS:localhost"
cp inter_nocs.pem nocertsign.issuers.pem

# anchor_pathlen: root0 (pathlen 0) > inter_a (CA) > leaf.
cert inter_a "/CN=Rex Test Intermediate A" root0 "$ca"
cert anchor_pathlen "/CN=localhost" inter_a "$leaf\nsubjectAltName=DNS:localhost"
cp inter_a.pem anchor_pathlen.issuers.pem

# The forged leaf alone, for a trust store that holds a.test itself (ca-leaf).
cert forged_only "/CN=localhost" atest "$leaf\nsubjectAltName=DNS:localhost"
cp atest.pem ca-leaf/

# selfsigned: a server certificate (CA:FALSE) trusted as itself (ca-self).
selfsigned self "/CN=localhost" "$leaf\nsubjectAltName=DNS:localhost"
cp self.pem ca-self/

# Host names for an IP locator: only an iPAddress entry may match it.
cert ip_wild "/CN=localhost" inter "$leaf\nsubjectAltName=DNS:*.0.0.1"
cert ip_dns "/CN=localhost" inter "$leaf\nsubjectAltName=DNS:127.0.0.1"
cert ip_cn "/CN=127.0.0.1" inter "$leaf"
for n in ip_wild ip_dns ip_cn; do cp inter.pem "$n.issuers.pem"; done

# direct: the root signs the server's certificate, with no intermediate.
cert direct "/CN=localhost" root "$leaf\nsubjectAltName=DNS:localhost"

# valid6: as valid, for the IPv6 locator [::1].
cert valid6 "/CN=localhost" inter "$leaf\nsubjectAltName=DNS:localhost,IP:::1"
cp inter.pem valid6.issuers.pem

# v1 trust anchors: a self-signed v1 root is a CA, as OpenSSL's V1_ROOT; a v1
# certificate signed by another key is not.
v1 v1root "/CN=Rex Test V1 Root" v1root
cp v1root.pem ca-v1root/
cert v1_root "/CN=localhost" v1root "$leaf\nsubjectAltName=DNS:localhost\nauthorityKeyIdentifier=none"
v1 v1mid "/CN=Rex Test V1 Signed" root
cp v1mid.pem ca-v1signed/
cert v1_signed "/CN=localhost" v1mid "$leaf\nsubjectAltName=DNS:localhost\nauthorityKeyIdentifier=none"

# rollover: root > inter (pathlen 0) > inter_new (the same name, a new key: self-issued)
# > leaf. RFC 5280 6.1.4(l) does not count a self-issued intermediate.
cert inter_new "/CN=Rex Test Intermediate" inter "$ca"
cert rollover "/CN=localhost" inter_new "$leaf\nsubjectAltName=DNS:localhost"
cat inter_new.pem inter.pem > rollover.issuers.pem

# Name constraints: an intermediate for localhost and 127.0.0.1 only, one excluding
# localhost, one constraining email names, and a root like the sandbox test CA.
nc="$ca\nnameConstraints=critical,permitted;DNS:localhost,permitted;IP:127.0.0.1/255.255.255.255"
cert inter_nc "/CN=Rex Test Constrained" root "$nc"
cert nc_ok "/CN=localhost" inter_nc "$leaf\nsubjectAltName=DNS:localhost,IP:127.0.0.1"
cert nc_outside "/CN=localhost" inter_nc "$leaf\nsubjectAltName=DNS:localhost,DNS:evil.test"
cert nc_ip "/CN=localhost" inter_nc "$leaf\nsubjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.0.1"
for n in nc_ok nc_outside nc_ip; do cp inter_nc.pem "$n.issuers.pem"; done
cert inter_ex "/CN=Rex Test Excluding" root "$ca\nnameConstraints=critical,excluded;DNS:localhost"
cert nc_excluded "/CN=localhost" inter_ex "$leaf\nsubjectAltName=DNS:localhost"
cp inter_ex.pem nc_excluded.issuers.pem
cert inter_mail "/CN=Rex Test Mail Constrained" root "$ca\nnameConstraints=critical,permitted;email:.example.com"
cert nc_email "/CN=localhost" inter_mail "$leaf\nsubjectAltName=DNS:localhost,email:a@mail.example.com"
cp inter_mail.pem nc_email.issuers.pem
selfsigned root_nc "/CN=Rex Test Constrained Root" "$nc"
cp root_nc.pem ca-nc/
cert nc_anchor "/CN=localhost" root_nc "$leaf\nsubjectAltName=DNS:localhost,IP:127.0.0.1"
cert nc_anchor_outside "/CN=localhost" root_nc "$leaf\nsubjectAltName=DNS:localhost,DNS:other.test"

# A dNSName written with another tag (universal 2, not [2]): the standard host match reads
# only the tag number, so it would match while the name constraints skipped it.
cert san_mistagged "/CN=localhost" inter "$leaf\n2.5.29.17=DER:300b02096c6f63616c686f7374"
cp inter.pem san_mistagged.issuers.pem
# Excluded subtrees reached otherwise than by the same bytes: a wildcard name that covers an
# excluded name, and a subject spelled in other case than the excluded directoryName.
cert inter_exw "/CN=Rex Test Excluding Bank" root "$ca\nnameConstraints=critical,excluded;DNS:bank.example.test"
cert nc_wildcard "/CN=localhost" inter_exw "$leaf\nsubjectAltName=DNS:localhost,DNS:*.example.test"
cp inter_exw.pem nc_wildcard.issuers.pem
cert inter_exd "/CN=Rex Test Excluding Dir" root "$ca\nnameConstraints=critical,excluded;dirName:excluded_dn\n[excluded_dn]\nCN=LOCALHOST"
cert nc_dir_case "/CN=localhost" inter_exd "$leaf\nsubjectAltName=DNS:localhost"
cp inter_exd.pem nc_dir_case.issuers.pem

# extendedKeyUsage of the server's certificate: TLS clients only, or any purpose.
plain='basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nsubjectAltName=DNS:localhost'
cert eku_client "/CN=localhost" inter "$plain\nextendedKeyUsage=clientAuth"
cert eku_any "/CN=localhost" inter "$plain\nextendedKeyUsage=anyExtendedKeyUsage"
# eku_none: no extendedKeyUsage at all, as many self-made certificates: no limit on its use.
cert eku_none "/CN=localhost" inter "$plain"
for n in eku_client eku_any eku_none; do cp inter.pem "$n.issuers.pem"; done

# An unknown critical extension, in the server's certificate and in an intermediate.
crit='1.2.3.4=critical,ASN1:UTF8String:rex'
cert crit_leaf "/CN=localhost" inter "$leaf\nsubjectAltName=DNS:localhost\n$crit"
cp inter.pem crit_leaf.issuers.pem
cert inter_crit "/CN=Rex Test Critical" root "$ca\n$crit"
cert crit_inter "/CN=localhost" inter_crit "$leaf\nsubjectAltName=DNS:localhost"
cp inter_crit.pem crit_inter.issuers.pem

rm -f ./*.csr ./*.ext
