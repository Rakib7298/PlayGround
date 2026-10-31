# Reverse-engineering report: Lightpanda → Android

**Subject:** [`lightpanda-io/browser`](https://github.com/lightpanda-io/browser)
**Snapshot analysed:** commit `56980afdec3ad177072e7a180cb05da838bc1744` (2026-10-31)
**Binary exercised:** `1.1.0-nightly.11326+948d77378` (linux x86_64)
**Method:** source-tree and build-graph analysis, static dependency inspection, `ldd`/`file` on the release binary, and dynamic CLI runs against a local test page. No proprietary binary was involved.

---

## 1. Executive summary

Lightpanda is **not a graphical browser that can be rebuilt as an Android app by swapping the UI**. It is a headless, server-oriented browsing engine:

- written in **Zig 0.17**, with Rust bridges for HTML parsing and text rendering;
- it has a DOM, CSS cascade/layout, JavaScript via **V8**, networking via **libcurl**, CDP and WebDriver BiDi servers;
- it has **no compositor or interactive paint path**, and its own README says "No graphical rendering engine";
- its PNG/PDF output is explicitly a **text-only rendering** (confirmed in this study);
- it has **no native Android target** and the published Linux binaries require **glibc**, which Android's Bionic libc does not provide.

A literal "full clone for Android" would therefore require two separate multi-year projects:

1. porting the engine to Bionic/ARM64 and building V8 for Android, and
2. writing a real graphical renderer, input/compositor, and mobile browser UI.

That is not a realistic first deliverable. This repository instead contains a **working Android browser shell** plus a **concrete, staged port plan**. The shell uses Android's WebView (Chromium) for pixels and touch, keeps the rendering engine behind a documented boundary, and already implements a Lightpanda-style content-extraction ("reader") pipeline. A future build can attach a native Lightpanda core over JNI/CDP without redesigning the UI.

---

## 2. What the upstream project actually contains

| Area | Files | Lines (Zig) | Role |
|---|---:|---:|---|
| `src/browser` | 532 | 183,807 | Browser, Page, Frame, Session, DOM/Web APIs, CSS, layout, render tree, dumps |
| `src/browser/webapi` | 425 | 114,404 | DOM/HTML/CSS/JS-facing Web API surface |
| `src/browser/js` | 33 | 14,099 | V8 platform, snapshots, bindings |
| `src/browser/css` | 7 | 3,899 | Parser, cascade, selectors, values |
| `src/browser/parser` | 2 | 1,493 | HTML integration (html5ever bridge) |
| `src/network` | 23 | 20,300 | Loader, cache, cookies, interception, adblock, robots |
| `src/server` | 45 | 40,254 | CDP + WebDriver BiDi + HTTP/WS plumbing |
| `src/server/cdp` | 28 | 25,755 | Chrome DevTools Protocol domains |
| `src/agent` | 18 | 7,855 | LLM-driven agent mode |
| `src/mcp` | 7 | 4,326 | MCP server (stdio/HTTP) |
| `src/storage` | 2 | 744 | SQLite-backed storage |
| **Total `src`** | **685** | **295,615** | Plus ~4,300 lines of Rust |

Key non-Zig dependencies (`build.zig.zon`):

- `lightpanda-io/zig-v8-fork` (V8 15.5.x)
- `lightpanda-io/boringssl-zig`
- curl 8.23.0, nghttp2 1.70.0, zlib 1.3.2, brotli, zstd 1.5.7
- SQLite 3.53.2, PCRE2, isocline, `zenai`, `translate-c`
- Rust `html5ever` bridge and a text renderer (`src/rust`)

### 2.1 Execution model

```
main.zig
  └─ Config.parseArgs
      └─ App.init (network, V8 platform, snapshot, telemetry, watchdog)
          ├─ fetch   → Browser/Page → Frame.navigate → dump
          ├─ serve   → CDP / BiDi WebSocket server  (default :9222)
          ├─ mcp     → MCP stdio or HTTP server      (optional :9223)
          ├─ agent   → LLM tool loop + PandaScript
          └─ run     → replay a recorded PandaScript
```

The core is a **single-process, event-loop-oriented engine**, not a library with a stable embedding ABI. `App` owns global state (allocator, network, V8 platform, fonts, telemetry, signal handling), and the executable entry point is `src/main.zig`.

### 2.2 Modes and output formats

Commands: `fetch`, `serve`, `agent`, `mcp`, `run`, `version`, `completion`, `help`.

`fetch --dump` supports: `html`, `markdown`, `pdf`, `png`, `semantic_tree`, `semantic_tree_text`, `structured_data`.

The PNG/PDF path is a **text renderer**: it shapes and places glyphs and simple blocks. It does not paint CSS backgrounds, images, shadows, borders or a compositor surface. In this study a test page with `.ad { color: red }`, an image, a blockquote and a styled code block produced:

- a 1920×817 PNG;
- CSS text size/weight and simple code background;
- no image (alt text only), no `.ad` color, no blockquote border, no interactive surface.

That is sufficient for automation and document extraction, **not** for a browser a human scrolls and taps.

---

## 3. Dynamic observations

```text
$ ./lightpanda version
1.1.0-nightly.11326+948d77378

$ ldd ./lightpanda
linux-vdso.so.1
libm.so.6        => /lib/x86_64-linux-gnu/libm.so.6
libc.so.6        => /lib/x86_64-linux-gnu/libc.so.6
/lib64/ld-linux-x86-64.so.2
```

- The release binary is dynamically linked only to glibc/libm; V8 and the other dependencies are statically linked.
- On Android there is no `/lib64/ld-linux-*` and no glibc, so the stock binary cannot run — this matches the upstream README's Android/Termux note.
- `fetch --dump markdown` converts the **whole DOM**, including navigation, ads and footer (unlike a readability extractor). The app's reader mode deliberately does readability-style pruning instead.
- `fetch --dump png/pdf` works out of the box with built-in fonts; output is text-only.

---

## 4. Android feasibility matrix

| # | Blocker | Severity | Detail | Mitigation |
|---|---|---|---|---|
| 1 | **No graphical renderer** | Critical | No compositor, input surface, scrolling, CSS painting, images | Use WebView/GeckoView for pixels; use Lightpanda only for extraction/automation |
| 2 | **No V8 Android build** | Critical | Prebuilt V8 assets exist for linux aarch64/x86_64, macOS, iOS simulator only | Build V8 with GN + NDK (`target_os="android"`, monolithic), or use a JS engine with Android support |
| 3 | **glibc vs Bionic** | High | Release binary links `ld-linux`; Bionic lacks it | Cross-compile from source with `aarch64-linux-android` |
| 4 | **Rust components** | Medium | `html5ever` bridge + text renderer must be built for Android | `cargo --target aarch64-linux-android`, NDK linker, `libc++_shared`/static |
| 5 | **Native deps** | Medium | curl, OpenSSL/BoringSSL, nghttp2, SQLite, zlib, brotli, zstd, PCRE2 | Zig cross-build + CA bundle strategy |
| 6 | **No embedding ABI** | Medium | Only `main()`; globals, signals, telemetry, `std.process` | Add a C ABI/JNI shim; run one engine per process; loopback CDP |
| 7 | **Output is not pixels** | High | PNG/PDF are text-only; no screenshot of real CSS | Do not try to use `Page.captureScreenshot` as a mobile UI |
| 8 | **AGPL-3.0** | Business | Linking/redistributing creates source-disclosure duties, including network use | Publish app source under AGPL, or buy a commercial license |
| 9 | **Play requirements** | Medium | 16 KB page size, target API, arm64, signing, privacy | NDK r27+, 16 KB-aligned libs, CI checks |
| 10 | **ABI/CPU surface** | Medium | Main target is arm64-v8a; 32-bit adds cost | Ship arm64-v8a only first |

**Conclusion:** the viable Android architecture is a **hybrid**:

```
Android UI (tabs, URL bar, gestures, downloads)
        │
        ├── WebView (Chromium)  ← pixels, touch, video, forms       [works today]
        │
        └── optional native Lightpanda core (JNI → loopback CDP)    [phase 2+]
                └── extraction, automation, reader, agent, structured data
```

This is not a compromise on the UI: Chromium is the only component in the stack that already renders the real mobile web correctly. Lightpanda's value on mobile is its extraction/automation core, not its pixels.

---

## 5. Licensing

Lightpanda is **AGPL-3.0** (with a CLA for contributions). The repository also ships `LICENSING.md` describing commercial licensing.

- Copying or linking Lightpanda code into the Android app makes the combined work subject to AGPL-3.0, including the obligation to offer corresponding source to users interacting with it over a network.
- The shell in this repository is original code and does **not** include Lightpanda source. If you later add the native core, decide explicitly between AGPL publication and a commercial license before shipping.
- The upstream name/logo are trademarks; this app is labelled "Panda Mobile" and states that it is independent and unofficial.

---

## 6. What was built here

| Path | Purpose |
|---|---|
| `android/` | Dependency-free Android app (Java, minSdk 26, targetSdk 36) |
| `android/app/src/main/java/.../MainActivity.java` | Browser shell: tabs, URL bar, navigation, downloads, file chooser, fullscreen video, permissions |
| `android/app/src/main/assets/reader.js` | Readability-style extractor (paragraph scoring, tag whitelist, URL absolutisation, boilerplate removal) |
| `android/app/src/main/java/.../engine/NativeCore.java` | Documented JNI contract for a future Lightpanda core |
| `docs/lightpanda-mobile/PORT_PLAN.md` | Staged native-port plan, effort/risk, acceptance criteria |
| `.github/workflows/android.yml` | CI build of the debug APK |

**Verified in this study**

- `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`, APK ≈42 KB.
- `aapt dump badging` → package `dev.rakib.pandamobile`, minSdk 26, targetSdk 36, launcher activity present.
- Reader extractor tested in headless Chromium against a synthetic article: nav/ads/comments/footer removed; relative links and images absolutised; `<br>`, blockquote and lists preserved; duplicate title/byline removed.
- Emulator smoke test: see `android/README.md` for the recorded result.

---

## 7. Reproduction commands

```bash
# Upstream snapshot
git clone --depth 1 https://github.com/lightpanda-io/browser.git
git -C browser rev-parse HEAD

# Release binary inspection
curl -L -o lightpanda \
  https://github.com/lightpanda-io/browser/releases/download/nightly/lightpanda-x86_64-linux
chmod +x lightpanda
./lightpanda version
ldd ./lightpanda

# Dynamic dump against a local page
python3 -m http.server 8123 --bind 127.0.0.1 &
./lightpanda fetch --dump markdown http://127.0.0.1:8123/article.html
./lightpanda fetch --dump png      http://127.0.0.1:8123/article.html > shot.png
./lightpanda fetch --dump pdf      http://127.0.0.1:8123/article.html > shot.pdf

# Android app
cd android
./gradlew :app:assembleDebug
```
