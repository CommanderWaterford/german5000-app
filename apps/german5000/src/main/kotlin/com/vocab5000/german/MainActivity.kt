package com.vocab5000.german

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val clockChanged = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as German5000App).container
        setContent {
            val changed by clockChanged
            German5000Root(container, clockChanged = changed)
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as German5000App).container
        lifecycleScope.launch {
            clockChanged.value = container.clockChangeDetector.check()
        }
        // Reconcile purchases on resume; offline or errors never revoke cached premium.
        container.appScope.launch { container.entitlements.restore() }
    }
}
