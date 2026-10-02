package cc.ytdttj.noticleaner.keepalive

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * LSPosed 模块入口（libxposed Modern API 102，作用域：system / SystemUI / xmsf）。
 *
 * 三组 hook：
 * 1. 保活（Plan.md §7.4，system_server）：拦截 ActiveServices 的 stop 系列，阻止系统/厂商框架停止本应用服务。
 * 2. 入队前拦截（1.2.1，system_server）：hook NotificationManagerService.enqueueNotificationInternal，
 *    通知入队前在 system_server 内完成决策——命中即吞掉，根除 NLS 进程冻结/被杀导致的过滤延迟。
 *    拦截记录经 ModuleLogSink 回流 APP（ModuleLogProvider），历史与学习闭环完整。
 * 3. 岛链路（island 分支）：SystemUI 焦点白名单解锁 + 岛代发；xmsf 云认证解锁。
 *
 * 2.2.0 Dev 1：作用域热重载（module.prop `autoHotReload=true`——App 更新后框架自动换代）。
 * 仅 SystemUI / xmsf 支持：system_server 在框架记账建立前加载模块，结构性不是热重载目标
 * （实测 getRunningTargets() 永不含它），维持"改码即重启"。
 * - [onHotReloading]（旧代）：完成退役契约——反注册 Dispatcher 广播接收器（本项目唯一的
 *   系统侧模块对象引用），失败即拒绝重载；经 savedInstanceState 把目标应用 ClassLoader
 *   带给新代（HotReloadedParam 不带 classloader，ClassLoader 是框架创建的活对象、非模块
 *   CL 对象，不违反中性原则的立法本意）。
 * - [onHotReloaded]（新代）：旧句柄按 id 原子 replaceHook（官方默认实现是全部 unhook，
 *   覆写后由本类负责），再走与首次加载相同的 install 补装（[hookOnce] 按 executable 去重）；
 *   SystemUI 侧另需急切注册岛代发接收器——Application.attach 在重载后不会再次触发。
 *
 * API 102 模型：入口类继承 XposedModule（框架实例化后 attachFramework 注入），
 * hook 为拦截器式 Hooker——【不调用 chain.proceed() 即阻断原方法】；
 * ExceptionMode.PROTECTIVE：hook 内异常被框架吞掉并照常放行。
 * 在 LSPosed 管理器中禁用模块即完全停用。
 */
class LspEntry : XposedModule() {

    /**
     * 本代 hook 登记：executable → id。[hookOnce] 的去重依据；热重载时先由旧句柄回填，
     * 使 install 补装自动跳过已被 replaceHook 覆盖的目标（避免双重 hook）。
     */
    private val hookedExecutables = ConcurrentHashMap<Executable, String>()

