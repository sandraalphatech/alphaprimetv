package com.alphaprime.tv.data

data class M3UChannel(
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val type: ChannelType = ChannelType.TV
)

enum class ChannelType { TV, MOVIE, SERIES }
