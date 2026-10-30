package com.budgetingtool.app.data

import android.content.Context
import com.budgetingtool.app.util.newId

/** Small per-device settings. Never synced. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("budget_prefs", Context.MODE_PRIVATE)

    val deviceId: String by lazy {
        sp.getString(KEY_DEVICE, null) ?: newId().also { sp.edit().putString(KEY_DEVICE, it).apply() }
    }

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v.trim()).apply()

    /** The shared household code; null when not paired with a spouse. */
    var householdCode: String?
        get() = sp.getString("household_code", null)
        set(v) = sp.edit().putString("household_code", v).apply()

    /** Percent of a budget at which the "getting close" alert fires. */
    var warnPercent: Int
        get() = sp.getInt("warn_percent", 80)
        set(v) = sp.edit().putInt("warn_percent", v).apply()

    /** Blocks screenshots and hides the app's contents in the recent-apps view. */
    var secureScreen: Boolean
        get() = sp.getBoolean("secure_screen", true)
        set(v) = sp.edit().putBoolean("secure_screen", v).apply()

    /** Ask for fingerprint / face / device PIN when opening the app. */
    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit().putBoolean("app_lock", v).apply()

    /** Local clock time of the last successful send to a peer. */
    fun watermark(peerId: String): Long = sp.getLong("wm_$peerId", 0L)
    fun setWatermark(peerId: String, value: Long) = sp.edit().putLong("wm_$peerId", value).apply()
    fun clearWatermarks() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("wm_") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Last-known addresses ("ip:port") of the other phone(s), so they can reconnect without discovery. */
    var knownPeers: Set<String>
        get() = sp.getStringSet("known_peers", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("known_peers", v).apply()

    /** Keep a small foreground service running so sync continues while the app is in the background. */
    var backgroundSync: Boolean
        get() = sp.getBoolean("background_sync", true)
        set(v) = sp.edit().putBoolean("background_sync", v).apply()

    /** Which person's view is showing (null = combined). Per phone, not synced. */
    var viewPerson: String?
        get() = sp.getString("view_person", null)
        set(v) = sp.edit().putString("view_person", v).apply()

    fun alertLevel(key: String): Int = sp.getInt("alert_$key", 0)
    fun setAlertLevel(key: String, level: Int) = sp.edit().putInt("alert_$key", level).apply()

    private companion object {
        const val KEY_DEVICE = "device_id"
    }
}
