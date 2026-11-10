package dev.steamshelf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.steamshelf.ui.AppNavHost
import dev.steamshelf.ui.theme.SteamShelfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SteamShelfApp).container
        setContent {
            SteamShelfTheme {
                AppNavHost(container)
            }
        }
    }
}
