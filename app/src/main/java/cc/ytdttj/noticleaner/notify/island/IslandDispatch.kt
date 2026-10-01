package cc.ytdttj.noticleaner.notify.island

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import cc.ytdttj.noticleaner.keepalive.SystemUIIslandDispatcher
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 岛代发客户端（2.0.1 Dev 16，方案 B）：
 *
 * App 收到动账后不再自己 notify 岛通知，而是发广播给 **SystemUI 进程内的接收器**
 * （[SystemUIIslandDispatcher]，LSPosed 注入），由它**以 com.android.systemui 身份**
 * notify——发送者就是 systemui，白名单/签名/云认证三道门槛天然全免。
 *
 * 就绪探测（PING/ACK）：SystemUI 侧接收器注册成功后回发 READY；App 进程启动时也会
 * 发 PING 主动询问。未就绪（模块禁用 / LSPosed 未激活 / SystemUI 未重启）时
 * [tryDispatch] 返回 false，调用方回退自身 notify + AuthSession 兜底路径——双保险不断链。
 *
 * 安全：SystemUI 侧接收器要求 signature 权限（模块 APK 声明并自动持有），第三方
 * 无法伪造岛通知。
 */
object IslandDispatch {

    /** 首次岛通知需要 systemui 重启后才走代发（接收器随 SystemUI 重启注册） */
    private val ready = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    /** App 进程内注册 READY 监听 + 发 PING 询问（ServiceLocator/App 初始化时调用一次） */
    fun init(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        runCatching {
            appContext.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent?.action == SystemUIIslandDispatcher.ACTION_DISPATCH_READY) {
                            ready.set(true)
                        }
                    }
                },
                IntentFilter(SystemUIIslandDispatcher.ACTION_DISPATCH_READY),
                Context.RECEIVER_NOT_EXPORTED,
            )
            // PING：SystemUI 若已就绪会回 READY（App 晚于 SystemUI 启动时靠这个补上）
            appContext.sendBroadcast(
                Intent(SystemUIIslandDispatcher.ACTION_DISPATCH_PING)
                    .setPackage("com.android.systemui"),
            )
        }
    }

    /** 接收器是否已确认就绪（未就绪时调用方回退自身 notify 路径） */
    fun isReady(): Boolean = ready.get()

    /**
     * 通过 SystemUI 代发岛通知。
     *
     * [notification] 为 [IslandParamsBuilder.build] 的产物——真正跨进程的是它的
     * extras（miui.focus.param / pics / actions）与"已完成"按钮的 PendingIntent。
     * SystemUI 进程内以自己的 channel + smallIcon 重建 Notification 后 notify。
     *
     * @return true = 已交由代发；false = 接收器未就绪（调用方回退自身路径）
     */
    fun tryDispatch(context: Context, notification: Notification, notificationId: Int): Boolean {
        if (!ready.get()) return false
        val inner = notification.extras ?: return false
        if (!inner.containsKey("miui.focus.param")) return false
        val intent = Intent(SystemUIIslandDispatcher.ACTION_DISPATCH_ISLAND)
            .setPackage("com.android.systemui")
            .putExtra(SystemUIIslandDispatcher.EXTRA_INNER, BundleCloner.clone(inner))
            .putExtra(SystemUIIslandDispatcher.EXTRA_ID, notificationId)
        notification.contentIntent?.let { intent.putExtra(SystemUIIslandDispatcher.EXTRA_CONTENT_PI, it) }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { return false }
        return true
    }
}

/** 广播 extras 的浅拷贝（直接传原 Bundle 在跨进程序列化时安全，这里仅作语义隔离） */
private object BundleCloner {
    fun clone(src: android.os.Bundle): android.os.Bundle = android.os.Bundle(src)
}
