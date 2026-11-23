package dev.bloom.editor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.bloom.editor.ui.BloomTheme
import dev.bloom.editor.ui.EditorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        val photoView = PhotoView(this)
        lifecycle.addObserver(photoView)
        setContent { BloomTheme { EditorScreen(photoView) } }
    }
}
