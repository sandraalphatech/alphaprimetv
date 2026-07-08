package com.velvetiptv.app.ui.screens.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.velvetiptv.app.data.M3UChannel

class ChannelAdapter(
    private val onChannelClick      : (M3UChannel) -> Unit,
    private val onChannelDouble     : (M3UChannel) -> Unit,
    private val onChannelFocus      : (M3UChannel) -> Unit,
    private val onChannelLongClick  : (M3UChannel) -> Unit = {},
    private val onCategoryClick     : (ChannelRow.Category) -> Unit,
    private val onCategoryLockToggle: (ChannelRow.Category) -> Unit
) : ListAdapter<ChannelRow, RecyclerView.ViewHolder>(DIFF) {

    // Tracking do duplo clique em campos simples (fora do Compose) — imune a recomposição.
    private var lastClickMs  = 0L
    private var lastClickUrl = ""

    companion object {
        private const val TYPE_CATEGORY = 0
        private const val TYPE_ITEM     = 1

        private val DIFF = object : DiffUtil.ItemCallback<ChannelRow>() {
            override fun areItemsTheSame(a: ChannelRow, b: ChannelRow): Boolean = when {
                a is ChannelRow.Category && b is ChannelRow.Category -> a.name == b.name
                a is ChannelRow.Item     && b is ChannelRow.Item     -> a.channel.url == b.channel.url
                else -> false
            }
            override fun areContentsTheSame(a: ChannelRow, b: ChannelRow) = a == b
        }

        // Alpha Prime color palette
        private val C_ACCENT     = 0xFF7B35C4.toInt()
        private val C_BG_SEL     = 0x387B35C4.toInt()
        private val C_BG_FOCUS   = 0x24FFFFFF
        private val C_TEXT       = 0xFFE5E7EB.toInt()
        private val C_TEXT_DIM   = 0x52E5E7EB.toInt()
        private val C_DIVIDER    = 0x0AFFFFFF
        private val C_IND_FOCUS  = 0x80FFFFFF.toInt()
        private val C_CARD_BG    = 0xFF1a1a2e.toInt()
        private val C_CARD_FOCUS = 0xFF252540.toInt()
    }

    // Handler for D-pad focus debounce (Dream TV: muda canal só quando D-pad para)
    private val handler = Handler(Looper.getMainLooper())
    private var focusRunnable: Runnable? = null

    var favoriteUrls: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { i, row ->
                if (row is ChannelRow.Item && (row.channel.url in old || row.channel.url in value)) {
                    notifyItemChanged(i, PAYLOAD_FAV)
                }
            }
        }

    var selectedUrl: String = ""
        set(value) {
            if (field == value) return
            val old = field
            field = value
            // Actualização parcial — evita rebind completo de todos os itens.
            // Precisa do valor antigo para limpar o destaque do canal anterior;
            // comparar só com o novo valor deixava todos os canais já tocados
            // presos com o realce roxo.
            currentList.forEachIndexed { i, row ->
                if (row is ChannelRow.Item && (row.channel.url == old || row.channel.url == value)) {
                    notifyItemChanged(i, PAYLOAD_SEL)
                }
            }
        }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ChannelRow.Category -> TYPE_CATEGORY
        is ChannelRow.Item     -> TYPE_ITEM
    }

    // ─── ViewHolders ────────────────────────────────────────────────────────────

    class ItemVH(
        val indicator : View,
        val logo      : ImageView,
        val name      : TextView,
        val star      : TextView,
        root          : View
    ) : RecyclerView.ViewHolder(root)

    class CategoryVH(
        val card  : LinearLayout,
        val name  : TextView,
        val count : TextView,
        val lock  : TextView,
        root      : View
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val d   = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d + 0.5f).toInt()

        if (viewType == TYPE_CATEGORY) {
            // Cartão arredondado com nome à esquerda e contagem alinhada à direita —
            // mesmo estilo de lista de categorias (group-title) de outros players IPTV.
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, dp(48f)
                ).apply { setMargins(dp(8f), dp(4f), dp(8f), dp(4f)) }
                setPadding(dp(14f), 0, dp(14f), 0)
                isFocusable = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(C_CARD_BG)
                }
            }
            val name = TextView(ctx).apply {
                setTextColor(C_TEXT)
                textSize  = 13f
                typeface  = Typeface.DEFAULT_BOLD
                maxLines  = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val count = TextView(ctx).apply {
                setTextColor(C_ACCENT)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8f), 0, 0, 0)
            }
            // Cadeado — toca para bloquear/desbloquear esta categoria com senha.
            // É uma área de toque própria (isClickable), separada do resto do
            // cartão, para não entrar na categoria só por querer bloqueá-la.
            val lock = TextView(ctx).apply {
                textSize = 15f
                isClickable = true
                isFocusable = false
                setPadding(dp(10f), 0, 0, 0)
            }
            card.addView(name)
            card.addView(count)
            card.addView(lock)
            return CategoryVH(card, name, count, lock, card)
        }

        // Linha de canal — igual ao estilo anterior (indicador + logo + nome).
        val root = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            isFocusable  = true
            isClickable  = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
            gravity = Gravity.CENTER_VERTICAL
        }

        val indicator = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3f), dp(32f)).apply {
                marginEnd = dp(8f)
            }
            setBackgroundColor(Color.TRANSPARENT)
        }

        val logo = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f)).apply {
                marginEnd = dp(8f)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val name = TextView(ctx).apply {
            setTextColor(C_TEXT)
            textSize  = 12f
            maxLines  = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val star = TextView(ctx).apply {
            textSize    = 14f
            isClickable = true
            isFocusable = false
            setPadding(dp(6f), 0, dp(2f), 0)
        }

        row.addView(indicator)
        row.addView(logo)
        row.addView(name)
        row.addView(star)
        root.addView(row)

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(C_DIVIDER)
        })

        return ItemVH(indicator, logo, name, star, root)
    }

    // ─── Bind com suporte a payload (actualização parcial de selecção) ─────────

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (holder is ItemVH) {
            val row = getItem(position) as? ChannelRow.Item ?: return
            if (payloads.contains(PAYLOAD_SEL)) { applySelection(holder, row); return }
            if (payloads.contains(PAYLOAD_FAV)) { applyStar(holder, row.channel.url); return }
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ChannelRow.Category -> bindCategory(holder as CategoryVH, row)
            is ChannelRow.Item     -> bindItem(holder as ItemVH, row)
        }
    }

    private fun bindCategory(holder: CategoryVH, row: ChannelRow.Category) {
        holder.name.text  = row.name
        holder.count.text = "${row.count}"
        holder.lock.text  = if (row.locked) "🔒" else "🔓"
        holder.lock.alpha = if (row.locked) 1f else 0.35f
        holder.lock.visibility = if (row.canLock) View.VISIBLE else View.GONE
        holder.card.setOnClickListener { onCategoryClick(row) }
        holder.lock.setOnClickListener { if (row.canLock) onCategoryLockToggle(row) }
        holder.card.setOnFocusChangeListener { _, hasFocus ->
            (holder.card.background as? GradientDrawable)?.setColor(if (hasFocus) C_CARD_FOCUS else C_CARD_BG)
        }
    }

    private fun bindItem(holder: ItemVH, row: ChannelRow.Item) {
        val ch = row.channel
        holder.name.text = ch.name
        holder.name.setTypeface(null, if (ch.url == selectedUrl) Typeface.BOLD else Typeface.NORMAL)

        if (ch.logo.isNotBlank()) {
            holder.logo.load(ch.logo) {
                size(80, 80)
                scale(Scale.FIT)
                allowHardware(false)
            }
        } else {
            holder.logo.setImageDrawable(null)
        }

        applySelection(holder, row)
        applyStar(holder, ch.url)
        wireItemListeners(holder, ch)
    }

    private fun applyStar(holder: ItemVH, url: String) {
        val fav = url in favoriteUrls
        holder.star.text  = if (fav) "★" else "☆"
        holder.star.setTextColor(if (fav) 0xFFFFD700.toInt() else 0x60E5E7EB.toInt())
    }

    private fun wireItemListeners(holder: ItemVH, ch: M3UChannel) {
        holder.itemView.setOnClickListener {
            val now     = System.currentTimeMillis()
            val elapsed = now - lastClickMs
            val isDouble = lastClickUrl == ch.url && elapsed in 200L..700L
            lastClickMs  = now
            lastClickUrl = ch.url
            if (isDouble) onChannelDouble(ch) else onChannelClick(ch)
        }
        holder.itemView.setOnLongClickListener { onChannelLongClick(ch); true }
        holder.star.setOnClickListener { onChannelLongClick(ch) }

        // Foco D-pad: debounce 450 ms (como Dream TV — evita trocar canal em cada
        // item ao percorrer a lista rapidamente com o controlo remoto)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            applyFocusColor(holder, ch, hasFocus)
            if (hasFocus) {
                focusRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { onChannelFocus(ch) }
                focusRunnable = r
                handler.postDelayed(r, 450)
            } else {
                focusRunnable?.let { handler.removeCallbacks(it) }
            }
        }
    }

    // ─── Estilo de selecção / foco ─────────────────────────────────────────────

    private fun applySelection(holder: ItemVH, row: ChannelRow.Item) {
        val sel = row.channel.url == selectedUrl
        holder.itemView.setBackgroundColor(if (sel) C_BG_SEL else Color.TRANSPARENT)
        holder.indicator.setBackgroundColor(if (sel) C_ACCENT else Color.TRANSPARENT)
        holder.name.setTextColor(if (sel) Color.WHITE else C_TEXT)
        holder.name.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun applyFocusColor(holder: ItemVH, ch: M3UChannel, hasFocus: Boolean) {
        val sel = ch.url == selectedUrl
        holder.itemView.setBackgroundColor(
            when { sel -> C_BG_SEL; hasFocus -> C_BG_FOCUS; else -> Color.TRANSPARENT }
        )
        holder.indicator.setBackgroundColor(
            when { sel -> C_ACCENT; hasFocus -> C_IND_FOCUS; else -> Color.TRANSPARENT }
        )
    }

    // ─── Ciclo de vida RecyclerView ────────────────────────────────────────────

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ItemVH) {
            holder.logo.setImageDrawable(null)
            holder.itemView.setOnFocusChangeListener(null)
            holder.itemView.setOnClickListener(null)
            holder.star.setOnClickListener(null)
        }
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        focusRunnable?.let { handler.removeCallbacks(it) }
    }
}

private const val PAYLOAD_SEL = "sel"
private const val PAYLOAD_FAV = "fav"
