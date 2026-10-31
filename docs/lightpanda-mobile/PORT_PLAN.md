# Port plan: Lightpanda core on Android

**Status:** Phase 0 complete (working Android shell). Phases 1–4 are the realistic route to an optional native core.
**Related:** [`REVERSE_ENGINEERING.md`](./REVERSE_ENGINEERING.md)

---

## 1. Decision

Do **not** try to turn Lightpanda into the visible mobile browser. Its text-only renderer cannot display the real web, and building a compositor/CSS paint stack is a multi-year effort.

Use a **hybrid**:

| Layer | Technology | Why |
|---|---|---|
| Pixels, touch, scrolling, media, forms | Android WebView (Chromium) | Already correct, secure, hardware-accelerated, maintained |
| Reader / extraction / structured data | Lightpanda-style extractor (shipped) | Fast, no native build required |
| Automation / crawling / agent | Optional native Lightpanda core over loopback CDP | Reuses upstream engine and protocol |
| Tabs, URL bar, downloads, bookmarks, UX | Kotlin/Java app code (shipped) | Full control, no engine constraints |

Three candidate strategies:

| Strategy | Time to usable app | Risk | Verdict |
|---|---:|---|---|
| **A. WebView shell + extractor** | days (done) | Low | **Ship this** |
| **B. Hybrid + native Lightpanda core via JNI/CDP** | 2–3 months | High | Worth a spike |
| **C. Full native port with own renderer** | 18–36+ months | Extreme | Do not start |

The rest of this document is the plan for **B**, with **A** as the shipping baseline.

---

## 2. Target architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Android app process                                         │
│                                                             │
│  MainActivity / tabs / URL bar / downloads / settings       │
│        │                          │                         │
│        │ WebView (Chromium)        │ EngineService (future)  │
│        │ pixels + input            │   │                     │
│        │                          │   ├─ JNI: NativeCore    │
│        │                          │   │    start/stop       │
│        │                          │   └─ CDP client         │
│        │                          │        (loopback)       │
│        │                          ▼                         │
│        │                  liblightpanda_jni.so              │
│        │                  (Zig + V8 + Rust + curl)          │
│        │                          │                         │
│        │                  localhost / abstract Unix socket  │
└────────┴─────────────────────────────────────────────────────┘
```

Why CDP at the boundary instead of a wide JNI API?

- upstream already exposes a stable-ish protocol (`serve`), with `Page`, `Runtime`, `Network`, `DOM`, `Target` domains;
- the same core can run on a desktop/server for testing;
- JNI surface stays tiny (start/stop/version), reducing ABI and threading hazards;
- the Android UI can be developed against a desktop Lightpanda instance before V8/Android is ready.

The JNI contract is already declared in
`android/app/src/main/java/dev/rakib/pandamobile/engine/NativeCore.java`.

---

## 3. Phase 0 — Android shell (done)

**Deliverable:** `android/` APK, no external dependencies, minSdk 26, targetSdk 36.

Implemented:

- multi-tab strip with create/close/switch, title updates, active tab scroll;
- URL bar with search fallback, URL detection, IME "Go";
- back/forward/reload, progress, history state;
- desktop-site mode (UA + viewport injection);
- JavaScript toggle (persisted), DOM storage, third-party cookies, safe browsing;
- reader mode via `assets/reader.js` (readability-style scoring + sanitisation);
- bookmarks (SharedPreferences), share, downloads via `DownloadManager`;
- file chooser, fullscreen video, custom-scheme/intent handling;
- permission hardening: camera/mic denied, geolocation denied, mixed content compatibility;
- edge-to-edge insets, light/dark theme, adaptive layout.

**Exit criteria met:** debug APK builds; launcher activity present; reader extractor verified in headless Chromium; emulator smoke test recorded in `android/README.md`.

---

## 4. Phase 1 — Harden the shell (2–3 weeks)

| Task | Acceptance |
|---|---|
| Persist tabs/history across process death | Reopen restores URLs and active tab; no crash on restore |
| Find-in-page bar | `findAllAsync`, next/previous, match count |
| Per-site settings | JS/desktop/reader remembered per origin |
| Download UX | Progress/notification, open file, storage permission flow on API 26–28 |
| Security | Block `intent://` to non-exported components, safe browsing callbacks, certificate error UX |
| Accessibility | TalkBack labels, focus order, 48dp touch targets, font scaling |
| Privacy | Private tab (no cookies/history), clear-data screen, no telemetry |
| Tests | Instrumentation tests for tabs/navigation/reader; screenshot tests |
| CI | `.github/workflows/android.yml` build + lint + artifact |

No native code required. This phase makes the app genuinely usable before investing in the port.

---

## 5. Phase 2 — Native core build (4–8 weeks, high risk)

