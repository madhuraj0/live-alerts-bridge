package com.livealert.bridge

import android.graphics.drawable.Drawable

data class AppInfoItem(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    var isBlacklisted: Boolean
)
