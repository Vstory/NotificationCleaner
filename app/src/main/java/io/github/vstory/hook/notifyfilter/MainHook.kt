package io.github.vstory.hook.notifyfilter

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.vstory.hook.notifyfilter.keepalive.FilterEngine
import io.github.vstory.hook.notifyfilter.keepalive.ModuleLogSink
import io.github.vstory.hook.notifyfilter.provider.ModuleLogProvider
import java.lang.reflect.Method

/**
 * LSPosed 模块入口（libxposed Modern API 102，作用域：系统 android / system_server）。
 *
 * 两组 hook：
 * 1. 保活（Plan.md §7.4）：拦截 ActiveServices 的 stop 系列，阻止系统/厂商框架停止本应用服务。
 * 2. 入队前拦截（1.2.1，借鉴 ref/Notice）：hook NotificationManagerService.enqueueNotificationInternal，
 *    通知入队前在 system_server 内完成决策——命中即吞掉，根除 NLS 进程冻结/被杀导致的过滤延迟。
 *    拦截记录经 ModuleLogSink 回流 APP（ModuleLogProvider），历史与学习闭环完整。
 *
 * API 102 模型：入口类继承 XposedModule（框架实例化后 attachFramework 注入），
 * hook 为拦截器式 Hooker——【不调用 chain.proceed() 即阻断原方法】；
 * ExceptionMode.PROTECTIVE：hook 内异常被框架吞掉并照常放行。
 * 在 LSPosed 管理器中禁用模块即完全停用。
 */
