package com.alphaprime.tv.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.alphaprime.tv.R
import com.alphaprime.tv.data.M3UChannel

class ChannelAdapter(
    private val onClick: (M3UChannel) -> Unit,
    private val onFocus: (M3UChannel) -> Unit
) : ListAdapter<M3UChannel, ChannelAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<M3UChannel>() {
            override fun areItemsTheSame(a: M3UChannel, b: M3UChannel) = a.url == b.url
            override fun areContentsTheSame(a: M3UChannel, b: M3UChannel) = a == b
        }
        private val C_ACCENT    = 0xFF7B35C4.toInt()
        private val C_BG_SEL    = 0x387B35C4.toInt()
        private val C_BG_FOCUS  = 0x24FFFFFF
        private val C_TEXT      = 0xFFE5E7EB.toInt()
        private val C_IND_FOCUS = 0x80FFFFFF.toInt()
        private const val PAYLOAD_SEL = "sel"
    }

    // Handler para debounce D-pad (igual ao Dream TV — não troca canal em cada item)
    private val handler = Handler(Looper.getMainLooper())
    private var focusJob: Runnable? = null

    var selectedUrl = ""
        set(v) {
            if (field == v) return
            val old = field; field = v
            currentList.forEachIndexed { i, ch ->
                if (ch.url == old || ch.url == v) notifyItemChanged(i, PAYLOAD_SEL)
            }
        }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val indicator: View    = view.findViewById(R.id.indicator)
        val logo: ImageView    = view.findViewById(R.id.iv_logo)
        val name: TextView     = view.findViewById(R.id.tv_name)
        val group: TextView    = view.findViewById(R.id.tv_group)
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_SEL)) { refreshSel(h, getItem(pos)); return }
        onBindViewHolder(h, pos)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = getItem(pos)
        h.name.text = ch.name
        h.group.text = ch.group
        h.group.visibility = if (ch.group.isBlank()) View.GONE else View.VISIBLE

        if (ch.logo.isNotBlank()) {
            h.logo.load(ch.logo) {
                size(80, 80)
                scale(Scale.FIT)
                allowHardware(false)    // evita crash no RecyclerView em certas ROMs
            }
        } else {
            h.logo.setImageDrawable(null)
        }

        refreshSel(h, ch)
        wireListeners(h, ch)
    }

    private fun wireListeners(h: VH, ch: M3UChannel) {
        h.itemView.setOnClickListener { onClick(ch) }

        // Debounce 450ms — Dream TV pattern: muda canal só quando D-pad para
        h.itemView.setOnFocusChangeListener { _, hasFocus ->
            applyFocus(h, ch, hasFocus)
            if (hasFocus) {
                focusJob?.let { handler.removeCallbacks(it) }
                val r = Runnable { onFocus(ch) }
                focusJob = r
                handler.postDelayed(r, 450)
            } else {
                focusJob?.let { handler.removeCallbacks(it) }
            }
        }
    }

    private fun refreshSel(h: VH, ch: M3UChannel) {
        val sel = ch.url == selectedUrl
        h.itemView.setBackgroundColor(if (sel) C_BG_SEL else Color.TRANSPARENT)
        h.indicator.setBackgroundColor(if (sel) C_ACCENT else Color.TRANSPARENT)
        h.name.setTextColor(if (sel) Color.WHITE else C_TEXT)
        h.name.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun applyFocus(h: VH, ch: M3UChannel, hasFocus: Boolean) {
        val sel = ch.url == selectedUrl
        h.itemView.setBackgroundColor(when { sel -> C_BG_SEL; hasFocus -> C_BG_FOCUS; else -> Color.TRANSPARENT })
        h.indicator.setBackgroundColor(when { sel -> C_ACCENT; hasFocus -> C_IND_FOCUS; else -> Color.TRANSPARENT })
    }

    override fun onViewRecycled(h: VH) {
        super.onViewRecycled(h)
        h.logo.setImageDrawable(null)
        h.itemView.setOnFocusChangeListener(null)
        h.itemView.setOnClickListener(null)
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        focusJob?.let { handler.removeCallbacks(it) }
    }
}
