package com.foreverjukebox.app

import android.app.Application

class ForeverJukeboxApp : Application() {
    override fun onCreate() {
        // Install logging as early as possible so any throwable during startup —
        // fatal (CrashLogger) or recoverable (AppLog) — is captured.
        CrashLogger.install(this)
        AppLog.init(this)
        super.onCreate()
    }
}
