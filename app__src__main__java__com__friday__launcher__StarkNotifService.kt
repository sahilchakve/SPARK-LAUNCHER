package com.friday.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Passes notification and call events to the HUD, and keeps call actions so they can be pressed by voice. */
object NotifBus {
    @Volatile
    var sink: ((String) -> Unit)? = null
    val actions = ConcurrentHashMap<String, Array<Notification.Action>>()

    fun send(json: String) {
        sink?.invoke(json)
    }
}

class StarkNotifService : NotificationListenerService() {

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val extras = n.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: ""
        val text = extras?.getCharSequence("android.text")?.toString() ?: ""
        val key = sbn.key ?: ""
        val dialer = pkg.contains("dialer") || pkg.contains("incallui") || pkg.contains("telecom") || pkg == "com.android.phone"
        val acts = n.actions
        val isCall = n.category == Notification.CATEGORY_CALL || (dialer && acts != null && acts.isNotEmpty())
        if (isCall && acts != null) NotifBus.actions[key] = acts
        val json = JSONObject()
            .put("type", if (isCall) "call" else "notif")
            .put("key", key)
            .put("app", appLabel(pkg))
            .put("title", title)
            .put("text", text)
            .put("caller", title)
        NotifBus.send(json.toString())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        val n = sbn.notification ?: return
        NotifBus.actions.remove(key)
        if (n.category == Notification.CATEGORY_CALL) {
            NotifBus.send(JSONObject().put("type", "callEnd").put("key", key).toString())
        }
    }
}
