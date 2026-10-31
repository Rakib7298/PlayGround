# PlayGround

<!-- omgithub:readme:start -->
## 🚀 Build, play, and remix with OMGithub

**Created using [OMGithub.com](https://omgithub.com).**

[![OMGithub](https://img.shields.io/badge/OMGithub-Open%20project-orange?style=for-the-badge)](https://omgithub.com/Rakib7298/PlayGround)
[![GitHub](https://img.shields.io/badge/GitHub-Source-181717?logo=github&style=for-the-badge)](https://github.com/Rakib7298/PlayGround)

- 🎮 [Open the project](https://omgithub.com/Rakib7298/PlayGround).
- ✨ [Remix this project](https://omgithub.com/?remix=Rakib7298%2FPlayGround).
- 💻 [Explore the source](https://github.com/Rakib7298/PlayGround).
- 🛠️ [Check build runs](https://github.com/Rakib7298/PlayGround/actions).
- 🐛 [Report an issue](https://github.com/Rakib7298/PlayGround/issues).
- 👤 [Explore the creator's projects](https://omgithub.com/Rakib7298).
- 🌍 [Create with OMGithub](https://omgithub.com).
<!-- omgithub:readme:end -->

## 📱 Panda Mobile (Android)

An independent Android browser shell built from the Lightpanda port study.

- **App:** [`android/`](android/) — build with `cd android && ./gradlew :app:assembleDebug`
- **Docs index + screenshots:** [`docs/lightpanda-mobile/`](docs/lightpanda-mobile/)
- **Reverse-engineering report:** [`docs/lightpanda-mobile/REVERSE_ENGINEERING.md`](docs/lightpanda-mobile/REVERSE_ENGINEERING.md)
- **Port plan:** [`docs/lightpanda-mobile/PORT_PLAN.md`](docs/lightpanda-mobile/PORT_PLAN.md)

Lightpanda is a headless engine with no graphical renderer and no native Android target, so this
app uses Android WebView for pixels and touch, ships a Lightpanda-style reader/extraction pipeline,
and keeps a documented JNI/CDP boundary for an optional native core. It is **not** a copy of
Lightpanda and is not affiliated with the upstream project.

