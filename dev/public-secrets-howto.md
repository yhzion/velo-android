# Configure the original Velo release signer

Owner only. Run this after the new `yhzion/velo-android` repository exists **privately**, from a trusted local Bash terminal with `gh`, `keytool`, `base64`, and `sha256sum` installed. Use the original Velo release keystore and passwords from your offline backup, not a new key or a debug key. Do not paste secrets into chat, commands, or a repository. GitHub does not expose existing Actions secret values for copying.

Save this block as a local script outside the checkout and run it with Bash. It checks the target and original certificate before writing any secret; it does not tag, build, publish, or change visibility. Passwords and the alias are prompted without echo. On success, only secret names are listed.

```bash
#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077
repo=yhzion/velo-android
[[ $(gh repo view "$repo" --json nameWithOwner,visibility \
    --jq '.nameWithOwner + " " + .visibility') == "$repo PRIVATE" ]] || {
    echo 'Expected the new private yhzion/velo-android repository.' >&2
    exit 1
}
read -r -p 'Original release keystore path: ' keystore </dev/tty
[[ -f $keystore && -r $keystore ]]
read -rs -p 'Original store password: ' store_password </dev/tty; printf '\n' >&2
read -rs -p 'Original key alias: ' key_alias </dev/tty; printf '\n' >&2
read -rs -p 'Original key password: ' key_password </dev/tty; printf '\n' >&2
: "${store_password:?}" "${key_alias:?}" "${key_password:?}"
expected=b2523892cca139fe7617832288bf0bfe04d5f98de56a2fd238a048fd9c2ba772
cert=$(STORE_PASSWORD="$store_password" keytool -exportcert -keystore "$keystore" \
    -storepass:env STORE_PASSWORD -alias "$key_alias" | sha256sum)
[[ ${cert%% *} == "$expected" ]] || {
    echo 'Wrong signing certificate; no secret was set.' >&2
    exit 1
}
base64 < "$keystore" | tr -d '\r\n' | gh secret set RELEASE_KEYSTORE_BASE64 --app actions -R "$repo"
printf '%s' "$store_password" | gh secret set RELEASE_STORE_PASSWORD --app actions -R "$repo"
printf '%s' "$key_alias" | gh secret set RELEASE_KEY_ALIAS --app actions -R "$repo"
printf '%s' "$key_password" | gh secret set RELEASE_KEY_PASSWORD --app actions -R "$repo"
unset store_password key_alias key_password
names=$(gh secret list --app actions -R "$repo" --json name --jq '.[].name')
for name in RELEASE_KEYSTORE_BASE64 RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
    grep -Fxq "$name" <<< "$names"
    printf '%s\n' "$name"
done
```

If any upload fails, stop and rerun the full setup with the same original values after fixing the error. Checking the certificate proves that the selected keystore entry has the original public certificate; only a successful signing run proves the key password and private key work. Keep the encrypted offline backup. Do not use a shell trace, an online Base64 encoder, or `gh secret set --body` with secret values.

See [Android releases](../docs/releasing.md) for the separate signed APK check and publication hold.
