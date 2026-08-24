package unicode.sinhala.keyboard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import unicode.sinhala.keyboard.ui.PredictionManagerScreen
import unicode.sinhala.keyboard.ui.theme.UnicodeSinhalaTheme

class PredictionManagerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UnicodeSinhalaTheme {
                PredictionManagerScreen(
                    onBackClick = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
