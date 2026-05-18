package com.shimtraveling.ui.common

import android.content.Context
import android.content.Intent
import com.shimtraveling.ui.main.MainActivity

fun Context.openGuide() {
    startActivity(
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_GUIDE, true)
        }
    )
}

