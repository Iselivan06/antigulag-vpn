package com.antigulag.vpn

data class ProfileItem(
    val id: String,
    val name: String,
    val url: String,
    var isSelected: Boolean = false,
    val isPreset: Boolean = false
)