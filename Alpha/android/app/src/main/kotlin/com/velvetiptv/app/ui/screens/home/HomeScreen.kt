package com.velvetiptv.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.velvetiptv.app.ui.theme.SurfaceDark

data class Channel(
    val id: String,
    val name: String,
    val logo: String,
    val category: String
)

@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        AsyncImage(
            model = channel.logo,
            contentDescription = channel.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentScale = ContentScale.Crop
        )
        Text(
            text = channel.name,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

fun getMockChannels(): List<Channel> {
    return listOf(
        Channel("1", "Globo", "https://via.placeholder.com/150", "TV Aberta"),
        Channel("2", "SBT", "https://via.placeholder.com/150", "TV Aberta"),
        Channel("3", "Record", "https://via.placeholder.com/150", "TV Aberta"),
        Channel("4", "Band", "https://via.placeholder.com/150", "TV Aberta"),
        Channel("5", "HBO", "https://via.placeholder.com/150", "Filmes"),
        Channel("6", "Netflix", "https://via.placeholder.com/150", "Series"),
        Channel("7", "ESPN", "https://via.placeholder.com/150", "Esportes"),
        Channel("8", "TNT", "https://via.placeholder.com/150", "Filmes"),
    )
}
