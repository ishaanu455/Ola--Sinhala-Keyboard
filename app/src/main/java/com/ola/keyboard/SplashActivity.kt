package com.ola.keyboard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ola.keyboard.ui.theme.Ink1
import com.ola.keyboard.ui.theme.Light1
import com.ola.keyboard.ui.theme.Light2
import com.ola.keyboard.ui.theme.OlaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Launcher activity. Shows the brand mark + app name + version on a dark
 * background (matching the reference splash design) for a short beat, then
 * hands off to MainActivity. This is a plain content screen, not the
 * system SplashScreen API - it's what actually renders now, since the
 * previous "blank black screen on open" bug was this screen either not
 * existing or not being the launcher, leaving nothing drawn while
 * MainActivity's Compose content was still being set up.
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OlaTheme(darkTheme = true) {
                SplashScreen()
            }
        }
    }

    @Composable
    private fun SplashScreen() {
        LaunchedEffect(Unit) {
            // Fire-and-forget, throttled to once/24h inside UpdateChecker itself -
            // runs in the background and never delays the hand-off to MainActivity
            // below. Its only effect is writing Prefs.updateAvailable, which the
            // keyboard's settings-icon badge and Settings > About both read later.
            launch { UpdateChecker.checkForUpdate(this@SplashActivity) }

            // Long enough for the brand mark to actually register, short
            // enough that it doesn't feel like a delay.
            delay(900)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Ink1
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_ola_logo_mark),
                    contentDescription = stringResource(id = R.string.app_name),
                    modifier = Modifier.size(96.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(id = R.string.app_name),
                    color = Light1,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(id = R.string.splash_version, BuildConfig.VERSION_NAME),
                    color = Light2,
                    fontSize = 14.sp
                )
            }
        }
    }
}
