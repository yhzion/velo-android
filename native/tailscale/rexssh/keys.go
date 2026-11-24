package rexssh

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"slices"
	"strings"

	"golang.org/x/crypto/ssh"
)

// GenerateKey makes the phone key: Ed25519, its private half as unencrypted OpenSSH PEM
// (Kotlin seals it), its public half as an authorized_keys line ending in comment.
func GenerateKey(comment string) (pemKey []byte, publicLine string, err error) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, "", err
	}
	block, err := ssh.MarshalPrivateKey(priv, comment)
	if err != nil {
		return nil, "", err
	}
	sshPub, err := ssh.NewPublicKey(pub)
	if err != nil {
		return nil, "", err
	}
	line := strings.TrimSpace(string(ssh.MarshalAuthorizedKey(sshPub)))
	if comment != "" {
		line += " " + comment
	}
	return pem.EncodeToMemory(block), line, nil
}

// DecryptKey opens an imported private key (OpenSSH or PEM; Ed25519, ECDSA or RSA) with
// its passphrase, "" for none, and re-encodes it as unencrypted OpenSSH PEM.
func DecryptKey(pemKey []byte, passphrase string) ([]byte, error) {
	key, err := ssh.ParseRawPrivateKey(pemKey)
	var missing *ssh.PassphraseMissingError
	if errors.As(err, &missing) && passphrase != "" {
		key, err = ssh.ParseRawPrivateKeyWithPassphrase(pemKey, []byte(passphrase))
	}
	if err != nil {
		return nil, err
	}
	block, err := ssh.MarshalPrivateKey(key, "")
	if err != nil {
		return nil, err
	}
	if _, err := signerFromPEM(pem.EncodeToMemory(block)); err != nil {
		return nil, err
	}
	return pem.EncodeToMemory(block), nil
}

// maxKeysOffered is how many keys one login offers. OpenSSH's sshd allows six failed
// attempts by default (MaxAuthTries) and counts each refused key as one, so five keys
// leave room for the password or a prompt, as with OpenSSH's own identities.
const maxKeysOffered = 5

// identity is a key logins offer, with the comment its public line ends in (as the key
// page shows it).
type identity struct {
	signer  ssh.Signer
	comment string
}

// KeysLeftOut is SetIdentities' error for keys it left out because they do not parse:
// their places in the list given, from 1. It holds nothing of the keys themselves.
type KeysLeftOut struct{ Places []int }

func (e *KeysLeftOut) Error() string {
	return fmt.Sprintf("rexssh: keys left out because they do not parse: %v", e.Places)
}

// parseIdentities parses unencrypted keys in their order; a key given again (the same
// public key) is kept once, at its first place. A key that does not parse is left out, as
// OpenSSH skips an identity it cannot load, and named (by its place) in a *KeysLeftOut.
func parseIdentities(pemKeys [][]byte) ([]identity, error) {
	ids := make([]identity, 0, len(pemKeys))
	var left []int
	for i, pemKey := range pemKeys {
		signer, err := signerFromPEM(pemKey)
		if err != nil {
			left = append(left, i+1)
			continue
		}
		pub := signer.PublicKey().Marshal()
		if slices.ContainsFunc(ids, func(id identity) bool { return bytes.Equal(id.signer.PublicKey().Marshal(), pub) }) {
			continue
		}
		ids = append(ids, identity{signer: signer, comment: keyComment(pemKey)})
	}
	if left != nil {
		return ids, &KeysLeftOut{Places: left}
	}
	return ids, nil
}

// signerFromPEM parses an unencrypted key for logging in. RSA keys sign with SHA-2 only;
// DSA is refused (OpenSSH disables both SHA-1 RSA signatures and DSA by default).
func signerFromPEM(pemKey []byte) (ssh.Signer, error) {
	signer, err := ssh.ParsePrivateKey(pemKey)
	if err != nil {
		return nil, err
	}
	switch signer.PublicKey().Type() {
	case ssh.KeyAlgoRSA:
		as, ok := signer.(ssh.AlgorithmSigner)
		if !ok {
			return nil, errors.New("rexssh: this RSA key cannot sign with SHA-2")
		}
		return ssh.NewSignerWithAlgorithms(as, []string{ssh.KeyAlgoRSASHA512, ssh.KeyAlgoRSASHA256})
	case ssh.InsecureKeyAlgoDSA:
		return nil, errors.New("rexssh: DSA keys are not supported")
	}
	return signer, nil
}

// keyComment is the comment an unencrypted OpenSSH private key carries (GenerateKey's;
// DecryptKey writes none), or "" when there is none, the key is of another format, or the
// comment holds a control character (it ends a line that a shell reads).
func keyComment(pemKey []byte) string {
	block, _ := pem.Decode(pemKey)
	const magic = "openssh-key-v1\x00"
	if block == nil || block.Type != "OPENSSH PRIVATE KEY" || !bytes.HasPrefix(block.Bytes, []byte(magic)) {
		return ""
	}
	var file struct {
		CipherName, KdfName, KdfOpts string
		NumKeys                      uint32
		PubKey, PrivKeyBlock         []byte
	}
	if ssh.Unmarshal(block.Bytes[len(magic):], &file) != nil || file.CipherName != "none" || file.NumKeys != 1 {
		return ""
	}
	var priv struct {
		Check1, Check2 uint32
		Keytype        string
		Rest           []byte `ssh:"rest"`
	}
	if ssh.Unmarshal(file.PrivKeyBlock, &priv) != nil {
		return ""
	}
	// The fields of each key type before its comment, as x/crypto/ssh reads them.
	var comment string
	switch {
	case priv.Keytype == ssh.KeyAlgoED25519:
		var k struct {
			Pub, Priv []byte
			Comment   string
			Pad       []byte `ssh:"rest"`
		}
		if ssh.Unmarshal(priv.Rest, &k) != nil {
			return ""
		}
		comment = k.Comment
	case strings.HasPrefix(priv.Keytype, "ecdsa-sha2-"):
		var k struct {
			Curve   string
			Pub     []byte
			D       *big.Int
			Comment string
			Pad     []byte `ssh:"rest"`
		}
		if ssh.Unmarshal(priv.Rest, &k) != nil {
			return ""
		}
		comment = k.Comment
	case priv.Keytype == ssh.KeyAlgoRSA:
		var k struct {
			N, E, D, Iqmp, P, Q *big.Int
			Comment             string
			Pad                 []byte `ssh:"rest"`
		}
		if ssh.Unmarshal(priv.Rest, &k) != nil {
			return ""
		}
		comment = k.Comment
	default:
		return ""
	}
	if strings.ContainsFunc(comment, isControl) {
		return ""
	}
	return comment
}
