package com.kite.zmusic.data.platform

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MusicPlatformStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _current = MutableStateFlow(read())
    val currentFlow: StateFlow<MusicPlatform> = _current.asStateFlow()

    val current: MusicPlatform get() = _current.value

    fun set(platform: MusicPlatform) {
        prefs.edit().putString(KEY, platform.id).apply()
        _current.value = platform
    }

    /** 重启前必须同步落盘，否则新进程仍读到旧平台。 */
    fun setBlocking(platform: MusicPlatform) {
        check(prefs.edit().putString(KEY, platform.id).commit())
        _current.value = platform
    }

    private fun read(): MusicPlatform = MusicPlatform.fromId(prefs.getString(KEY, null))

    companion object {
        private const val PREFS = "zmusic_platform"
        private const val KEY = "platform"

        fun restart(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pending = PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pending)
            (context as? Activity)?.finishAffinity()
            exitProcess(0)
        }
    }
}
