package com.velvetiptv.app.ui.screens.movies

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.velvetiptv.app.data.ChannelType
import com.velvetiptv.app.ui.screens.vod.VodGridScreen

@Composable
fun MoviesScreen(navController: NavController? = null) {
    VodGridScreen(navController = navController, type = ChannelType.MOVIE, title = "Filmes")
}
