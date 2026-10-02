package cc.ytdttj.noticleaner.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 岛代发（2.0.1 Dev 16，方案 B——借鉴 ref/HyperIsland islanddispatch 信任模型）：
 *
 * 在 SystemUI 进程内注册广播接收器，收到 App 的派发广播后**以 com.android.systemui
 * 身份** `nm.notify()` 发出岛通知。发送者 = systemui → SystemUI/xmsf 的三道认证门槛
 * （canShowFocus 白名单 / 签名校验 / 云认证 -300 scope mismatch）**天然全部不适用**，
 * 认证等待归零，且不再依赖 xmsf 混淆符号（AuthSession 兜底降级为旧路径回退用）。
 *
 * 安全：接收器注册时要求 signature 权限 `PERMISSION_DISPATCH_ISLAND`（模块 APK 声明
 * 并自动持有，第三方无法伪造），框架在 AMS 层强制校验发送方。
 *
 * 注册时机：hook `Application.attach` / `onCreate`（SystemUIApplication 会走基类），
 * 拿到 Context 后幂等注册。注册成功即回发 READY 广播，App 侧据此启用代发路径
 * （未 READY 时 App 自动回退自身 notify + AuthSession 兜底，双保险不断链）。
 */
internal object SystemUIIslandDispatcher {

    private const val TAG = "NCIslandHook"
    const val ACTION_DISPATCH_ISLAND = "cc.ytdttj.noticleaner.ACTION_DISPATCH_ISLAND"
    const val ACTION_DISPATCH_PING = "cc.ytdttj.noticleaner.ACTION_DISPATCH_PING"
    const val ACTION_DISPATCH_READY = "cc.ytdttj.noticleaner.ACTION_DISPATCH_READY"
    const val PERMISSION_SEND = "cc.ytdttj.noticleaner.PERMISSION_DISPATCH_ISLAND"
    const val CHANNEL_ID = "nc_island_dispatcher"
    const val EXTRA_INNER = "nc_island_extras"
    const val EXTRA_ID = "nc_island_id"
    const val EXTRA_CONTENT_PI = "nc_island_content_pi"

    @Volatile private var registered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 由 LspEntry 在 SystemUI 分支调用：挂 Application.attach/onCreate，拿 Context 注册 */
    fun install(module: XposedModule, classLoader: ClassLoader) {
        val appClass = runCatching { classLoader.loadClass("android.app.Application") }.getOrNull() ?: run {
            module.log(android.util.Log.WARN, TAG, "island dispatcher: Application class not found")
            return
        }
        var hooked = 0
        for (name in listOf("attach", "onCreate")) {
            runCatching {
                val m = appClass.declaredMethods.firstOrNull { it.name == name } ?: return@runCatching
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(AttachInterceptor(module))
                hooked++
            }
        }
        module.log(
            if (hooked > 0) android.util.Log.INFO else android.util.Log.WARN,
            TAG, "island dispatcher installed on $hooked Application hooks",
        )
    }

    private class AttachInterceptor(private val module: XposedModule) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val app = chain.thisObject as? android.app.Application
                    ?: (chain.args.firstOrNull() as? android.content.Context)?.applicationContext
                    ?: return@runCatching
                register(app.applicationContext, module)
            }
            return result
        }
    }

    /** 幂等注册：主线程 + SystemUIApplication Context */
    @Synchronized
    private fun register(ctx: Context, module: XposedModule) {
        if (registered) return
        registered = true
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "通知滤盒岛", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_DISPATCH_ISLAND -> handleDispatch(c, intent, module)
                        ACTION_DISPATCH_PING -> answerReady(c)
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(ACTION_DISPATCH_ISLAND)
                addAction(ACTION_DISPATCH_PING)
            }
            // signature 权限在框架层校验发送方；EXPORTED 是接收外部（本 App）广播的必要标志
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, PERMISSION_SEND, mainHandler, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter, PERMISSION_SEND, mainHandler)
            }
            answerReady(ctx)
            module.log(
                android.util.Log.INFO, TAG,
                "island dispatcher receiver registered — systemui-identity posting active",
            )
        }.onFailure {
            registered = false
            module.log(android.util.Log.WARN, TAG, "island dispatcher register failed: $it")
        }
    }

    /** 告知 App：派发接收器已就绪（App 侧据此启用代发路径） */
    private fun answerReady(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_DISPATCH_READY)
                    .setPackage("cc.ytdttj.noticleaner"),
            )
        }
    }

    private fun handleDispatch(ctx: Context, intent: Intent, module: XposedModule) {
        val inner = intent.getBundleExtra(EXTRA_INNER) ?: return
        if (!inner.containsKey("miui.focus.param")) return // 非岛通知防御
        val id = intent.getIntExtra(EXTRA_ID, 0)
        module.log(android.util.Log.INFO, TAG, "island dispatched as systemui id=$id")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // HyperIsland 经验：同 id 先 cancel 再 notify，避免被系统当作"更新"不触发展示
        runCatching { nm.cancel(id) }
        // 自排除标记：App 侧 NLS 收到这条（pkg=systemui）时据此跳过，不进过滤管线/历史
        inner.putBoolean("nc_island_dispatched", true)
        val notif = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setVisibility(Notification.VISIBILITY_SECRET) // 通知栏完全无痕，仅岛展示
            .addExtras(inner)
            .also { b ->
                (intent.getParcelableExtra(EXTRA_CONTENT_PI, android.app.PendingIntent::class.java))
                    ?.let { b.setContentIntent(it) }
            }
            .build()
        nm.notify(id, notif)
    }
}