    /** 目标应用 ClassLoader（install 时记录；onHotReloading 经 savedInstanceState 带给新代） */
    @Volatile
    private var appClassLoader: ClassLoader? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded in ${param.processName} (isSystemServer=${param.isSystemServer()})")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        when (param.packageName) {
            "com.android.systemui" -> runCatching { installSystemUI(param.defaultClassLoader) }
                .onFailure { log(Log.WARN, TAG, "systemui install failed: $it") }
            "com.xiaomi.xmsf" -> runCatching { installXmsf(param.defaultClassLoader) }
                .onFailure { log(Log.WARN, TAG, "xmsf install failed: $it") }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        hookActiveServices(param.classLoader)
        hookNotificationManagerService(param.classLoader)
    }

    // ==================== 热重载（API 102，仅 SystemUI / xmsf 生效） ====================

    /**
     * 旧代退役回调（old code）。官方默认实现返回 false = 不覆写即永久拒绝热重载，
     * 必须显式返回 true。返回 true 前须完成退役契约：本项目无自建线程/native hook/JNI
     * 全局引用，唯一义务是反注册 Dispatcher 广播接收器。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val ok = runCatching { SystemUIIslandDispatcher.teardown() }
            .getOrElse {
                log(Log.WARN, TAG, "hot reload: dispatcher teardown crashed: $it")
                false
            }
        if (!ok) {
            // 接收器拆不干净 → 宁可拒绝（service 报 FAILED 且 message=null = 拒绝），
            // 也不留"新旧接收器并存"的双投递状态
            log(Log.WARN, TAG, "hot reload refused: dispatcher receiver still attached")
            return false
        }
        // 跨代状态：仅传目标应用 ClassLoader（框架创建的活对象，非模块 CL 对象）。
        // 框架对旧模块 CL 对象会抛 IllegalArgumentException——吞掉降级（新代走 ActivityThread 反射自取）
        try {
            param.setSavedInstanceState(appClassLoader)
        } catch (e: IllegalArgumentException) {
            log(Log.WARN, TAG, "hot reload: savedInstanceState rejected: $e")
        }
        log(Log.INFO, TAG, "hot reload accepted (old generation retiring)")
        return true
    }

    /**
     * 新代接管回调（new code）。注意两点官方规格：
     * 1. 包生命周期回调（onPackageLoaded 等）不会自动重放——hook 全部由本方法负责恢复；
     * 2. 默认实现是 unhook 全部旧句柄——本类覆写后改为按 id 原子 replaceHook + install 补装。
     * 全程 runCatching：异常穿透会导致 service 报 FAILED 但新代已接管（实测半挂状态，状态错乱）。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        runCatching {
            when (param.processName) {
                "com.android.systemui" -> reloadInProcess(param, "com.android.systemui") { cl ->
                    installSystemUI(cl)
                    // 急切注册岛代发接收器：Application.attach 在重载后不会再次触发，
                    // 必须用现存 Context 立即注册（App 侧 READY 处理幂等，重发无副作用）
                    val ctx = HookLogSink.currentApplicationOrNull()
                        ?: HookLogSink.systemContextOrNull()
                    if (ctx != null) {
                        SystemUIIslandDispatcher.registerNow(ctx, this)
                    } else {
                        log(Log.WARN, TAG, "hot reload: no Context for dispatcher re-register")
                    }
                }
                "com.xiaomi.xmsf" -> reloadInProcess(param, "com.xiaomi.xmsf") { cl ->
                    installXmsf(cl)
                }
                else -> super.onHotReloaded(param)
            }
        }.onFailure {
            log(Log.ERROR, TAG, "hot reload failed in ${param.processName}: $it")
        }
    }

    /** 旧句柄按 id 原子替换（保留 executable/priority/exceptionMode/id，无无-hook 窗口） */
    private fun reloadInProcess(
        param: HotReloadedParam,
        process: String,
        install: (ClassLoader) -> Unit,
    ) {
        HookLogSink.init(process)
        replaceOldHandles(param.oldHookHandles)
        // ClassLoader 恢复：优先跨代携带，失败走 ActivityThread 反射自取
        val cl = (param.savedInstanceState as? ClassLoader)
            ?: HookLogSink.currentApplicationOrNull()?.classLoader
            ?: HookLogSink.systemContextOrNull()?.classLoader
        if (cl == null) {
            log(Log.WARN, TAG, "hot reload: no ClassLoader available, replaced hooks only")
            return
        }
        install(cl) // hookOnce 按 executable 去重，已 replaceHook 覆盖的目标自动跳过
        log(Log.INFO, TAG, "hot reload completed in $process (pid unchanged)")
    }

    private fun replaceOldHandles(handles: List<XposedInterface.HookHandle>) {
        for (h in handles) {
            val id = h.id
            val hooker = id?.let { hookerForId(it) }
            if (hooker != null) {
                runCatching {
                    val newHandle = h.replaceHook(hooker)
                    hookedExecutables[newHandle.executable] = id
                    log(Log.INFO, TAG, "hot reload: replaced hook [$id]")
                }.onFailure {
                    log(Log.WARN, TAG, "hot reload: replace [$id] failed: $it")
                }
            } else {
                runCatching { h.unhook() }
                log(Log.INFO, TAG, "hot reload: unhooked handle (id=$id, no new hooker)")
            }
        }
    }

    /** id → 新代 Hooker（replaceHook 只换 Hooker，executable 由旧句柄保留） */
    private fun hookerForId(id: String): XposedInterface.Hooker? = when {
        id.startsWith("focus:") -> IslandUnlockFocusHook.hookerForId(this, id)
        id.startsWith("dispatcher:") -> SystemUIIslandDispatcher.hookerForId(this, id)
        id.startsWith("xmsf:") -> XmsfUnlockAuthHook.hookerForId(this, id)
        else -> null
    }

    /**
     * 幂等 hook 注册：同 executable 本代只挂一次（热重载补装时跳过已被 replaceHook
     * 覆盖的目标）。所有 SystemUI/xmsf hook 必须走此入口并携带稳定 id。
     */
    fun hookOnce(ex: Executable, id: String, hooker: XposedInterface.Hooker): Boolean {
        if (hookedExecutables.putIfAbsent(ex, id) != null) return false
        val ok = runCatching {
            hook(ex).setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker)
        }.isSuccess
        if (ok) {
            log(Log.INFO, TAG, "hooked [$id] ${ex.declaringClass?.name ?: "?"}#${ex.name}")
        } else {
            hookedExecutables.remove(ex)
            log(Log.WARN, TAG, "hook [$id] failed")
        }
        return ok
    }

    // ---- install（首次加载与热重载补装共用，幂等） ----

    private fun installSystemUI(cl: ClassLoader) {
        HookLogSink.init("com.android.systemui")
        appClassLoader = cl
        runCatching { IslandUnlockFocusHook(this).install(cl) }
            .onFailure { log(Log.WARN, TAG, "island focus hook init failed: $it") }
        runCatching { SystemUIIslandDispatcher.install(this, cl) }
            .onFailure { log(Log.WARN, TAG, "island dispatcher init failed: $it") }
    }

    private fun installXmsf(cl: ClassLoader) {
        HookLogSink.init("com.xiaomi.xmsf")
        appClassLoader = cl
        runCatching { XmsfUnlockAuthHook(this).install(cl) }
            .onFailure { log(Log.WARN, TAG, "xmsf auth hook init failed: $it") }
    }

    // ---- Hook 组 1：防服务被停（保活，system_server——不可热重载，维持冷路径） ----

    private fun hookActiveServices(classLoader: ClassLoader) {
        try {
            val asClass = Class.forName(AS_CLASS, false, classLoader)
            val hooker = BlockStopHooker(this)
            var hooked = 0
            for (m in asClass.declaredMethods) {
                if (m.name in HOOK_METHODS) {
                    runCatching {
                        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker)
                    }.onSuccess { hooked++ }
                        .onFailure { log(Log.WARN, TAG, "hook ${m.name} failed: $it") }
                }
            }
            log(Log.INFO, TAG, "ActiveServices hooked: $hooked methods")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "init failed: $t")
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

    // ---- Hook 组 2：入队前拦截（1.2.1，system_server——不可热重载，维持冷路径） ----

    private fun hookNotificationManagerService(classLoader: ClassLoader) {
        try {
            val nms = Class.forName(NMS_CLASS, false, classLoader)
            val target = longestEnqueue(nms)
            if (target == null) {
                log(Log.WARN, TAG, "enqueueNotificationInternal not found")
                return
            }
            val engine = FilterEngine().also { it.attach(this) }
            val sink = ModuleLogSink()
            hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(NmsBlockHooker(engine, sink))
            log(Log.INFO, TAG, "NMS enqueueNotificationInternal hooked (${target.parameterCount} params)")
            // Dev 5 曾在此处调 ActivityThread.systemMain() 取 SystemContext 提交心跳——
            // 【恶性 bug】system_server 启动中二次调用 systemMain 会 new 出第二个
            // ActivityThread 并 attach，污染全局状态：SystemServer.startOtherServices 的
            // installSystemProviders 拿到残缺 ClassLoader → ClassNotFoundException →
            // system_server FATAL → 重启循环 → 安全模式（2026-09-25 真机事故，Dev 6 首次
            // 重启时引爆，LSPosed 日志 4718 行铁证）。
            // Dev 7 修复：心跳改由 NmsBlockHooker 首次拦截时提交（那时系统已稳定运行，
            // Context 取自 hook 到的 NMS 实例本身，零额外反射）。
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "NMS hook failed: $t")
        }
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
                            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_PACKAGE, TARGET)
                            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_CHANNEL, "")
                            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_TITLE, "system_server NMS hook OK")
                            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_CONTENT, "")
                            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_POST_TIME, System.currentTimeMillis())
                            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_PROBABILITY, 0f)
                            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_DECISION,
                                cc.ytdttj.noticleaner.provider.ModuleLogProvider.LSP_ALIVE_DECISION)
                            put(cc.ytdttj.noticleaner.provider.ModuleLogProvider.COL_KEY, "lsp:heartbeat")
                        }
                        sink.submit(ctx, hb)
                        android.util.Log.i(TAG, "LSP heartbeat submitted (first NMS enqueue)")
                    }
                }.onFailure { android.util.Log.w(TAG, "heartbeat submit failed: $it") }
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
                android.util.Log.w("NCWatch", "module decide failed: $t")
                null
            }
            if (outcome == null || !outcome.block) return chain.proceed()

            android.util.Log.i("NCWatch", "enqueue blocked: ${outcome.decision} p=${outcome.probability} ${pkg.orEmpty()}")
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

    companion object {
        private const val TAG = "NotiCleaner"
        // island 分支：跟随 applicationId（island 版包名不同，LSPosed 需单独激活本模块）
        private val TARGET = cc.ytdttj.noticleaner.BuildConfig.APPLICATION_ID
        private const val AS_CLASS = "com.android.server.am.ActiveServices"
        private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

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
