package unicode.sinhala.keyboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import unicode.sinhala.com.R

/**
 * Renders every [FontStyle] as a single-column row - the sample word shown in that
 * style, so the user can see the look before picking it, mirroring the ClipboardAdapter
 * pattern (simple list, no headers needed since 13 styles fit on one screen with scroll).
 */
class FontStyleAdapter(
    private val onStyleClick: (FontStyle) -> Unit
) : RecyclerView.Adapter<FontStyleAdapter.StyleViewHolder>() {

    private var activeStyle: FontStyle = FontStyle.NONE
    private val styles = FontStyle.entries.toList()

    /** Called once when the panel opens (and again if the active style changes elsewhere)
     *  so the currently-active row shows a checkmark instead of the user having to guess. */
    fun setActiveStyle(style: FontStyle) {
        if (activeStyle == style) return
        activeStyle = style
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StyleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_font_style, parent, false)
        return StyleViewHolder(view)
    }

    override fun onBindViewHolder(holder: StyleViewHolder, position: Int) {
        val style = styles[position]
        holder.sample.text = style.sample
        holder.name.text = style.displayName
        holder.check.isVisible = style == activeStyle
        holder.itemView.setOnClickListener {
            activeStyle = style
            notifyDataSetChanged()
            onStyleClick(style)
        }
    }

    override fun getItemCount(): Int = styles.size

    class StyleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val sample: TextView = itemView.findViewById(R.id.font_style_sample)
        val name: TextView = itemView.findViewById(R.id.font_style_name)
        val check: ImageView = itemView.findViewById(R.id.font_style_check)
    }
}
