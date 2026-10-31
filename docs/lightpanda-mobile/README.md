# Lightpanda → Android

Documentation for the Android port study of
[`lightpanda-io/browser`](https://github.com/lightpanda-io/browser).

| Document | Contents |
|---|---|
| [`REVERSE_ENGINEERING.md`](./REVERSE_ENGINEERING.md) | Source/build/dependency analysis, dynamic observations, Android feasibility matrix, licensing |
| [`PORT_PLAN.md`](./PORT_PLAN.md) | Hybrid architecture, phased native-port plan, effort/risk, acceptance criteria, security/legal checklists |
| [`../../android/README.md`](../../android/README.md) | The working Android shell: build, features, layout, testing status |

## TL;DR

- Lightpanda is a **headless** engine (Zig + V8 + libcurl + Rust HTML/renderer), no compositor.
- It has **no Android target**; release binaries need glibc, which Android does not have.
- Its PNG/PDF output is **text-only** — not a mobile browser UI.
- A literal full clone is a multi-year renderer project.
- This repo ships a **working Android browser shell** (WebView for pixels) with a
  Lightpanda-style reader extractor and a documented JNI/CDP path for an optional native core.

**Start here:** [`REVERSE_ENGINEERING.md`](./REVERSE_ENGINEERING.md) → [`PORT_PLAN.md`](./PORT_PLAN.md).

## App

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Screenshots

| Start page | Article | Reader mode |
|---|---|---|
| ![Start page](./screenshots/home.png) | ![Reader demo](./screenshots/reader-demo.png) | ![Reader mode](./screenshots/reader-mode.png) |
