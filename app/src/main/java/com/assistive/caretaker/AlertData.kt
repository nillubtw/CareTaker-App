package com.assistive.caretaker

data class AlertData(
    var key: String = "",          // 🔑 Firebase node key
    var type: String = "",
    var acknowledged: Boolean = false,
    var timestamp: Long = 0L,
    var deviceId: String? = null
)


