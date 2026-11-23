# Bloom

Free, open-source Android photo editor with on-device AI (selection, masking, color, blur, sharpen, liquify, object eraser). Optional cloud tier for advanced AI.

License: GPL-3.0 (see `LICENSE`). Bundles LibRaw 0.22.3 (LGPL-2.1, `app/src/main/cpp/libraw/COPYRIGHT`) for camera raw files.

- Plan, milestones, features and decisions: [`docs/PLAN.md`](docs/PLAN.md)
- UI spec: [`design/UI.md`](design/UI.md), clickable mockup: [`design/mockup.html`](design/mockup.html)

## Build

Requires Android Studio (bundled JDK + Android SDK 37).

```
./gradlew assemblePlayDebug      # or assembleFdroidDebug
./gradlew testPlayDebugUnitTest
./gradlew connectedPlayDebugAndroidTest   # GPU tests, needs a device or emulator
```

Min Android 8.0 (API 26), OpenGL ES 3.0.
