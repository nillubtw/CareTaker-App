package com.assistive.caretaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class EmergencyAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val alertType = intent.getStringExtra("ALERT_TYPE") ?: "EMERGENCY"

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {

                    Text(
                        text = "🚨 EMERGENCY 🚨",
                        style = MaterialTheme.typography.headlineLarge
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = alertType,
                        style = MaterialTheme.typography.headlineMedium
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { finish() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ACKNOWLEDGE")
                    }
                }
            }
        }
    }
}
