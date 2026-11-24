# A TLS 1.2 server that answers the ClientHello with a ServerHello and two Certificate
# messages, the first holding the certificate in argv[1], the second none, then hangs up.
# TLS allows the server one Certificate message: the client must refuse the second.
# Prints "ACCEPT 127.0.0.1:PORT" once it listens, as openssl s_server does.
import base64
import socket
import sys

pem = open(sys.argv[1]).read()
der = base64.b64decode("".join(l for l in pem.splitlines() if "-----" not in l))
server = socket.socket()
server.bind(("127.0.0.1", 0))
server.listen(1)
print("ACCEPT 127.0.0.1:%d" % server.getsockname()[1], flush=True)
conn, _ = server.accept()


def take(n):
    data = b""
    while len(data) < n:
        more = conn.recv(n - len(data))
        if not more:
            sys.exit(0)
        data += more
    return data


take(int.from_bytes(take(5)[3:5], "big"))  # the ClientHello record


def u24(n):
    return n.to_bytes(3, "big")


def handshake(kind, body):
    return bytes([kind]) + u24(len(body)) + body


def record(body):
    return b"\x16\x03\x03" + len(body).to_bytes(2, "big") + body


# TLS 1.2, no session id, ECDHE_RSA_WITH_AES_128_GCM_SHA256, no compression, no extensions.
hello = b"\x03\x03" + bytes(range(32)) + b"\x00" + b"\xc0\x2f" + b"\x00"
certs = u24(len(der)) + der
conn.sendall(
    record(handshake(2, hello))
    + record(handshake(11, u24(len(certs)) + certs))
    + record(handshake(11, u24(0)))
)
conn.close()
