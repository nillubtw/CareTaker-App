package com.assistive.caretaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.assistive.caretaker.ui.theme.CaretakerAppTheme
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import java.text.SimpleDateFormat
import java.util.*

fun formatTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

fun formatAlertType(type: String): String {
    val formatted = type.split("_").joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercaseChar() }
    }
    
    // Add emojis based on alert type
    val emoji = when (type) {
        "FALL_DETECTED", "PROLONGED_INACTIVITY" -> "⚠️🆘"
        else -> "🚨"
    }
    
    return "$emoji $formatted"
}

fun getAlertIcon(type: String): ImageVector {
    return when (type) {
        "FALL_DETECTED" -> Icons.Default.Warning
        "PROLONGED_INACTIVITY" -> Icons.Default.Info
        "LONG_HUM", "SHORT_HUM" -> Icons.Default.Phone
        "GESTURE_LEFT", "GESTURE_RIGHT" -> Icons.Default.Notifications
        else -> Icons.Default.Notifications
    }
}

class MainActivity : ComponentActivity() {

    private var activeAlerts by mutableStateOf<List<AlertData>>(emptyList())
    private var alertHistory by mutableStateOf<List<AlertData>>(emptyList())
    private var showHistory by mutableStateOf(false)
    private var showProfile by mutableStateOf(false)
    private val notifiedAlertIds = mutableSetOf<String>()

    private val notifiedAlertKeys = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle notification click - open to dashboard (alerts are already loaded via Firebase listener)
        val alertKey = intent.getStringExtra("ALERT_KEY")
        val alertType = intent.getStringExtra("ALERT_TYPE")
        if (alertKey != null || alertType != null) {
            Log.d("NOTIFICATION", "Opened from notification - Alert: $alertType, Key: $alertKey")
            // Dashboard will automatically show the alert since Firebase listener is active
        }

        /* 🔹 FCM TOKEN (Optional – for future push use) */
        FirebaseMessaging.getInstance().token.addOnSuccessListener {
            Log.d("FCM_TOKEN", it)
        }

        /* 🔹 NOTIFICATION CHANNEL */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "EMERGENCY_ALERTS",
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical emergency alerts"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        /* 🔹 FIREBASE LISTENER */
        val alertRef = FirebaseDatabase.getInstance().getReference("alerts")

        alertRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                val active = mutableListOf<AlertData>()
                val history = mutableListOf<AlertData>()

                snapshot.children.forEach { snap ->
                    val alert = snap.getValue(AlertData::class.java) ?: return@forEach

                    alert.key = snap.key ?: ""   // ✅ inject Firebase key manually

                    history.add(alert)

                    if (!alert.acknowledged) {
                        active.add(alert)

                        if (!notifiedAlertIds.contains(alert.key)) {
                            showEmergencyNotification(alert)
                            notifiedAlertIds.add(alert.key)
                        }
                    }
                }

                activeAlerts = active.sortedByDescending { it.timestamp }
                alertHistory = history.sortedByDescending { it.timestamp }

                Log.d("ALERT", "Active=${activeAlerts.size}, History=${alertHistory.size}")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ALERT", error.message)
            }
        })

        /* 🔹 UI */
        setContent {
            CaretakerAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background
                    ) { padding ->
                        when {
                            showProfile -> ProfileScreen { showProfile = false }
                            showHistory -> AlertHistoryScreen(alertHistory) { showHistory = false }
                            else -> DashboardScreen(
                            alerts = activeAlerts,
                            onAcknowledge = ::acknowledgeAlert,
                            onViewHistory = { showHistory = true },
                                onViewProfile = { showProfile = true },
                            modifier = Modifier.padding(padding)
                        )
                        }
                    }
                }
            }
        }
    }

    /* 🔹 ACKNOWLEDGE — BULLETPROOF */
    private fun acknowledgeAlert(alert: AlertData) {
        if (alert.key.isEmpty()) return

        // Cancel the notification to stop alarm sound
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(alert.key.hashCode())

        // Update Firebase
        FirebaseDatabase.getInstance()
            .getReference("alerts")
            .child(alert.key)
            .child("acknowledged")
            .setValue(true)
    }


    /* 🔹 LOCAL NOTIFICATION */
    private fun showEmergencyNotification(alert: AlertData) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // More urgent notification for fall detection (UI enhancement only - no logic change)
        val isFallAlert = alert.type == "FALL_DETECTED"
        val isEmergencyAlert = alert.type == "FALL_DETECTED" || alert.type == "PROLONGED_INACTIVITY"
        val title = if (isEmergencyAlert) {
            val alertName = alert.type.split("_").joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
            "⚠️🆘 $alertName"
        } else {
            "🚨 Emergency Alert"
        }
        val body = if (isFallAlert) "Immediate attention required!" else alert.type.ifBlank { "Emergency detected" }

        // Create intent to open MainActivity when notification is clicked
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALERT_KEY", alert.key)
            putExtra("ALERT_TYPE", alert.type)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            alert.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "EMERGENCY_ALERTS")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(alert.key.hashCode(), notification)
    }
}