Goal: produce `liblightpanda_jni.so` for `arm64-v8a` (and `x86_64` for the emulator) and start a CDP endpoint.

### 5.1 Prerequisites

- Zig **0.17.0** (exact `minimum_zig_version` from `build.zig.zon`);
- Android NDK **r27+** (16 KB page support) and SDK platform 36;
- Rust stable + `aarch64-linux-android` / `x86_64-linux-android` targets;
- depot_tools/GN/ninja for V8;
- a Linux build host with ~32 GB RAM and ~100 GB disk (V8 is the long pole).

### 5.2 Work breakdown

1. **V8 for Android** (critical path)
   - `zig-v8-fork` currently has no Android branch in its GN args and publishes no Android prebuilt.
   - Build `v8_monolith` with GN using the NDK toolchain:
     ```text
     target_os="android"
     target_cpu="arm64"
     v8_monolithic=true
     v8_monolithic_for_shared_library=true
     v8_enable_sandbox=false          # verify against current V8 requirements
     is_component_build=false
     is_debug=false
     symbol_level=1
     android_ndk_root=<ndk>
     use_custom_libcxx=true
     ```
   - Package the archive with the naming `build.zig` expects
     (`libc_v8_<version>_android_<arch>.a`) or pass `-Dprebuilt_v8_path=...`.
   - **Exit test:** a trivial C++ binary calls `v8::V8::Initialize` on an Android emulator.

2. **Rust bridge and text renderer**
   - Add Android targets to `rustup`.
   - Configure Cargo linker per target:
     ```bash
     export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=\
       $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
     cargo build --release --target aarch64-linux-android
     ```
   - Verify `html5ever` parsing and `src/rust/render` linkage; the renderer is optional if the app only uses extraction/CDP.

3. **C dependencies**
   - Zig already vendors curl, nghttp2, zlib, brotli, zstd, SQLite, PCRE2, BoringSSL.
   - Compile with `-Dtarget=aarch64-linux-android`; audit `build.zig` for host-only assumptions (pkg-config, system paths, `/usr` includes).
   - Bundle a Mozilla CA PEM (~220 KB) and point curl at it; BoringSSL does not read Android's system store automatically.

4. **Zig core**
   - `zig build -Dtarget=aarch64-linux-android -Dcpu=baseline -Doptimize=ReleaseFast`.
   - Audit Bionic gaps: signals, core dumps, `/proc`, `getrandom`, `poll`/`epoll`, `fork`/`exec`, `getpwnam`, `dlopen`, thread names, `std.Io` assumptions.
   - Disable or stub: telemetry (`LIGHTPANDA_DISABLE_TELEMETRY`), crash core dumps (`LIGHTPANDA_DISABLE_CORE_DUMP`), updater, shell completion, TTY-dependent UI.

5. **Embedding ABI / JNI shim**
   - Add `src/android/main.zig` (or `c_api.zig`) exporting:
     ```c
     int32_t lp_android_start(const char* data_dir, int32_t port);
     void    lp_android_stop(void);
     const char* lp_android_version(void);
     ```
   - JNI wrapper maps `NativeCore.start/stop/version`.
   - Run the engine on a **dedicated thread** with its own event loop; never block the Android main thread.
   - One engine per process; Android may kill/restart the app, so persist cookies/storage under `filesDir`.

6. **Transport and authentication**
   - Prefer an **abstract Unix socket** (Linux `--unix-socket`) or loopback TCP bound to `127.0.0.1` with an unguessable token.
   - CDP has **no authentication**. Do not expose `0.0.0.0`. Verify the `Host`/token header and reject other apps.
   - Disable `/metrics`; disable MCP/agent HTTP unless needed.

7. **Packaging**
   - Place `liblightpanda_jni.so`, `libc++_shared.so` (if needed), and CA bundle in `jniLibs/`.
   - Verify 16 KB alignment: `llvm-readelf -l liblightpanda_jni.so | grep LOAD` (align `0x4000`).
   - Add `android:extractNativeLibs="false"` and `useLegacyPackaging=false`.
   - CI matrix: `arm64-v8a` required; `x86_64` optional for emulators.

**Exit criteria:** app starts the core, `GET /json/version` returns a version, and a CDP `Page.navigate` + `Runtime.evaluate` round-trip succeeds on an arm64 emulator/device.

---

## 6. Phase 3 — Integrate the core (2–3 weeks)

- Add `EngineService` (foreground service or app-scoped singleton) that starts/stops the core.
- Implement a minimal CDP client (WebSocket + JSON-RPC) in Java/Kotlin; no third-party dependency needed.
- Feature-flag the engine; fall back silently to the WebView path.
- First features to route through the core:
  1. **Reader/extraction**: `Runtime.evaluate` with the same `reader.js`, or a native extraction domain; compare quality/speed with the JS path.
  2. **Structured data**: `--dump structured_data` equivalent.
  3. **Automation recipes** (PandaScript) triggered by the user, not arbitrary remote code.
  4. **Background prefetch** of pages for offline reading.
