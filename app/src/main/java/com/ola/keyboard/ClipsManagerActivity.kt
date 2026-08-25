package com.ola.keyboard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.ola.keyboard.ui.ClipsManagerScreen
import com.ola.keyboard.ui.theme.OlaTheme

/** Full-screen clip browser/manager, opened from Settings > Clipboard > "Clips Manager". */
class ClipsManagerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OlaTheme {
                ClipsManagerScreen(
                    onBackClick = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