class MainHook : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "api102 module loaded in ${param.processName} (isSystemServer=${param.isSystemServer()})")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        sClassLoader = param.classLoader
        installHooks(param.classLoader)
    }

    /** 接口默认返回 false，不覆写则旧代码永远拒绝热重载，module.prop 的 autoHotReload 形同虚设。 */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = true

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // 热重载不重放 onSystemServerStarting：旧句柄不显式拆掉，原方法会被双份拦截。
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        // system_server 无 APP ClassLoader 概念，被 hook 的框架类由 boot loader 加载，
        // 故从旧句柄反推 loader 不会被「平台类」污染（与普通 APP 模块的跳过规则不同）。
        val cl = sClassLoader
            ?: param.oldHookHandles.firstNotNullOfOrNull { it.executable.declaringClass.classLoader }
            ?: ClassLoader.getSystemClassLoader()
        installHooks(cl)
    }

    // ---- 装配 ----

    /**
     * 唯一装配入口，onSystemServerStarting 与 onHotReloaded 共用。
     *
     * 每个 hook 点各打一条日志会刷屏，故统一记进 [sHookDetail] + 计数器，装完只出一条汇总。
     * SKIP = 类/方法在该 ROM 上不存在（跨版本正常）；FAIL = hook 抛异常（需要修）。
     */
    private fun installHooks(classLoader: ClassLoader) {
        sHandles.forEach { runCatching { it.unhook() } }
        sHandles.clear()
        sHookOk = 0
        sHookSkip = 0
        sHookFail = 0
        sHookDetail = StringBuilder()
        dbg("installHooks start, classLoader=$classLoader")

        hookActiveServices(classLoader)
        hookNotificationManagerService(classLoader)

        log(Log.INFO, TAG, "installHooks done: $sHookOk OK / $sHookSkip SKIP / $sHookFail FAIL /$sHookDetail")
    }

    private fun ok(desc: String, handle: XposedInterface.HookHandle) {
        sHookOk++
        sHandles += handle
        sHookDetail.append("\n[OK]   ").append(desc)
    }

    private fun skip(desc: String, t: Throwable) {
        sHookSkip++
        sHookDetail.append("\n[SKIP] ").append(desc).append(": ").append(t.message)
    }

    private fun fail(desc: String, t: Throwable) {
        sHookFail++
        sHookDetail.append("\n[FAIL] ").append(desc).append(": ").append(t)
    }

    // ---- Hook 组 1：防服务被停（保活） ----

    private fun hookActiveServices(classLoader: ClassLoader) {
        val asClass = runCatching { Class.forName(AS_CLASS, false, classLoader) }.getOrElse {
            skip(AS_CLASS, it)
            return
        }
        val hooker = BlockStopHooker(this)
        for (m in asClass.declaredMethods) {
            if (m.name !in HOOK_METHODS) continue
            runCatching { hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker) }
                .onSuccess { ok("$AS_CLASS#${m.name}", it) }
                .onFailure { fail("$AS_CLASS#${m.name}", it) }
        }
    }

    /**
     * 拦截器：参数（含父类字段）中找到 packageName == 本应用的 ServiceRecord 时，
     * 不调用 proceed 直接返回"阻断值" → 阻止 stop/stopServiceToken；其余调用照常 proceed。
     *
     * 2.0.1 Dev 10（P0-1）：返回值必须按方法返回类型映射，不能一律 null——
     * `stopServiceTokenLocked` 返回 primitive boolean、`stopServiceLocked` 在部分版本返回 int，
     * 拿 null 去填 primitive 槽位是未定义行为（取决于 lsplant/libxposed 实现，
     * 极可能在 **system_server 内** NPE/转型崩溃）。阻断值一律走 [blockedResult]。
     */
    private class BlockStopHooker(private val xposed: XposedInterface) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val isTarget = chain.getArgs().any { arg -> arg != null && packageNameOf(arg) == TARGET }
            if (!isTarget) return chain.proceed()
            xposed.log(Log.INFO, TAG, "blocked service stop attempt")
            return blockedResult(chain.executable as? Method)
        }

        /** 沿类层次找 packageName 字段（等价旧 XposedHelpers.getObjectField） */
        private fun packageNameOf(arg: Any): String? {
            var c: Class<*>? = arg.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField("packageName")
                    f.isAccessible = true
                    return f.get(arg) as? String
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            return null
        }
    }

    // ---- Hook 组 2：入队前拦截（1.2.1） ----

    private fun hookNotificationManagerService(classLoader: ClassLoader) {
        val nms = runCatching { Class.forName(NMS_CLASS, false, classLoader) }.getOrElse {
            skip(NMS_CLASS, it)
            return
        }
        val target = longestEnqueue(nms)
        if (target == null) {
            skip("$NMS_CLASS#enqueueNotificationInternal", NoSuchMethodException())
            return
        }
        val engine = sharedEngine(this)
        val sink = sharedSink()
        runCatching {
            hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(NmsBlockHooker(engine, sink))
        }.onSuccess { ok("$NMS_CLASS#enqueueNotificationInternal(${target.parameterCount})", it) }
            .onFailure { fail("$NMS_CLASS#enqueueNotificationInternal", it) }

        // Dev 5 曾在此处调 ActivityThread.systemMain() 取 SystemContext 提交心跳——
        // 【恶性 bug】system_server 启动中二次调用 systemMain 会 new 出第二个
        // ActivityThread 并 attach，污染全局状态：SystemServer.startOtherServices 的
        // installSystemProviders 拿到残缺 ClassLoader → ClassNotFoundException →
        // system_server FATAL → 重启循环 → 安全模式（2026-09-25 真机事故，Dev 6 首次
        // 重启时引爆，LSPosed 日志 4718 行铁证）。
        // Dev 7 修复：心跳改由 NmsBlockHooker 首次拦截时提交（那时系统已稳定运行，
        // Context 取自 hook 到的 NMS 实例本身，零额外反射）。
    }

    /** 取参数最多的 enqueueNotificationInternal 重载（跨 ROM 版本兜底） */
    private fun longestEnqueue(nms: Class<*>): Method? {
        val methods = ArrayList<Method>()
        var current: Class<*>? = nms
        while (current != null && current != Any::class.java) {
            methods += current.declaredMethods.filter { it.name == "enqueueNotificationInternal" }
            current = current.superclass
        }
        return methods.maxByOrNull { it.parameterTypes.size }
    }

    /**
     * 入队前拦截器：解析参数（pkg, Notification）→ FilterEngine 决策；
     * 命中 → 回流记录 + 返回 [blockedResult]（按返回类型给零值，boolean→false）阻断入队；
     * 未命中/异常 → 照常 proceed。
     */
    private class NmsBlockHooker(
        private val engine: FilterEngine,
        private val sink: ModuleLogSink,
    ) : XposedInterface.Hooker {

        /** Dev 7：首次真实拦截时提交激活心跳（此前的 systemMain() 方案会导致 system_server 崩溃） */
        @Volatile
        private var heartbeatSent = false

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!heartbeatSent) {
                heartbeatSent = true
                runCatching {
                    val ctx = chain.thisObject?.let { nmsContext(it) }
                    if (ctx != null) {
                        val hb = android.content.ContentValues().apply {
                            put(ModuleLogProvider.COL_PACKAGE, TARGET)
                            put(ModuleLogProvider.COL_CHANNEL, "")
                            put(ModuleLogProvider.COL_TITLE, "system_server NMS hook OK")
                            put(ModuleLogProvider.COL_CONTENT, "")
                            put(ModuleLogProvider.COL_POST_TIME, System.currentTimeMillis())
                            put(ModuleLogProvider.COL_PROBABILITY, 0f)
                            put(ModuleLogProvider.COL_DECISION, ModuleLogProvider.LSP_ALIVE_DECISION)
                            put(ModuleLogProvider.COL_KEY, "lsp:heartbeat")
                        }
                        sink.submit(ctx, hb)
                        Log.i(TAG, "LSP heartbeat submitted (first NMS enqueue)")
                    }
                }.onFailure { Log.w(TAG, "heartbeat submit failed: $it") }
            }
            val args = chain.args
            val notification = args.firstOrNull { it is android.app.Notification } as? android.app.Notification
            var pkg: String? = null
            for (arg in args) {
                if (arg is String && arg.contains('.')) {
                    pkg = arg
                    break
                }
            }
            val outcome = try {
                engine.decide(pkg.orEmpty(), notification)
            } catch (t: Throwable) {
                Log.w(TAG, "module decide failed: $t")
                null
            }
            if (outcome == null || !outcome.block) return chain.proceed()

            Log.i(TAG, "enqueue blocked: ${outcome.decision} p=${outcome.probability} ${pkg.orEmpty()}")
            val postTime = System.currentTimeMillis()
            val values = android.content.ContentValues().apply {
                put("package", pkg.orEmpty())
                put("channel", notification?.channelId.orEmpty())
                put("title", outcome.title)
                put("content", outcome.content)
                put("post_time", postTime)
                put("probability", outcome.probability)
                put("decision", outcome.decision)
                put("key", "mod:${pkg.orEmpty()}:$postTime")
            }
            (chain.thisObject?.let { nmsContext(it) })?.let { sink.submit(it, values) }
            return blockedResult(chain.executable as? Method)
        }

        private fun nmsContext(service: Any): android.content.Context? = try {
            val m = service.javaClass.getMethod("getContext")
            m.invoke(service) as? android.content.Context
        } catch (_: Throwable) {
            try {
                val f = service.javaClass.getField("mContext")
                f.get(service) as? android.content.Context
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** D 级调试通道：release 下 BuildConfig.DEBUG 为常量 false，整个分支不进 dex。 */
    private fun dbg(msg: String) {
        // #ifdef DEBUG
        if (BuildConfig.DEBUG) {
            log(Log.DEBUG, TAG, msg)
            Log.d(TAG, msg)
        }
        // #endif
    }

    companion object {
        private const val TAG = "NotiCleaner"
        private val TARGET = BuildConfig.APPLICATION_ID
        private const val AS_CLASS = "com.android.server.am.ActiveServices"
        private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

        /** system_server 启动时框架给的 loader，供热重载复用（那之后不会再有 onSystemServerStarting） */
        @Volatile
        private var sClassLoader: ClassLoader? = null

        private val sHandles = ArrayList<XposedInterface.HookHandle>()
        private var sHookOk = 0
        private var sHookSkip = 0
        private var sHookFail = 0
        private var sHookDetail = StringBuilder()

        /**
         * 覆盖多个 ROM 版本的方法名（存在哪个 hook 哪个，全部失败也不影响系统）。
         *
         * **不再 hook `bringDownServiceLocked`**（Dev 10，P0-1）：它是 ServiceRecord 回收的
         * 唯一收口，无条件阻断会让 force-stop、卸载、包更新清理路径上本应用的
         * ServiceRecord 永远不被回收（ActiveServices 内部状态泄漏），且用户在系统设置里
         * "强制停止"会直接失效——保活只需挡住 stop/stopServiceToken 这类主动停止调用。
         */
        private val HOOK_METHODS = setOf(
            "stopServiceLocked",
            "stopServiceTokenLocked",
        )

        // 热重载会重放 installHooks，而这两个对象各自攥着常驻资源：FilterEngine 的模型重建线程 +
        // prefs 监听器，ModuleLogSink 的刷出线程 + 已注册的广播接收器。静态字段不随热重载重置、
        // 实例却每次都是新的 ⇒ 每热重载一次就往 system_server 里永久留下一份（上游 Dev 5 二次
        // systemMain 污染全局状态是同一类问题，只是量级小）。故进程内只造一份。
        @Volatile private var sEngine: FilterEngine? = null
        @Volatile private var sSink: ModuleLogSink? = null

        @Synchronized
        private fun sharedEngine(module: XposedInterface): FilterEngine =
            sEngine ?: FilterEngine().also { it.attach(module) }.also { sEngine = it }

        @Synchronized
        private fun sharedSink(): ModuleLogSink = sSink ?: ModuleLogSink().also { sSink = it }
    }
}

/**
 * 阻断返回值：按被 hook 方法的返回类型给"零值"，**绝不用 null 填 primitive 槽位**
 * （2.0.1 Dev 10，P0-1）。`stopServiceTokenLocked` 返回 primitive boolean、
 * `stopServiceLocked` 在部分版本返回 int —— null 落入 primitive 槽位是未定义行为，
 * 取决于 lsplant/libxposed 实现，很可能在 **system_server 内** NPE/转型崩溃。
 * void 与引用类型返回 null 是合规的。
 */
private fun blockedResult(method: Method?): Any? = when (method?.returnType) {
    null -> null
    java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
    java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
    java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
    java.lang.Short.TYPE, java.lang.Short::class.java -> 0.toShort()
    java.lang.Byte.TYPE, java.lang.Byte::class.java -> 0.toByte()
    java.lang.Double.TYPE, java.lang.Double::class.java -> 0.0
    java.lang.Float.TYPE, java.lang.Float::class.java -> 0f
    java.lang.Character.TYPE, java.lang.Character::class.java -> 0.toChar()
    else -> null
}
