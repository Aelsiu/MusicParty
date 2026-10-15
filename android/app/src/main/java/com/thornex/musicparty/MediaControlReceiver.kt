package com.thornex.musicparty

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification actions retain the owner that published the visible card. */
class MediaControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: return
        if (action !in listOf("play", "pause", "next")) return
        MediaSessionManager.dispatchNotification(action,
            intent.getStringExtra("context") ?: return,
            intent.getStringExtra("mode") ?: return,
            intent.getLongExtra("bridgeGeneration", -1L))
    }
}
