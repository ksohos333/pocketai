package com.bpoppa.pocketai

import android.content.Context

/** Single owner of the ServerManager so the activity and the service share one process. */
object ServerHolder {
    @Volatile
    var manager: ServerManager? = null

    fun get(ctx: Context): ServerManager =
        manager ?: createServerManager(ctx.applicationContext).also { manager = it }
}
