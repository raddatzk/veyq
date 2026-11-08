package me.raddatz.veyq.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) devLogin(intent) // a recreation (rotation, dark mode) keeps its session
        setContent { VeyqTheme { AppRoot(model) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        devLogin(intent)
    }

    private fun devLogin(intent: android.content.Intent?) {
        DevLogin.from(intent)?.let { model.signIn(it.server, it.email, it.password, it.secretKey) }
    }
}
