# Panda Mobile

An independent, dependency-free Android browser shell built as the shipping baseline for the
[Lightpanda Android port study](../docs/lightpanda-mobile/REVERSE_ENGINEERING.md).

- **Package:** `dev.rakib.pandamobile`
- **Min / target SDK:** 26 / 36
- **Language:** Java (no Kotlin, no AndroidX, no third-party libraries)
- **Rendering:** Android WebView (Chromium)
- **Engine boundary:** optional `NativeCore` JNI contract for a future Lightpanda core

> Lightpanda is a **headless** engine with no graphical renderer and no native Android target.
> This app is **not** a copy of Lightpanda. It is an Android browser shell plus a staged plan for
> attaching the Lightpanda core. See [`docs/lightpanda-mobile/`](../docs/lightpanda-mobile/).

---

## Build

Requirements: JDK 17+, Android SDK platform 36, build-tools 36.

```bash
cd android
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install on a device/emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.rakib.pandamobile/.MainActivity
```

The Gradle wrapper is pinned to Gradle 8.13 and Android Gradle Plugin 8.13.0
(Gradle 9.6+ removed an internal API AGP 8.x still uses).

---

## Features

- Multi-tab strip: new/close/switch, live titles, active-tab scroll, restore after process death.
- URL bar: search fallback (DuckDuckGo), URL detection, keyboard "Go".
- Back / forward / reload, progress, history state, external `http(s)` intents.
- Desktop-site mode: desktop UA + `viewport` injection, persisted.
- JavaScript toggle, DOM storage, third-party cookies, Safe Browsing, mixed-content compatibility.
- **Reader mode**: readability-style scoring, boilerplate removal, tag whitelist, URL
  absolutisation, duplicate title/byline removal, visible-byline preference, light/dark typography.
  The source article URL is kept for the URL bar, bookmarks, sharing and tab restore.
- Bookmarks (SharedPreferences), share, downloads (`DownloadManager`), file chooser.
- Fullscreen video, custom-scheme and `intent://` handling, safe external-app launching.
- Privacy defaults: camera/mic requests denied, geolocation denied, no telemetry.
- Edge-to-edge insets, light/dark theme, no external runtime permissions on modern Android.

### Reader extractor

`app/src/main/assets/reader.js` is injected into the page and returns JSON:

```json
{"title":"…","byline":"…","html":"…","url":"…","site":"…","chars":1234}
```

It scores paragraph containers (Readability-style), picks the best subtree, removes
`nav/aside/footer/header/forms/ads/comments`, whitelists readable tags, strips event handlers
and classes, and converts relative links/images to absolute URLs. It is disabled when JavaScript
is off.

Verified in headless Chromium against a synthetic article: nav/ads/comments/footer removed,
links/images absolutised, `<br>`/blockquote/lists preserved, duplicate heading/byline removed.

---

## Project layout

```
android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── home.html              # start page
│   │   └── reader.js              # extraction pipeline
│   ├── java/dev/rakib/pandamobile/
│   │   ├── MainActivity.java      # tabs, navigation, menus, downloads, chrome client
│   │   └── engine/NativeCore.java # JNI contract for a future Lightpanda core
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values*/               # light/dark theme, strings, colors
│       └── drawable/              # URL bar shape, adaptive panda icon
├── build.gradle
├── settings.gradle
└── gradlew
```

The UI is deliberately plain `Activity` + `WebView` + programmatic tab chips. This keeps the APK
small and makes the WebView/engine boundary easy to see.

---

## Engine boundary

`engine/NativeCore.java` declares:

```java
static native int  start(String dataDir, int preferredPort); // bound loopback port or -1
static native void stop();
static native String version();
```

`isAvailable()` catches `UnsatisfiedLinkError`, so the app runs unchanged when no native library
is present. A future build drops `liblightpanda_jni.so` into `app/src/main/jniLibs/arm64-v8a/`
and talks CDP over an authenticated loopback/abstract Unix socket. See
[`PORT_PLAN.md`](../docs/lightpanda-mobile/PORT_PLAN.md) for the full contract, build steps and
security requirements.

**Licensing:** linking Lightpanda (AGPL-3.0) into this app makes the combined work subject to
AGPL source-disclosure obligations unless a commercial license is obtained. This build contains
no Lightpanda code.

---

## Testing status

| Check | Result |
|---|---|
| `./gradlew :app:assembleDebug` | ✅ `BUILD SUCCESSFUL` |
| APK size | ≈42 KB |
| `aapt dump badging` | ✅ package/minSdk/targetSdk/launcher correct |
| Reader extractor (headless Chromium) | ✅ boilerplate removed, URLs absolutised, dedupe works |
| Byline priority (headless Chromium) | ✅ visible byline beats site-wide `meta[name=author]` |
| Emulator smoke test | see below |

### Emulator smoke test

Recorded with Android 35 (Google APIs, x86_64) on KVM:

- install, cold launch, start page renders;
- address bar navigation to an `https://` page;
- tab create/switch/close;
- reader mode extraction (nav/ad/footer removed, byline retained once);
- process death + relaunch restores the reader's **source URL**, not `about:blank`;
- dark-mode launch.

Screenshots: [`home.png`](../docs/lightpanda-mobile/screenshots/home.png),
[`reader-demo.png`](../docs/lightpanda-mobile/screenshots/reader-demo.png),
[`reader-mode.png`](../docs/lightpanda-mobile/screenshots/reader-mode.png).

---

## Known limits

- WebView, not Lightpanda, paints and handles touch. That is intentional.
- No tab syncing, extensions, incognito mode, find-in-page, or per-site settings yet.
- Reader mode is heuristic; paywalled/JS-rendered articles may extract poorly.
- Downloads rely on the system `DownloadManager`; `blob:`/`data:` downloads are not supported.
- Camera/microphone web APIs are denied.
- No native Lightpanda core is bundled; the JNI methods are a contract, not an implementation.
- No release signing or Play Store packaging yet.
