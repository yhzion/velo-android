#!/usr/bin/env bash
# Verify the original Velo signer, public sequence and release font inventory without signing secrets.
set -euo pipefail
if [[ $# != 4 ]]; then
    echo 'Usage: verify-public-apk.sh APK TAG RUN_NUMBER SOURCE_COMMIT' >&2
    exit 2
fi
apk=$1 tag=$2 run=$3 revision=$4
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
commit=$(git -C "$root" rev-parse --verify --end-of-options "$revision^{commit}")
base=$(git -C "$root" show "$commit:VERSION")
version=$(bash "$root/.github/scripts/release-version.sh" "$tag" "$base" "$run" yhzion/velo-android)
code=${version##*=}
sdk=${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/36.0.0
certs=$("$sdk/apksigner" verify --print-certs "$apk")
signer=$(sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([[:xdigit:]]+)$/\1/p' <<< "$certs")
[[ $signer == b2523892cca139fe7617832288bf0bfe04d5f98de56a2fd238a048fd9c2ba772 ]] || {
    echo 'APK signer differs from archive Velo v2.9.17' >&2; exit 1;
}
badging=$("$sdk/aapt" dump badging "$apk")
record=$(grep '^package: ' <<< "$badging")
[[ $record == "package: name='io.github.yhzion.velo' versionCode='$code' versionName='${tag#v}' "* ]] || {
    echo 'Unexpected public APK package or version' >&2; exit 1;
}
unzip -tq "$apk" lib/arm64-v8a/libvelocore.so lib/arm64-v8a/libtailscale.so
grep -Fxq "native-code: 'arm64-v8a'" <<< "$badging"
python3 "$root/.github/scripts/verify-apk-notices.py" "$apk" "$commit"
echo "Verified Velo $tag code $code, ARM64, original signer, release font inventory and source assets. This is not approval to publish."
