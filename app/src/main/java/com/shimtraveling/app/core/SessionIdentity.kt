package com.shimtraveling.core

import android.content.Context
import com.google.firebase.auth.FirebaseAuth


object SessionIdentity {

    private const val PREFS = "traveling_session"
    private const val KEY_ANON_ID = "anonymous_firebase_like_id"

    fun getLikeUserId(context: Context, auth: FirebaseAuth = FirebaseAuth.getInstance()): String {
        auth.currentUser?.uid?.let { return it }
        return getOrCreateAnonymousId(context.applicationContext)
    }

    fun getOrCreateAnonymousId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_ANON_ID, null)
        if (id.isNullOrBlank()) {
            id = "anon_${java.util.UUID.randomUUID()}"
            prefs.edit().putString(KEY_ANON_ID, id).apply()
        }
        return id
    }
}
