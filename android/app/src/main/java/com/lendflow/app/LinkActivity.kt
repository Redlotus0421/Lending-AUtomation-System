package com.lendflow.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Links waiting to be opened by [MainActivity] (kept in memory only, so they are never replayed). */
object PendingLink {
    @Volatile var uri: android.net.Uri? = null
    fun take(): android.net.Uri? = uri.also { uri = null }
}

/**
 * Receives https://<site>/sign/<token> and lendflow:// links and hands them to [MainActivity].
 * The link is passed in memory, not in MainActivity's launch intent, so reopening the app later
 * (from recents or after Android stopped it) never re-opens an old link.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PendingLink.uri = intent?.data
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
