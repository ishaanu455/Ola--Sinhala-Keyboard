package unicode.sinhala.keyboard

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import unicode.sinhala.keyboard.ui.ClipsManagerScreen
import unicode.sinhala.keyboard.ui.theme.UnicodeSinhalaTheme

/** Full-screen clip browser/manager, opened from Settings > Clipboard > "Clips Manager". */
class ClipsManagerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UnicodeSinhalaTheme {
                ClipsManagerScreen(
                    onBackClick = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
