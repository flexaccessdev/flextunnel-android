package dev.flexaccess.flextunnel

import android.app.Application

class FlextunnelApplication : Application() {
    lateinit var session: SessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        FlextunnelNative.init(this)
        session = SessionManager(this)
    }
}
