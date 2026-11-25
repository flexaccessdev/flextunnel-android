package dev.flexaccess.flextunnel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.flexaccess.flextunnel.ui.FlextunnelRoot
import dev.flexaccess.flextunnel.ui.FlextunnelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val manager = SessionManager.get(this)
        setContent {
            FlextunnelTheme {
                // Asked before a start, both optional — the session runs either
                // way: POST_NOTIFICATIONS (Android 13+) only shows its
                // notification; ACCESS_LOCAL_NETWORK (Android 17, targetSdk 37)
                // allows mDNS, direct LAN paths and SOCKS5 to LAN hosts —
                // without it everything goes through relays.
                val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    manager.start()
                }
                FlextunnelRoot(manager = manager, onStart = {
                    val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) manager.start() else askPermissions.launch(missing.toTypedArray())
                })
            }
        }
    }

    private companion object {
        /** ACCESS_LOCAL_NETWORK exists from Android 17; earlier, INTERNET covers the local network. */
        val PERMISSIONS = buildList {
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}
