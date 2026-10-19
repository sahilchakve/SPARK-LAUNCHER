package com.friday.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** Passes notification and call events from Android to the HUD. */
object NotifBus {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun send(json: String) {
        sink?.invoke(json)
    }
}

class StarkNotifService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (sbn.packageName == packageName) return
        val extras = n.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: ""
        val text = extras?.getCharSequence("android.text")?.toString() ?: ""
        val isCall = n.category == Notification.CATEGORY_CALL
        val json = JSONObject()
            .put("type", if (isCall) "call" else "notif")
            .put("key", sbn.key ?: "")
            .put("app", sbn.packageName ?: "")
            .put("title", title)
            .put("text", text)
        NotifBus.send(json.toString())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (n.category == Notification.CATEGORY_CALL) {
            NotifBus.send(JSONObject().put("type", "callEnd").put("key", sbn.key ?: "").toString())
        }
    }
}
