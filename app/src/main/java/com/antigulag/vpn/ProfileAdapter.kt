package com.antigulag.vpn

import android.graphics.Color
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Collections

class ProfileAdapter(
    private var profiles: List<ProfileItem>,
    private val onSelect: (ProfileItem) -> Unit,
    private val onDelete: (ProfileItem) -> Unit,
    private val onEdit: (ProfileItem) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

    private val pings = mutableMapOf<String, Long>()

    class ProfileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardProfile)
        val rbSelect: RadioButton = view.findViewById(R.id.rbSelect)
        val tvName: TextView = view.findViewById(R.id.tvProfileName)
        val tvHost: TextView = view.findViewById(R.id.tvProfileHost)
        val btnEdit: MaterialButton? = view.findViewById(R.id.btnEdit)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteProfile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        return ProfileViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val item = profiles[position]
        holder.tvName.text = item.name
        holder.rbSelect.isChecked = item.isSelected

        // Блокируем самостоятельную обработку клика радиокнопкой,
        // чтобы касание передавалось всей карточке и чекпоинт не переключался при работающем VPN
        holder.rbSelect.isClickable = false
        holder.rbSelect.isFocusable = false

        val host = try {
            val uri = Uri.parse(item.url)
            val security = uri.getQueryParameter("security")?.lowercase()
            if (security == "reality") {
                "IP: ${uri.host ?: "relay"}"
            } else {
                val sni = uri.getQueryParameter("sni") ?: uri.host ?: "relay"
                "Узел: $sni"
            }
        } catch (_: Exception) {
            "relay"
        }

        val ping = pings[item.id]
        if (ping != null) {
            val (pingStr, colorHex) = when {
                ping == -2L -> " • Замер..." to "#888888"
                ping in 1..250 -> " • ${ping} мс" to "#4CAF50"
                ping > 250 -> " • ${ping} мс" to "#FFB74D"
                else -> " • Таймаут" to "#FF5449"
            }
            val fullText = "$host$pingStr"
            val spannable = SpannableString(fullText)
            val start = host.length
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor(colorHex)),
                start,
                fullText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.tvHost.text = spannable
        } else {
            holder.tvHost.text = host
        }

        // Клик обрабатывает только карточка
        holder.card.setOnClickListener { onSelect(item) }

        if (item.isPreset) {
            holder.btnDelete.visibility = View.GONE
            holder.btnEdit?.visibility = View.GONE
        } else {
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.setOnClickListener { onDelete(item) }

            holder.btnEdit?.visibility = View.VISIBLE
            holder.btnEdit?.setOnClickListener { onEdit(item) }
        }
    }

    override fun getItemCount(): Int = profiles.size

    fun updateData(newProfiles: List<ProfileItem>) {
        profiles = newProfiles
        notifyDataSetChanged()
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val mutable = profiles.toMutableList()
        Collections.swap(mutable, fromPosition, toPosition)
        profiles = mutable
        notifyItemMoved(fromPosition, toPosition)
    }

    fun getCurrentProfiles(): List<ProfileItem> = profiles

    fun setProfilePing(profileId: String, pingMs: Long) {
        pings[profileId] = pingMs
        val idx = profiles.indexOfFirst { it.id == profileId }
        if (idx != -1) {
            notifyItemChanged(idx)
        }
    }

    fun setAllPingsLoading() {
        profiles.forEach { pings[it.id] = -2L }
        notifyDataSetChanged()
    }
}