# Building and installing

Both apps are built from source on a Mac. Nothing here needs a paid developer account.

## 1. Tools

| Tool | Version | Get it |
| --- | --- | --- |
| Xcode | 16 or later (tested with 27) | Mac App Store, then open it once to install the iOS platform |
| XcodeGen | 2.40 or later | `brew install xcodegen` |
| JDK | 17 | `brew install openjdk@17`, or Android Studio's bundled JDK |
| Android SDK | platform 36, build tools | Android Studio, or the command-line tools (`sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`) |
| adb | any recent | part of the SDK's platform-tools |

If `xcode-select -p` points at the Command Line Tools instead of Xcode, either run
`sudo xcode-select -s /Applications/Xcode.app` or prefix commands with
`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.

## 2. Local configuration (never committed)

| File | From | What goes in it |
| --- | --- | --- |
| `ios/Config/Local.xcconfig` | `ios/Config/Local.xcconfig.example` | Your Apple team id, a bundle id unique to you, the app's display name |
| `android/local.properties` | `android/local.properties.example` | `sdk.dir` (Android Studio writes it), optionally a Maven mirror and your own release key |
| `tools/local.env` | `tools/local.env.example` | Optional device ids, only needed when several phones are connected |

All three are listed in `.gitignore`.

## 3. Android app

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f app.tether.android   # optional: compile ahead of time, smoother audio
```

- `JAVA_HOME` must point at a JDK 17 (for Homebrew: `export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home`).
- The release build is signed with your local debug key unless you set `tether.signing.*` in `local.properties`.
  Keep using the same key: Android refuses to update an app signed with a different one.
- Prebuilt, signed APKs (and an unsigned iPhone IPA) are attached to each GitHub Release; the version comes from
  `VERSION` at the repository root. Release keys and cutting a release: [RELEASING.md](RELEASING.md).
- If `dl.google.com` is not reachable from your network, set `tether.googleMavenMirror=<url>` in
  `local.properties` (or `TETHER_GOOGLE_MAVEN_MIRROR` in the environment).
- The shell helper (`android/helper`) is built into the same APK; there is nothing else to install.

Enable USB debugging on the phone first (Settings › About phone › tap Build number seven times, then Developer
options › USB debugging).

### The Android client (a second Android phone)

```bash
cd android
./gradlew :client:testDebugUnitTest :client:assembleRelease
adb -s <the client phone> install -r client/build/outputs/apk/release/client-release.apk
```

It shares the protocol and codec code with the app above (`android/core`) and is signed the same way. Application
id `app.tether.client`, Android 10 or later. See [ANDROID-CLIENT.md](ANDROID-CLIENT.md).

## 4. iPhone / iPad app

```bash
cp ios/Config/Local.xcconfig.example ios/Config/Local.xcconfig
# edit it: TETHER_TEAM_ID, TETHER_BUNDLE_ID, TETHER_DISPLAY_NAME
cd ios && xcodegen generate
```

- **Team id**: Xcode › Settings › Accounts › your Apple account › the team's id (10 characters). With a free account
  the team is called "Your Name (Personal Team)".
- **Bundle id**: anything unique, for example `com.yourname.tether`. A free account cannot use a bundle id someone
  else has registered.
- **Display name**: the name under the icon (default "Cellular").

Then either open `ios/Tether.xcodeproj` in Xcode, pick your device and press Run, or use the script, which also
renews the signature:

```bash
tools/reinstall-ios.sh            # build, sign and install on the connected iPhone / iPad
tools/reinstall-ios.sh --force    # also request a fresh signing profile now
```

The first time, the device must trust your Mac and you must allow the developer certificate on it: Settings ›
General › VPN & Device Management › your Apple account › Trust. iOS 16 and later also need Developer Mode
(Settings › Privacy & Security › Developer Mode).

### Free vs paid Apple account

| | Free account | Paid developer program |
| --- | --- | --- |
| Signature valid for | 7 days: run `tools/reinstall-ios.sh` again before it ends (data and pairing are kept) | 1 year |
| Sender pictures in message notifications (Communication Notifications entitlement) | Not allowed: the script builds without it automatically | Yes |
| Default calling app on iOS 18.2+ (`com.apple.developer.calling-app`, same build variant) | Not allowed: built without it | Yes |
| Everything else | Same | Same |

The Link tab shows how many days the current signature has left.

### Build without signing (to check that it compiles)

```bash
cd ios && xcodegen generate
xcodebuild -project Tether.xcodeproj -scheme Tether -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO -skipMacroValidation build
```

## 5. Tests

```bash
(cd ios/TetherCore && ./test.sh)                      # Swift: protocol, crypto, framing, playout, parsing
(cd android && ./gradlew :app:testDebugUnitTest)      # Kotlin: the same protocol vectors, lists, link security
(cd android && ./gradlew :client:testDebugUnitTest)   # Android client: vectors, and its whole stack against a simulated Android phone
```

Both implementations check themselves against `protocol/vectors.json` byte for byte.

## 6. The simulator

The iOS app runs in the simulator with demo data (no Bluetooth there):

```bash
cd ios && xcodebuild -project Tether.xcodeproj -scheme Tether \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build-sim CODE_SIGNING_ALLOWED=NO -skipMacroValidation build
xcrun simctl install booted build-sim/Build/Products/Debug-iphonesimulator/Tether.app
xcrun simctl launch booted <your bundle id> -TetherDemo -TetherDemoMessages
```

Other demo screens: `-TetherDemoThread`, `-TetherDemoRich`, `-TetherDemoContacts`, `-TetherDemoRecents`,
`-TetherDemoCall`, `-TetherDemoDialing`, `-TetherDemoNotify`, `-TetherDemoVPN`, `-TetherDemoPair`.
