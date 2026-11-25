# Android releases

The [release workflow](../.github/workflows/release.yml) builds, signs, verifies and publishes a release when a `v*` tag is pushed to `yhzion/velo-android`, repository ID `1470994129`. It runs in no other repository: a fork, a copy under another name and the private archive (`1462789754`) skip both jobs. The first public release, v2.9.18, was built and signed locally as described [below](#first-public-apk-verify-privately-before-approval); later releases come from the workflow.

## Set up release signing

Prepare a dedicated release keystore and add four repository secrets under **Settings → Secrets and variables → Actions**. Do not use a debug key.

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Keystore file encoded locally as Base64 |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Alias of the signing key |
| `RELEASE_KEY_PASSWORD` | Password for that key |

Base64 is not encryption. Do not commit the keystore or passwords or use an online encoder. Keep an encrypted offline backup of the keystore and store credentials separately. Losing the key prevents updates signed with the same certificate. Protect `main`, restrict creation of `v*` tags to release maintainers, and protect existing tags from changes and deletion.

## Verify redistribution terms

The source export and release APK include stock JetBrains Mono 2.304 and D2Coding Ligature 1.4.0, with the other bundled fonts and their notices listed in [font provenance](../app/src/main/assets/fonts/PROVENANCE.txt). Keep the OFL notices and the MIT notice for the basic Powerline symbols. The Nerd Fonts composite and its glyph notices are removed from the source tree, not merely excluded from the export. Do not add the composite to the source, APK, or any bundled fixture; direct users to the [local font import instructions](../README.md#terminal-fonts).

Velo uses ZXing Core for [on-device QR scanning](../README.md#qr-scanning-and-privacy). Before publishing, compare the exact signed candidate and its resolved release runtime with [NOTICE](../NOTICE) and the [Android runtime inventory](../licenses/android/RELEASE-RUNTIME.md). Verify the packaged ZXing license and copyright notices and confirm that the APK contains no Barhopper library or barcode models, and that the resolved runtime contains no ML Kit, Play services, or scanner metrics dependencies.

## Choose a version and tag

The first stable public release was `v2.9.18`, code `3000036`, public build number `1`. The archive's largest published code was `3000035`, confirmed by its v2.9.17 APK and published release markers (including prereleases). The workflow continues that sequence with its run number: its run 1 was the v2.9.18 tag, which it skipped, so its first release is run 2, code `3000037`. Never publish a code at or below one already distributed.

Put the intended `X.Y.Z` in the root `VERSION`, without a leading `v` or a suffix, and merge it into `main` after review. The base version must be greater than `2.5.3`. You can publish multiple prereleases and then a stable release with the same base version.

Use the original v2.9.17 keystore for package `io.github.yhzion.velo`. The new repository needs the [four owner-provisioned secrets](../dev/public-secrets-howto.md) before any future approved signing workflow is enabled. The initial local candidate does not use GitHub secrets. Do not sign a dirty checkout. A candidate is not approval to change visibility, tag, or publish.

| Channel | Example (`VERSION` = `2.5.4`) | GitHub Release |
| --- | --- | --- |
| Stable | `v2.5.4` | Full release, Latest |
| Preview | `v2.5.4-preview.1` | Prerelease, not Latest |
| Edge | `v2.5.4-edge.2` | Prerelease, not Latest |

Use ASCII decimals without leading zeros. Minor and patch range from 0 to 999; the prerelease number ranges from 1 to 999,999,999. Suffixes are lowercase; other suffixes and `+build` metadata are not accepted. The following commands tag `main` and start the release workflow.

```sh
git switch main && git pull --ff-only
tag="v$(git show HEAD:VERSION)"
git tag -a "$tag" -m "$tag" && git push origin "refs/tags/$tag"
```

## Android version codes

The version script removes the leading `v` from the tag to produce `versionName`. The workflow uses `3,000,035 + GITHUB_RUN_NUMBER`, the public sequence; the archive used `3,000,000 + GITHUB_RUN_NUMBER`. Each repository has one increasing sequence across channels. A rerun keeps its run number and a new tag push takes the next one, so a failed release leaves a gap in the codes, never a reuse. The Android upper bound is 2,100,000,000.

The build job passes `RELEASE_VERSION_NAME` and `RELEASE_VERSION_CODE` together to both the initial build and the offline signed build. A local build without either variable uses `VERSION` and `major × 1,000,000 + minor × 1,000 + patch`.

## Verify the release

The workflow checks that the tag matches `VERSION` and points to a commit in `main`'s history, then runs native and Android unit tests. It checks that the APK is signed with the key in the secrets, then runs the [public APK verifier](../.github/scripts/verify-public-apk.sh): the original v2.9.17 signer certificate, package name, version and code, ABI and libraries, the allowed font inventory, and every packaged font and notice against the source commit. Then it checksums the final bytes. Before publishing, the workflow checks the checksum, tag object, and `main` ancestry again. The release body contains generated change notes and the APK's code on its own line, for example `<!-- android-version-code: 3000042 -->`. When editing the body, leave exactly one such marker unchanged.

Download the APK and `.sha256` file to the same directory, then run `sha256sum -c "velo-${tag}-arm64-v8a.apk.sha256"` with `tag` set to the downloaded tag. Use `apksigner verify --print-certs` to compare the APK certificate digest with the expected signing certificate. To check only the tag and version rules without a tag or signing key, run `bash .github/scripts/test-release-version.sh`.

Run `python3 .github/scripts/test-verify-apk-notices.py` to test the font and notice checks without Android tools or a signing key.

If a debug build uses the same package name as the release, Android will not install the release over it because the signatures differ. Record any settings you need before uninstalling the debug build; uninstalling erases its data. Subsequent releases signed with the same release key can update the installation in place.

## First public APK: verify privately before approval

This is how v2.9.18 was built and published. Later releases come from the workflow; do not publish a local build, as its build number would collide with the workflow's run numbers.

1. On the owner's trusted workstation, use a fresh checkout of the exact reviewed commit with `VERSION` set to the approved release version. Install the toolchains documented for the archive workflow. Run the following with the original keystore path and key alias supplied locally as `keystore` and `key_alias`; `apksigner` prompts for passwords, so do not pass passwords in arguments or put them in the checkout. The signing step does not contact GitHub.

   ```bash
   set -euo pipefail
   umask 077
   commit=$(git rev-parse HEAD)
   test -z "$(git status --porcelain --untracked-files=all)"
   base=$(cat VERSION)
   tag="v$base"
   build=1
   version=$(bash .github/scripts/release-version.sh "$tag" "$base" "$build" yhzion/velo-android)
   sdk="${ANDROID_HOME:?}/build-tools/36.0.0"
   apk="velo-$tag-arm64-v8a.apk"
   out=$(mktemp -d)
   (
     unset RELEASE_STORE_FILE RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD
     export RELEASE_VERSION_NAME="${tag#v}" RELEASE_VERSION_CODE="${version##*=}"
     (cd native && zig build test)
     gradle --no-daemon --no-scan -I dev/release-runtime.init.gradle \
       :app:testDebugUnitTest :app:assembleRelease :app:printReleaseRuntime | tee "$out/build.log"
   )
   "$sdk/apksigner" sign --ks "$keystore" --ks-key-alias "$key_alias" --v4-signing-enabled false \
     --out "$out/$apk" app/build/outputs/apk/release/app-release-unsigned.apk
   bash .github/scripts/verify-public-apk.sh "$out/$apk" "$tag" "$build" "$commit"
   (cd "$out" && sha256sum "$apk" > "$apk.sha256")
   read -r digest _ < "$out/$apk.sha256"
   printf 'commit=%s\ntag=%s\nbuild=%s\ncode=%s\napk_sha256=%s\n' \
     "$commit" "$tag" "$build" "${version##*=}" "$digest" > "$out/source.txt"
   printf 'Candidate and build log: %s\n' "$out"
   ```

2. Reconcile that build's dependencies with [NOTICE](../NOTICE) and the [release runtime audit](../licenses/android/RELEASE-RUNTIME.md). Preserve its R8 mapping, native build evidence and runtime inputs with the checksum. Repeat after any source or dependency change. Do not upload the candidate to GitHub to test the feed.
3. Record the approved destination commit, tag, build number, and signed APK SHA-256 together in the owner's approval record, separate from the candidate directory. After approval, create the tag in `yhzion/velo-android` on that exact commit, not on the current default branch tip. Build from the destination checkout: the history-free import has a different commit ID from the archive. Before publishing, set `commit`, `tag`, `build`, and `approved_sha256` from the approval record, not the selected candidate directory. Set `out` to that directory. Run the following as one Bash block:

   ```bash
   set -euo pipefail
   : "${commit:?}" "${tag:?}" "${build:?}" "${out:?}" "${approved_sha256:?}"
   apk="velo-$tag-arm64-v8a.apk"
   refs=$(git ls-remote --exit-code https://github.com/yhzion/velo-android.git \
     "refs/tags/$tag" "refs/tags/$tag^{}")
   target=$(awk -v ref="refs/tags/$tag" '
     $2 == ref { direct=$1 }
     $2 == ref "^{}" { peeled=$1 }
     END { print (peeled ? peeled : direct) }
   ' <<< "$refs")
   test "$target" = "$commit"
   actual=$(sha256sum "$out/$apk")
   test "${actual%% *}" = "$approved_sha256"
   printf '%s  %s\n' "$approved_sha256" "$apk" | cmp - "$out/$apk.sha256"
   bash .github/scripts/verify-public-apk.sh "$out/$apk" "$tag" "$build" "$commit"
   ```

   If either check fails, stop. Publish only the verified `velo-$tag-arm64-v8a.apk` and `velo-$tag-arm64-v8a.apk.sha256` to the existing tag. Add exactly one `<!-- android-version-code: N -->` line with the APK's actual code to the stable release body.

## Cutover check

The archive v2.9.17 binary uses `https://api.github.com/repositories/1462789754/releases`. It accepts web links under the new name, but the numeric ID identifies the old private repository, not the new repository. Users must download the first public APK and install it over the existing app once. Do not uninstall to resolve a signature or downgrade error. The new build checks `https://api.github.com/repos/yhzion/velo-android/releases`.

After the owner authorizes cutover, use an anonymous, logged-out request (no GitHub token or cookies). Request `https://api.github.com/repos/yhzion/velo-android/releases?per_page=100&page=1` with `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28` and `User-Agent: Velo-Update-Check`; require HTTP 200 without redirects. Check the stable entry is non-draft/non-prerelease, its `html_url` and both exact download URLs are under `https://github.com/yhzion/velo-android/releases/`, both assets are uploaded and nonempty, and the APK digest and sole version marker match the approved APK. Download both assets anonymously and verify the downloaded bytes and original signer again. This live check cannot be done before publication.
