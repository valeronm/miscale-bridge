package com.miscalebridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.miscalebridge.app.ui.MainScreen
import com.miscalebridge.app.ui.theme.MiScaleBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MiScaleApp
        setContent {
            MiScaleBridgeTheme {
                MainScreen(app)
            }
        }
    }
}
