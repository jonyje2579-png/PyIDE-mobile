package com.pyide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))

        val root = File(filesDir, "project").apply { mkdirs() }
        val site = File(filesDir, "site-packages").apply { mkdirs() }
        Backend.init(root.absolutePath, site.absolutePath)

        if (root.listFiles().isNullOrEmpty()) {
            File(root, "main.py").writeText(
                "name = input(\"Как тебя зовут? \")\n" +
                    "for i in range(3):\n" +
                    "    print(\"Привет,\", name, i)\n"
            )
        }
        val ide = Ide(root)
        ide.refresh()
        (ide.files.firstOrNull { it.name == "main.py" } ?: ide.files.firstOrNull())
            ?.let { ide.open(it) }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { IdeScreen(ide) }
        }
    }
}
