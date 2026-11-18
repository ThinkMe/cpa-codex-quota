package com.neo.codexquota

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neo.codexquota.ui.QuotaScreen
import com.neo.codexquota.ui.QuotaTheme

class MainActivity : ComponentActivity() {
    private var accountId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        accountId = intent.getStringExtra("account_id")
        setContent { QuotaTheme { QuotaScreen(accountId, onDismissAccount = { accountId = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accountId = intent.getStringExtra("account_id")
    }
}
