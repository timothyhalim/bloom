# Releasing Bloom (Play internal testing)

Steps only you can do. Commands run from the project root with `JAVA_HOME` set to Android Studio's JBR.

## 1. Create the upload key (once)

```sh
keytool -genkeypair -v -keystore bloom-upload.jks -alias bloom -keyalg RSA -keysize 4096 -validity 10000
```

Keep `bloom-upload.jks` and its passwords outside the repo's history (a password manager plus one offline backup). `*.jks`, `*.keystore` and `keystore.properties` are gitignored; never commit them. With Play App Signing (the default) this is only the upload key: if it is lost, Play support can reset it.

## 2. Point the build at it

Create `keystore.properties` in the project root:

```properties
storeFile=bloom-upload.jks
storePassword=...
keyAlias=bloom
keyPassword=...
```

`storeFile` is relative to the project root (an absolute path works too). Without this file, release builds come out unsigned.

## 3. Build

```sh
./gradlew :app:bundlePlayRelease
```

Output: `app/build/outputs/bundle/playRelease/app-play-release.aab`. Release builds are minified (R8); the AAB carries the R8 mapping, so Play shows readable stack traces. Keep `app/build/outputs/mapping/playRelease/mapping.txt` for each version you upload too: crash reports shared by users through the app are obfuscated and need it (`retrace mapping.txt report.txt`, in the SDK's `cmdline-tools`).

Before each upload raise `versionCode` (and `versionName`) in `app/build.gradle.kts`; Play refuses a code it has seen.

## 4. Play Console (first time)

1. Create a developer account (one-time fee) at play.google.com/console.
2. **Create app**: name "Bloom", default language, App, Free; accept the declarations.
3. **Testing > Internal testing > Create new release**: accept Play App Signing, upload the AAB, add release notes, save, then **Review release > Start rollout**.
4. **Testers** tab: create an email list with your testers (up to 100) and share the opt-in link.
5. Fill in the **App content** section (Policy > App content):
   - **Privacy policy**: a public URL with the text in `docs/store/listing.md` (for example the file on GitHub, or GitHub Pages).
   - **Ads**: No.
   - **App access**: all functionality is available without special access.
   - **Content rating**: questionnaire, category "Utility, productivity, communication or other"; answer No to violence, sexual content, language, controlled substances, gambling; No to user interaction or sharing between users (export goes through the system share sheet, not a Bloom service); No to location sharing; No to digital purchases (until M9). Expected result: Everyone / PEGI 3.
   - **Target audience**: 13+ (do not select under-13 groups: that brings the Families policy).
   - **Data safety** (answers below).
   - **Government apps / Financial features / Health**: No.
6. **Store listing**: short and full description from `docs/store/listing.md`, app icon `app/src/main/ic_launcher-playstore.png` (512 px), a 1024 x 500 feature graphic, and at least 2 phone screenshots.

## 5. Data safety answers

- Does your app collect or share any of the required user data types? **No.**
  - Photos are edited on the device and only leave it when the user exports or shares them themselves (user-initiated, through other apps: not collection).
  - AI model downloads send no user data.
  - Crash reports are shown in a dialog; only if the user taps Share does the system share sheet open with the text, and the user picks the app and recipient. Nothing reaches a server of ours. Play treats data the user sends through another app by their own action as not collected; if you later add a server for reports, change this answer (Crash logs, Diagnostics; optional; not shared).
- Is all user data encrypted in transit? Answer only appears if you collect data; with "No" above it is skipped.
- Account creation: none. Data deletion: not applicable (nothing collected).

## 6. Each later release

1. Raise `versionCode` / `versionName`.
2. `./gradlew :app:bundlePlayRelease`
3. Keep the mapping file.
4. Internal testing > Create new release > upload > rollout.

## F-Droid flavor

`./gradlew :app:assembleFdroidRelease` builds an unsigned APK; F-Droid builds and signs from source itself. Nothing to upload by hand.
