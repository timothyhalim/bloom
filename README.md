# Bloom

Free, open-source Android photo editor with on-device AI (selection, masking, color, blur, sharpen, liquify, object eraser). Optional cloud tier for advanced AI.

License: GPL-3.0 (see `LICENSE`).

## Build

Requires Android Studio (bundled JDK + Android SDK 35).

```
./gradlew assemblePlayDebug      # or assembleFdroidDebug
./gradlew testPlayDebugUnitTest
```

Min Android 8.0 (API 26), OpenGL ES 3.0.
