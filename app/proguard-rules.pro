# Called by name from native code (cpp/raw.cpp). Native methods themselves are kept by the default rules.
-keepclassmembers class dev.bloom.editor.Raw {
    private static android.graphics.Bitmap alloc(int, int);
}
-keep interface dev.bloom.editor.Raw$Progress { *; }

