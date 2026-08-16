package com.mazevm.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazevm.android.ui.MazeRoot
import com.mazevm.android.ui.theme.MazeTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = MazeApp.from(this)

        setContent {
            val settings by app.prefs.state.collectAsStateWithLifecycle()
            MazeTheme(variant = settings.theme, accent = settings.accent) {
                MazeRoot(app)
            }
        }
    }
}
