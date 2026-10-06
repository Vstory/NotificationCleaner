package io.github.vstory.hook.notifyfilter.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import io.github.vstory.hook.notifyfilter.R

/**
 * 监听失效提醒通知（1.4.0 Dev 15）。
 *
 * 监听断线（进程被杀/绑定卡死/权限被摘除）时发一条**悬浮 + 锁屏可见**的提醒，
 * 让用户在没打开 APP 的情况下也能立刻知道"净化停了"，并可直接点「立即修复」。
 *
 * 形态要点：
 * - 渠道 IMPORTANCE_HIGH → 系统以**悬浮通知（heads-up）**弹出；
 * - [Notification.VISIBILITY_PUBLIC] → 锁屏界面完整显示内容（不是"隐藏内容"）；
 * - 两个操作：立即修复（Shizuku/Root 直接执行，普通用户跳权限页）/ 权限设置；
 * - 30 分钟冷却，避免看门狗 30s 一次反复弹同一条。
 */
object ListenerAlertNotifier {

    private const val CHANNEL_ID = "listener_alert"
    private const val NOTIF_ID = 8001
    private const val COOLDOWN_MS = 30 * 60 * 1000L
    private const val PREFS = "listener_alert"
    private const val KEY_LAST = "last_notified"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        // 渠道名/描述是创建时写进系统库的快照，只有重复 create 才会跟随系统语言刷新
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.listener_alert_channel),
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.listener_alert_channel_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
            },
        )
    }

    /**
     * 监听失效提醒；冷却期内不重复弹。
     * @param escalate 连续多次修复无效时升级为"建议重启手机"（系统放弃重绑只有重启能复位）
     */
    fun notifyDown(context: Context, @StringRes reasonRes: Int, escalate: Boolean = false) {
        val app = context.applicationContext
        ensureChannel(app)
        val reason = app.getString(reasonRes)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) < COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST, now).apply()

        val openApp = PendingIntent.getActivity(
            app, 0,
            Intent(app, io.github.vstory.hook.notifyfilter.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val repair = PendingIntent.getBroadcast(
            app, 1,
            Intent(app, ListenerRepairActionReceiver::class.java)
                .setAction(ListenerRepairActionReceiver.ACTION_REPAIR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val settings = PendingIntent.getBroadcast(
            app, 2,
            Intent(app, ListenerRepairActionReceiver::class.java)
                .setAction(ListenerRepairActionReceiver.ACTION_OPEN_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(app.getString(R.string.listener_alert_title))
            .setContentText(
                app.getString(
                    if (escalate) R.string.listener_alert_text_escalated
                    else R.string.listener_alert_text,
                    reason,
                ),
            )
            .setStyle(
                Notification.BigTextStyle().bigText(
                    app.getString(
                        if (escalate) R.string.listener_alert_bigtext_escalated
                        else R.string.listener_alert_bigtext,
                        reason,
                    ),
                ),
            )
            // 锁屏完整显示
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(app, R.drawable.ic_stat_alert),
                    app.getString(R.string.listener_alert_action_repair), repair,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    app.getString(R.string.listener_alert_action_settings),
                    settings,
                ).build(),
            )
            .build()

        runCatching {
            app.getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notif)
        }
    }

    /** 监听恢复后撤下提醒 */
    fun cancel(context: Context) {
        runCatching {
            context.applicationContext.getSystemService(android.app.NotificationManager::class.java)
                .cancel(NOTIF_ID)
        }
    }

    /** 清冷却（用户手动点修复后允许再次提醒） */
    fun resetCooldown(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST, 0L).apply()
        }
    }
}
