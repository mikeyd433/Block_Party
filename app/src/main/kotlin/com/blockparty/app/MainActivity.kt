package com.blockparty.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.blockparty.app.ui.BlockPartyApp
import com.blockparty.app.ui.theme.BlockPartyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlockPartyTheme {
                BlockPartyApp()
            }
        }
    }
}
