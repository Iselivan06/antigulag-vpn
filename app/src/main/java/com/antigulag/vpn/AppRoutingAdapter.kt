package com.antigulag.vpn

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isSelected: Boolean
)

class AppRoutingAdapter(
    private var list: List<AppItem>,
    private val onToggle: (AppItem, Boolean) -> Unit
) : RecyclerView.Adapter<AppRoutingAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivIcon: ImageView = v.findViewById(R.id.ivAppIcon)
        val tvName: TextView = v.findViewById(R.id.tvAppName)
        val tvPkg: TextView = v.findViewById(R.id.tvAppPkg)
        val cb: MaterialCheckBox = v.findViewById(R.id.cbAppSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_routing, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]
        holder.ivIcon.setImageDrawable(item.icon)
        holder.tvName.text = item.name
        holder.tvPkg.text = item.packageName

        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = item.isSelected

        holder.cb.setOnCheckedChangeListener { _, isChecked ->
            item.isSelected = isChecked
            onToggle(item, isChecked)
        }

        holder.itemView.setOnClickListener {
            holder.cb.toggle()
        }
    }

    override fun getItemCount() = list.size

    fun updateData(newList: List<AppItem>) {
        list = newList
        notifyDataSetChanged()
    }
}