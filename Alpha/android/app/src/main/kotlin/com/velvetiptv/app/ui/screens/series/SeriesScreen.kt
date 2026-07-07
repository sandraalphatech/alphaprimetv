package com.velvetiptv.app.ui.screens.series

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.velvetiptv.app.data.ChannelType
import com.velvetiptv.app.ui.screens.vod.VodGridScreen

@Composable
fun SeriesScreen(navController: NavController? = null) {
    VodGridScreen(navController = navController, type = ChannelType.SERIES, title = "Séries")
}
