package com.velvetiptv.app.ui.screens.tv

import com.velvetiptv.app.data.M3UChannel

// Linha a apresentar no RecyclerView — ou uma categoria (group-title do M3U,
// ex.: "GLOBO CAPITAIS", "PREMIERE") ou um canal individual dentro dela.
sealed class ChannelRow {
    data class Category(val name: String, val count: Int, val locked: Boolean = false, val canLock: Boolean = true) : ChannelRow()
    data class Item(val channel: M3UChannel) : ChannelRow()
}

data class ChannelCategory(val name: String, val channels: List<M3UChannel>, val canLock: Boolean = true)

// Agrupa por group-title (categoria do M3U), preservando a ordem de
// primeira ocorrência — a mesma ordem em que a lista vem do fornecedor.
fun groupByCategory(channels: List<M3UChannel>): List<ChannelCategory> {
    val byName = LinkedHashMap<String, MutableList<M3UChannel>>()
    for (ch in channels) {
        val key = ch.group.ifBlank { "Sem categoria" }
        byName.getOrPut(key) { mutableListOf() }.add(ch)
    }
    return byName.map { (name, list) -> ChannelCategory(name, list) }
}