/* ================= UI ================= */

@Composable
fun DashboardScreen(
    alerts: List<AlertData>,
    onAcknowledge: (AlertData) -> Unit,
    onViewHistory: () -> Unit,
    onViewProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        // Header Section with Modern Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Caretaker Dashboard",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (alerts.isEmpty())
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "${alerts.size}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (alerts.isEmpty())
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "active alert${if (alerts.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }

                // Profile Button with Modern Style
                IconButton(
                    onClick = onViewProfile,
                    modifier = Modifier.size(56.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        if (alerts.isEmpty()) {
            // Empty State - Modern Design
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "All Clear",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No active alerts at this time.\nEverything looks good!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
                    )
                }
            }
        } else {
            // Alert Cards
            alerts.forEachIndexed { index, alert ->
                AlertCard(
                    alert = alert,
                    onAcknowledge = { onAcknowledge(alert) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // History Button - Modern Style
        OutlinedButton(
            onClick = onViewHistory,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                1.5.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            ),
            contentPadding = PaddingValues(vertical = 16.dp, horizontal = 20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.CenterVertically),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "View Alert History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
fun AlertCard(
    alert: AlertData,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFallAlert = alert.type == "FALL_DETECTED"
    val isInactivityAlert = alert.type == "PROLONGED_INACTIVITY"

    val cardColor = when {
        isFallAlert -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        isInactivityAlert -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surface
    }

    val iconColor = when {
        isFallAlert -> MaterialTheme.colorScheme.error
        isInactivityAlert -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    val borderColor = when {
        isFallAlert -> MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
        isInactivityAlert -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Alert Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = iconColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = getAlertIcon(alert.type),
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatAlertType(alert.type),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .align(Alignment.CenterVertically),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = formatTimestamp(alert.timestamp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Acknowledge Button - Modern Style
            Button(
                onClick = onAcknowledge,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFallAlert)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 0.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.CenterVertically)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Acknowledge",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}

@Composable
fun AlertHistoryScreen(alerts: List<AlertData>, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        // Header - Modern Card Design
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Alert History",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${alerts.size} total alert${if (alerts.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        if (alerts.isEmpty()) {
            // Empty History State - Modern Design
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
        Spacer(Modifier.height(24.dp))
                    Text(
                        text = "No Alert History",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Alert history will appear here\nwhen alerts are generated.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
                    )
                }
            }
        } else {
            // Group alerts by date
            val groupedAlerts = alerts.groupBy { formatDate(it.timestamp) }

            groupedAlerts.forEach { (date, dateAlerts) ->
                // Date Header - Modern Style
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

                // Alert Items for this date
                dateAlerts.forEach { alert ->
                    HistoryAlertItem(
                        alert = alert,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryAlertItem(
    alert: AlertData,
    modifier: Modifier = Modifier
) {
    val isFallAlert = alert.type == "FALL_DETECTED"
    val isAcknowledged = alert.acknowledged

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji Icon
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "🚨",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatAlertType(alert.type).substringAfter(" "), // Remove emoji, keep only text
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatTimestamp(alert.timestamp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // Status Badge - Modern Style
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isAcknowledged)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAcknowledged) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isAcknowledged)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isAcknowledged) "Done" else "Pending",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAcknowledged)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    var caretakerName by remember { mutableStateOf("Caretaker") }
    var mobileNumber by remember { mutableStateOf("") }
    var isEditingName by remember { mutableStateOf(false) }
    var isEditingMobile by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        // Header - Modern Card Design
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Caretaker Profile",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(Modifier.height(28.dp))
        
        // Profile Card - Modern Design
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Profile Icon - Modern Design
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(120.dp),
                    tonalElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Profile",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(64.dp)
                        )
                    }
        }

        Spacer(Modifier.height(24.dp))
                
                // Editable Name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEditingName) {
                        OutlinedTextField(
                            value = caretakerName,
                            onValueChange = { caretakerName = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            shape = RoundedCornerShape(14.dp),
                            trailingIcon = {
                                IconButton(onClick = { isEditingName = false }) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Save",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    } else {
                        Text(
                            text = caretakerName,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { isEditingName = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Name",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Mobile Number
                if (isEditingMobile) {
                    OutlinedTextField(
                        value = mobileNumber,
                        onValueChange = { mobileNumber = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mobile Number") },
                        placeholder = { Text("Enter mobile number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            Row {
                                if (mobileNumber.isNotEmpty()) {
                                    IconButton(onClick = { mobileNumber = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = { isEditingMobile = false }) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Save",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (mobileNumber.isEmpty()) {
                            TextButton(onClick = { isEditingMobile = true }) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Add Mobile Number",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = mobileNumber,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { isEditingMobile = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Mobile",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Information Section - Modern Design
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(Modifier.height(16.dp))
                
                ProfileInfoItem(
                    icon = Icons.Default.Phone,
                    label = "Device Status",
                    value = "Connected"
                )
                
                Spacer(Modifier.height(12.dp))
                
                ProfileInfoItem(
                    icon = Icons.Default.Notifications,
                    label = "Alert Notifications",
                    value = "Enabled"
                )
                
                Spacer(Modifier.height(12.dp))
                
                ProfileInfoItem(
                    icon = Icons.Default.Info,
                    label = "App Version",
                    value = "1.0.0"
                )
            }
        }
    }
}

@Composable
fun ProfileInfoItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun SendMessageScreen(
    onBack: () -> Unit,
    onSendMessage: (String, String, (Boolean) -> Unit) -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }

    val icons = listOf(
        "❤️", "👍", "😊", "😄", "💪", "🎉", "✅", "💙", "⭐", "🌟",
        "☺️", "🙂", "😃", "🎊", "🎈", "💝", "✨", "🔥", "💯", "🚀"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        // Header - Modern Card Design
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Send Message to Device",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // Message Input Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Message Text",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter message (e.g., 'I'm on the way!', 'Everything is OK')") },
                    maxLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Select Icon (Optional)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))

                // Icon Grid
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(icons.size) { index ->
                        val icon = icons[index]
                        Surface(
                            onClick = { selectedIcon = if (selectedIcon == icon) "" else icon },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedIcon == icon)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = icon,
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Preview Card
        if (messageText.isNotEmpty() || selectedIcon.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Preview",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (selectedIcon.isNotEmpty()) {
                        Text(
                            text = selectedIcon,
                            style = MaterialTheme.typography.displaySmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (messageText.isNotEmpty()) {
                        Text(
                            text = messageText,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Send Button
        Button(
            onClick = {
                if (messageText.isNotEmpty() || selectedIcon.isNotEmpty()) {
                    isLoading = true
                    onSendMessage(messageText, selectedIcon) { success ->
                        isLoading = false
                        showSuccess = success
                        if (success) {
                            messageText = ""
                            selectedIcon = ""
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            enabled = !isLoading && (messageText.isNotEmpty() || selectedIcon.isNotEmpty()),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Sending...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.CenterVertically),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Send to Device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }

        if (showSuccess) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Message sent successfully!",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

// Function to send message to Firebase
private fun sendMessageToDevice(messageText: String, icon: String, callback: (Boolean) -> Unit) {
    val messageRef = FirebaseDatabase.getInstance().getReference("deviceMessages")
    val messageKey = messageRef.push().key ?: return callback(false)

    val messageData = hashMapOf(
        "text" to messageText,
        "icon" to icon,
        "deviceId" to "wearable_01",
        "timestamp" to System.currentTimeMillis(),
        "displayed" to false
    )

    messageRef.child(messageKey).setValue(messageData)
        .addOnSuccessListener {
            Log.d("MESSAGE", "Message sent successfully to Firebase")
            callback(true)
        }
        .addOnFailureListener { e ->
            Log.e("MESSAGE", "Failed to send message: ${e.message}")
            callback(false)
        }
}

