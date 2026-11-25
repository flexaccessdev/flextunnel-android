package dev.flexaccess.flextunnel

import android.Manifest
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
                // The session's notification needs POST_NOTIFICATIONS (Android
                // 13+). Asked on the first start; the session runs either way —
                // a denied permission only hides the notification.
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    manager.start()
                }
                FlextunnelRoot(manager = manager, onStart = {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        manager.start()
                    } else {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            }
        }
    }
}
