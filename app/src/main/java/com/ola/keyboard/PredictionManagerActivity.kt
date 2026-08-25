package com.ola.keyboard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.ola.keyboard.ui.PredictionManagerScreen
import com.ola.keyboard.ui.theme.OlaTheme

class PredictionManagerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OlaTheme {
                PredictionManagerScreen(
                    onBackClick = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
