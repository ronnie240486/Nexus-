package com.nexustv.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CatalogAdapter(
    private val imageLoader: ImageLoader,
    private val fallbackLogo: (CatalogEntry) -> Int,
    private val onSelected: (CatalogEntry) -> Unit,
    private val onClicked: (CatalogEntry) -> Unit,
    private val onLongClicked: (CatalogEntry) -> Unit,
) : RecyclerView.Adapter<CatalogAdapter.Holder>() {
    private var items: List<CatalogEntry> = emptyList()
    private var selectedKey: String? = null
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
    }

    fun submit(items: List<CatalogEntry>, selectedKey: String?) {
        this.items = items
        this.selectedKey = selectedKey
        notifyDataSetChanged()
    }

    fun positionOf(key: String?): Int = if (key == null) -1 else items.indexOfFirst { it.key == key }

    // Atualiza qual item mostra a borda de selecionado sem recarregar a
    // lista inteira (isso é o que fazia a borda quase nunca aparecer --
    // antes só atualizava em submit(), que roda raramente por design, pra
    // não bagunçar a posição de rolagem). Só chama notifyItemChanged para
    // posições que já estão de fato visíveis: notificar uma posição fora de
    // tela (ex.: posição 0, longe depois de rolar rápido) fazia o
    // RecyclerView/LinearLayoutManager mexer no "ponto de ancoragem" do
    // layout e arrastar a rolagem de volta pra lá sozinho.
    fun setSelectedKey(key: String?) {
        if (key == selectedKey) return
        val oldPosition = positionOf(selectedKey)
        selectedKey = key
        val recyclerView = attachedRecyclerView
        if (oldPosition >= 0 && recyclerView?.findViewHolderForAdapterPosition(oldPosition) != null) {
            notifyItemChanged(oldPosition)
        }
        val newPosition = positionOf(key)
        if (newPosition >= 0 && recyclerView?.findViewHolderForAdapterPosition(newPosition) != null) {
            notifyItemChanged(newPosition)
        }
    }

    fun append(items: List<CatalogEntry>) {
        if (items.isEmpty()) return
        val start = this.items.size
        this.items = this.items + items
        notifyItemRangeInserted(start, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(12, 10, 12, 10)
            layoutParams = RecyclerView.LayoutParams(-1, 92).apply { setMargins(0, 5, 0, 5) }
        }
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(68, 68).apply { setMargins(0, 0, 12, 0) }
        }
        val number = TextView(context).apply {
            setTextColor(Color.rgb(111, 120, 149))
            textSize = 11f
            gravity = Gravity.CENTER
            setSingleLine(true)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(34, -1)
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        val badge = TextView(context).apply {
            setTextColor(Color.rgb(244, 123, 156))
            textSize = 10f
            setPadding(8, 5, 8, 5)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        row.addView(logo)
        row.addView(number)
        row.addView(title)
        row.addView(badge)
        return Holder(row, logo, number, title, badge)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.number.text = String.format("%02d", position + 1)
        holder.title.text = if (item.kind == MediaKind.SERIES && item.seriesGroup.isNotBlank()) item.seriesGroup else item.name
        holder.badge.text = item.quality.ifBlank {
            when (item.kind) {
                MediaKind.LIVE -> "LIVE"
                MediaKind.MOVIE -> "FILME"
                MediaKind.SERIES -> "SÉRIE"
            }
        }
        holder.row.tag = item.key
        imageLoader.load(item.logoUrl, holder.logo, fallbackLogo(item))
        fun paint(focused: Boolean) {
            val isSelected = item.key == selectedKey
            holder.row.background = layered(
                fill = if (focused) 0x333FE7EF else 0x00111629,
                strokeColor = if (isSelected) 0xFFFFFFFF else 0x00000000,
                strokeWidthPx = if (isSelected) 3 else 0,
            )
        }
        paint(holder.row.hasFocus())
        holder.row.setOnFocusChangeListener { _, hasFocus ->
            paint(hasFocus)
            if (hasFocus) onSelected(item)
        }
        holder.row.setOnClickListener { onClicked(item) }
        holder.row.isLongClickable = true
        holder.row.setOnLongClickListener { onLongClicked(item); true }
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        val row: View,
        val logo: ImageView,
        val number: TextView,
        val title: TextView,
        val badge: TextView,
    ) : RecyclerView.ViewHolder(row)

    private fun rounded(color: Long, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb((color shr 24 and 0xFF).toInt(), (color shr 16 and 0xFF).toInt(), (color shr 8 and 0xFF).toInt(), (color and 0xFF).toInt()))
        cornerRadius = radius
    }

    private fun layered(fill: Long, strokeColor: Long, strokeWidthPx: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb((fill shr 24 and 0xFF).toInt(), (fill shr 16 and 0xFF).toInt(), (fill shr 8 and 0xFF).toInt(), (fill and 0xFF).toInt()))
        cornerRadius = 10f
        if (strokeWidthPx > 0) {
            setStroke(strokeWidthPx, Color.argb((strokeColor shr 24 and 0xFF).toInt(), (strokeColor shr 16 and 0xFF).toInt(), (strokeColor shr 8 and 0xFF).toInt(), (strokeColor and 0xFF).toInt()))
        }
    }
}
