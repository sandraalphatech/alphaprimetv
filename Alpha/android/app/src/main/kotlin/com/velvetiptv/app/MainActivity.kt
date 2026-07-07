package com.velvetiptv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import com.velvetiptv.app.ui.navigation.AlphaPrimeNavigation
import com.velvetiptv.app.ui.theme.AlphaPrimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlphaPrimeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlphaPrimeNavigation()
                }
            }
        }
    }
}
