# Releasing

Every release is built by GitHub Actions from a version tag, so anyone can check that the files attached to a release
were built from that tag. One version number drives everything:

| Where | What follows `VERSION` |
| --- | --- |
| `VERSION` (repository root) | the single source, e.g. `1.2.0` or `1.2.0-rc.1` |
| Android A54 app and client | `versionName` = `VERSION`; `versionCode` = major·1000000 + minor·10000 + patch·100 + 99 (a pre-release `-rc.N` uses N instead of 99, so it sorts before the release). Read by `android/app` and `android/client` `build.gradle.kts` |
| iPhone app | `MARKETING_VERSION` (without the suffix) and `CURRENT_PROJECT_VERSION` (= the versionCode) in `ios/project.yml` and the generated project, written by `tools/release.sh` |
| Swift package TetherCore | the git tag `v<version>` |

`tools/release.sh --code 1.2.0` prints the versionCode of a version.

## 1. A release key (once)

Android only installs an update signed with the same key as the installed app, so keep this key forever and back it up
outside the repository.

```bash
keytool -genkeypair -v -keystore tether-release.jks -alias tether -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Tether"
```

Add it to the repository's Actions secrets (the values go to GitHub, never into a file in the repository):

```bash
base64 -i tether-release.jks | gh secret set TETHER_KEYSTORE_BASE64
gh secret set TETHER_KEYSTORE_PASSWORD     # prompts for the value
gh secret set TETHER_KEY_ALIAS --body tether
gh secret set TETHER_KEY_PASSWORD
```

For local release builds with the same key, put the same four values in `android/local.properties` (git-ignored):
`tether.signing.storeFile`, `tether.signing.storePassword`, `tether.signing.keyAlias`, `tether.signing.keyPassword`.
Both apps read them. Without them, release builds are signed with your machine's Android debug key.

Without the secrets the workflow still builds, but names the APKs `*-debug-signed.apk` (signed with the runner's
throw-away debug key) and says so at the top of the release notes.

### Switching an installed app to a new key

An app signed with one key cannot be updated with an APK signed with another: Android refuses the install. To move to
release-signed builds, uninstall the app first (`adb uninstall app.tether.android`). That deletes its data, **including
the pairing**: pair the iPhone again afterwards, as on first setup ([SETUP.md](SETUP.md)). Builds made
from source without a release key are signed with that machine's debug key, so the same applies when switching between
your own builds and release APKs.

## 2. Cut a release

Write the changes under `## Unreleased` in `CHANGELOG.md` as you go. Then, on a clean `main`:

```bash
tools/release.sh 1.2.0 --dry-run   # shows what would change
tools/release.sh 1.2.0             # quick tests, VERSION + iOS version, CHANGELOG section, commit, tag v1.2.0
git push origin main
git push origin v1.2.0             # starts the Release workflow
```

`tools/release.sh` never pushes; it prints the two push commands. It moves the Unreleased entries under
`## 1.2.0 — <date>` and leaves an empty `## Unreleased`. A version with a suffix (`1.2.0-rc.1`, `1.2.0-beta`) becomes a
pre-release. To rebuild an existing tag, run the workflow by hand (Actions › Release › Run workflow, with the tag); it
replaces the files of that release.

## 3. What the workflow produces

`.github/workflows/release.yml` checks that the tag equals `VERSION` (and that the iOS version and the CHANGELOG section
match), runs the Android unit tests, builds the Swift package, and attaches to the GitHub Release:

| File | What |
| --- | --- |
| `Tether-A54-<version>.apk` | the app for the Android phone with the SIMs |
| `Tether-Client-<version>.apk` | the Android client (a second Android phone) |
| `Tether-<version>-unsigned.ipa` | the iPhone / iPad app, Release build, **not signed**: for sideloading tools that re-sign it with your own Apple ID |
| `altstore.json` | AltStore / SideStore source for the IPA, made and checked by `tools/altstore-source.py` from the IPA's own Info.plist. Its stable address is `https://github.com/amirwmr/tether/releases/latest/download/altstore.json` (GitHub's "latest" points at the newest non-prerelease, so prereleases never reach users of the source) |
| `SHA256SUMS` | checksums of the files above |

The release notes are that version's CHANGELOG section plus install instructions. On every push to `main`, CI also keeps
debug-signed APKs of both apps as a workflow artifact (`tether-apks-debug-signed`, 30 days) for testers.

## 4. Verifying a download

```bash
sha256sum -c SHA256SUMS --ignore-missing          # macOS: shasum -a 256 -c SHA256SUMS --ignore-missing
gh attestation verify Tether-A54-1.2.0.apk --repo amirwmr/tether
```

The attestation proves that the file was built by this repository's Release workflow from the tagged commit. To check
the Android signing key of an APK: `apksigner verify --print-certs Tether-A54-1.2.0.apk`. Release APKs from 1.2.0 on
are signed with the project's release key, certificate SHA-256
`16f456947ccc85880d9fe3cd24775b884d2f21d0f39c4f10d3b3d6e64901057f`.
