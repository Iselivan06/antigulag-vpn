package com.antigulag.vpn

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

data class RoutingRuleItem(
    val key: String,
    val title: String,
    val targetDesc: String,
    var isEnabled: Boolean
)

class RoutingRuleAdapter(
    private val rules: List<RoutingRuleItem>,
    private val onToggle: (RoutingRuleItem, Boolean) -> Unit
) : RecyclerView.Adapter<RoutingRuleAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvRuleTitle)
        val tvTarget: TextView = v.findViewById(R.id.tvRuleTarget)
        val switchRule: MaterialSwitch = v.findViewById(R.id.switchRule)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_routing_rule, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = rules[position]
        holder.tvTitle.text = item.title
        holder.tvTarget.text = item.targetDesc

        holder.switchRule.setOnCheckedChangeListener(null)
        holder.switchRule.isChecked = item.isEnabled

        holder.switchRule.setOnCheckedChangeListener { _, isChecked ->
            item.isEnabled = isChecked
            onToggle(item, isChecked)
        }

        holder.itemView.setOnClickListener {
            holder.switchRule.toggle()
        }
    }

    override fun getItemCount(): Int = rules.size
}