- Do **not** use the core for visible rendering; keep WebView as the paint path.

**Exit criteria:** toggling the feature flag does not change the visible browsing experience; extraction works with the core off and on; crash in the core cannot crash the UI process.

---

## 7. Phase 4 — Productisation (2–4 weeks)

- Signing, Play Console listing, privacy policy, data-safety form.
- Target API compliance, 16 KB page compliance, arm64-only first release.
- Crash reporting with opt-in; no page content in logs.
- Update cadence for V8/curl CVEs; pin and rebuild monthly.
- Localisation, tablet/foldable layout, keyboard shortcuts, external keyboard.
- Licensing review: AGPL source offer or commercial license.

---

## 8. Phase 5 — Own renderer (optional research, not recommended)

If the product requires Lightpanda's DOM *and* its own pixels, the missing pieces are:

- CSS paint (backgrounds, borders, shadows, gradients, filters, transforms);
- images/media decode and compositing;
- scrolling, hit-testing, touch gestures, text selection, IME;
- accessibility tree, form controls, focus;
- GPU/software raster backend and damage tracking;
- security isolation (site isolation, sandboxing).

That is a browser-engine-scale program, not a port. Keep WebView for pixels.

---

## 9. Effort and risk summary

| Phase | Effort | Risk | Notes |
|---|---:|---|---|
| 0. Shell | done | Low | 42 KB APK, no dependencies |
| 1. Harden shell | 2–3 weeks | Low | Highest user value per week |
| 2. Native build | 4–8 weeks | **High** | V8 + Bionic + Rust + deps; unknown unknowns |
| 3. Integration | 2–3 weeks | Medium | CDP client, lifecycle, auth |
| 4. Productise | 2–4 weeks | Medium | Play, legal, updates |
| 5. Renderer | 18–36 months | Extreme | Not a port |

**Go/no-go for Phase 2:** only proceed if a V8 `v8_monolith` for `arm64-android` initialises on a device within the spike budget (2 weeks). If not, stop at Phase 1 + remote CDP (desktop/server Lightpanda) and keep the WebView shell.

---

## 10. Acceptance tests

**Shell**

- cold start < 1.5 s on a mid-range device; no ANR;
- 10 tabs, switch/close, no WebView leak (memory returns after close);
- reader mode on 20 real articles: no scripts, no ads, absolute links, readable typography;
- downloads complete and open; file inputs work; video enters/exits fullscreen;
- back stack behaves; process death restores tabs.

**Native core (Phase 2+)**

- `Page.navigate`, `Runtime.evaluate`, `Network.getCookies` over the authenticated endpoint;
- 100 sequential navigations without leak; RSS < 250 MB;
- core crash is contained; restart works;
- no listening socket reachable from another app;
- 16 KB-aligned `.so`; `arm64-v8a` only build passes Play pre-launch;
- AGPL source archive produced for the exact shipped binary.

---

## 11. Security and privacy checklist

- [ ] CDP bound to loopback/abstract socket, never `0.0.0.0`.
- [ ] Per-install random token; reject unauthenticated handshakes.
- [ ] `LIGHTPANDA_DISABLE_TELEMETRY=true`; audit outbound calls.
- [ ] Disable core dumps; no page content in crash logs.
- [ ] Clear cookies/storage on private-tab close.
- [ ] Certificate validation on; no `--insecure-disable-tls-host-verification`.
- [ ] Treat all fetched HTML/JS as untrusted; no `Runtime.evaluate` of remote strings.
- [ ] Keep WebView and native core updated; monitor V8/curl advisories.
- [ ] Document permissions and data flows for Play.

---

## 12. Legal checklist

- [ ] Choose AGPL compliance (publish app + core source, offer corresponding source) **or** commercial license.
- [ ] Keep upstream copyright notices and license text.
- [ ] Do not use Lightpanda marks/logo as the app identity without permission.
- [ ] Confirm third-party licenses (V8, curl, BoringSSL, SQLite, Rust crates) are compatible with distribution.
- [ ] Publish a privacy policy and terms.

---

## 13. Immediate next actions

1. Run the Phase 1 hardening list; ship a signed internal build.
2. Spike V8 Android init (2 weeks, timeboxed). Record exact GN args and binary sizes.
3. If the spike passes, build the C ABI + JNI shim and a CDP smoke test.
4. If the spike fails, keep the WebView shell and offer "remote Lightpanda" for extraction/automation.
