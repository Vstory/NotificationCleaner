package cc.ytdttj.noticleaner.keepalive

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * xmsf 焦点通知认证解锁（island 分支，islandv2plan P3）。
 *
 * 作用域：com.xiaomi.xmsf（小米服务框架）。
 *
 * 真机诊断（2026-09-19，OS3）：岛通知的云端认证由 xmsf 联网
 * hyperos.developer.xiaomi.com 完成，**断网时 fail-closed**（onAuthFailed → removeByKey），
 * iptables 盲窗方案在 OS3 上方向相反。本 hook 在 xmsf 进程内拦截认证失败回调，
 * 强制走成功路径 —— 与 SignalDock/HyperIsland 的 UnlockFocusAuthHook 同一机制
 * （HyperIsland 为 MIT 许可，此处按公开机制自行实现）。
 *
 * hook 点：com.xiaomi.xms.auth.AuthSession.b(error)
 * - error == null：认证成功，照常 proceed
 * - error != null：将错误码字段 `a` 置 0，调用成功回调 `h()`，跳过原方法
 * 混淆名随 xmsf 版本可能漂移，找不到时记日志并静默退出。
 *
 * Dev 8 诊断（2026-09-26 上岛延迟排查）：成功路径此前完全无声，认证耗时是黑盒。
 * 补充时间戳：b(error==null) 入口 / 成功回调 h() 调用时刻，经 LSPosed 日志
 * 与 App 端"岛通知提交时刻"对齐，量化"岛等认证"的真实耗时。
 */
class XmsfUnlockAuthHook(private val module: XposedModule) {

    companion object {
        private const val AUTH_SESSION_CLASS = "com.xiaomi.xms.auth.AuthSession"
    }

    fun onPackageLoaded(param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        // Dev 8：Hook 日志回流（认证时刻进 App 环形日志）；xmsf 进程无 Application，
        // 读现有 ActivityThread 的 SystemContext（仅 getter 调用，安全）
        cc.ytdttj.noticleaner.keepalive.HookLogSink.init("com.xiaomi.xmsf")
        val authSession = runCatching { cl.loadClass(AUTH_SESSION_CLASS) }.getOrNull() ?: run {
            module.log(android.util.Log.WARN, "NCIslandHook", "AuthSession not found in xmsf CL (version changed?)")
            return
        }
        val target = authSession.declaredMethods
            .filter { it.name == "b" && it.parameterCount == 1 }
            .firstOrNull()
        if (target == null) {
            module.log(android.util.Log.WARN, "NCIslandHook", "AuthSession.b(error) not found (xmsf version changed?)")
            return
        }
        runCatching {
            module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(AuthBypassHooker(module))
            module.log(android.util.Log.INFO, "NCIslandHook", "hooked AuthSession.b(error) — focus auth unlocked")
        }.onFailure {
            module.log(android.util.Log.WARN, "NCIslandHook", "hook AuthSession.b failed: $it")
        }
        // Dev 8：成功回调 h() 时间戳（岛渲染前的最后一步，量化认证段耗时）
        val successCb = authSession.declaredMethods
            .filter { it.name == "h" && it.parameterCount == 0 }
            .firstOrNull()
        if (successCb != null) {
            runCatching {
                module.hook(successCb)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(AuthSuccessHooker(module))
                module.log(android.util.Log.INFO, "NCIslandHook", "hooked AuthSession.h() — success callback timestamp enabled")
            }.onFailure {
                module.log(android.util.Log.WARN, "NCIslandHook", "hook AuthSession.h failed: $it")
            }
        } else {
            module.log(android.util.Log.WARN, "NCIslandHook", "AuthSession.h() not found (version changed?)")
        }

        // Dev 13：认证全程观察——实测云认证 100% 失败（code=-300 scope mismatch，
        // 我们的包名不在小米云焦点通知作用域里，认证不可能真成功），现在的兜底挂在
        // **失败回调**上，每次都要白等网络往返（0.3~0.8s）。要"跳过认证"必须短路
        // **发起入口**，但 xmsf 混淆后入口方法名未知，盲猜 hook 点不可靠
        //（CodeReview P2-5 同款问题）。先把 AuthSession 的全部方法签名 dump 出来，
        // 并对每个方法挂**纯观察** hook（PROTECTIVE + 立即 proceed，只记录调用序列）——
        // 用户跑一次认证，"构造 → 发起 → b(error) → h()"的完整序列就出来了，
        // 下一版据此精准短路发起方法。每方法签名只记首次，避免刷屏。
        runCatching {
            val sigs = authSession.declaredMethods.joinToString("; ") { m ->
                "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
            }
            module.log(android.util.Log.INFO, "NCIslandHook", "AuthSession methods: $sigs")
            cc.ytdttj.noticleaner.keepalive.HookLogSink.log(null, "auth-session-methods", sigs)
            var observed = 0
            for (m in authSession.declaredMethods) {
                if (m.name == "b" || m.name == "h") continue // 已有专用 hook
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(AuthFlowObserver())
                    observed++
                }
            }
            module.log(
                android.util.Log.INFO, "NCIslandHook",
                "auth flow observation enabled: $observed methods hooked",
            )
        }.onFailure { module.log(android.util.Log.WARN, "NCIslandHook", "auth flow observation failed: $it") }

        // Dev 14：会话创建观察——认证发起方不在 AuthSession 内（实测只有 b() 被调），
        // 那就在**会话创建点**抓调用栈：谁构造 AuthSession，谁就是认证入口的邻居。
        // 每进程只记首次创建的栈，避免刷屏。
        runCatching {
            var hooked = 0
            for (ctor in authSession.declaredConstructors) {
                runCatching {
                    module.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(AuthCreateObserver())
                    hooked++
                }
            }
            module.log(
                android.util.Log.INFO, "NCIslandHook",
                "auth session create observation: $hooked constructors hooked",
            )
        }.onFailure { module.log(android.util.Log.WARN, "NCIslandHook", "auth create observation failed: $it") }
    }

    /** 会话创建观察（Dev 14）：记录 AuthSession 构造调用栈（每进程首次），立即 proceed */
    private class AuthCreateObserver : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (createLogged.compareAndSet(false, true)) {
                val stack = Throwable().stackTrace
                    .filter { !it.className.startsWith("LSPosed") && !it.className.contains("de.robv") }
                    .take(16)
                    .joinToString("\n") {
                        "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                    }
                android.util.Log.i("NCIslandHook", "auth session created. stack:\n$stack")
                cc.ytdttj.noticleaner.keepalive.HookLogSink.log(
                    cc.ytdttj.noticleaner.keepalive.HookLogSink
                        .contextOf(chain.args, null),
                    "auth-session-created", "构造调用栈:\n$stack",
                )
            }
            return chain.proceed()
        }

        companion object {
            val createLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        }
    }

    /**
     * 认证调用序列观察（Dev 13）：只记录、立即 proceed，不改变任何行为。
     * 每个方法签名（名字+参数个数）在进程生命周期内只记录首次调用。
     */
    private class AuthFlowObserver : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val m = chain.executable as? java.lang.reflect.Method
            val key = "${m?.name}/${m?.parameterCount}"
            if (seenSignatures.add(key)) {
                val argsDump = chain.args.joinToString(",") { it?.javaClass?.simpleName ?: "null" }
                val msg = "auth-flow: ${m?.name}($argsDump)"
                android.util.Log.i("NCIslandHook", msg)
                cc.ytdttj.noticleaner.keepalive.HookLogSink.log(
                    cc.ytdttj.noticleaner.keepalive.HookLogSink
                        .contextOf(chain.args, chain.thisObject),
                    "auth-flow", msg,
                )
            }
            return chain.proceed()
        }

        companion object {
            /** 进程级去重（观察的是"调用序列形态"，同签名重复调用无新信息） */
            val seenSignatures: MutableSet<String> =
                java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
        }
    }

    /** 认证失败拦截：强制 errorCode=0 并调用成功回调 h() */
    private class AuthBypassHooker(private val module: XposedModule) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            // Dev 12：xmsf 进程里 ActivityThread.currentActivityThread() 常常取不到，
            // 旧写法直接传 systemContextOrNull() 的结果（多为 null）→ 认证事件 100% 静默丢失。
            // 改为从 hook 现场（参数 / thisObject.mContext）尽力取 Context，最后才回退系统上下文。
            val ctx = cc.ytdttj.noticleaner.keepalive.HookLogSink
                .contextOf(chain.args, chain.thisObject)
            val error = chain.args.getOrNull(0) ?: run {
                // Dev 8：成功路径入口时间戳（此前静默 proceed，认证耗时无法量化）
                val t = System.currentTimeMillis()
                module.log(
                    android.util.Log.INFO, "NCIslandHook",
                    "auth success path START t=$t",
                )
                cc.ytdttj.noticleaner.keepalive.HookLogSink.log(ctx, "auth-START", "t=$t")
                return chain.proceed()
            }
            return runCatching {
                // Dev 12：强制置 0 之前先把真实错误码与错误信息回流——
                // 否则只知道"认证失败"，永远不知道为什么失败（无法判断能否让它真成功）
                val errInfo = errorInfo(error)
                setIntField(error!!, "a", 0)
                val success = callNoArg(chain.thisObject, "h")
                // Dev 14：运行时真实类 + 调用栈打进 **LSP 日志**（module.log 必达——
                // HookLogSink 的 Provider 回流在 xmsf 侧仍不通，且 Dev 13 实测 xmsf 运行时
                // 用的是 AuthSession 的**匿名子类**：b/h 未被重写能拦到，其余方法走子类
                // 实现、基类钩子拦不到，构造器也走子类的 → 必须看运行时类才能定位发起方）
                val runtimeClass = chain.thisObject?.javaClass
                val runtimeSigs = runtimeClass?.declaredMethods?.joinToString("; ") { m ->
                    "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}"
                } ?: "thisObject=null"
                module.log(
                    android.util.Log.INFO, "NCIslandHook",
                    "auth bypassed (errorCode forced to 0) $errInfo\n" +
                        "runtimeClass=${runtimeClass?.name}\n" +
                        "runtimeMethods=$runtimeSigs\n" +
                        "stack:\n${stackText()}",
                )
                cc.ytdttj.noticleaner.keepalive.HookLogSink.log(
                    ctx,
                    "auth-BYPASSED",
                    "云端认证失败已强制成功（fail-closed 兜底）：$errInfo\n调用栈:\n${stackText()}",
                )
                success
            }.getOrElse {
                module.log(android.util.Log.WARN, "NCIslandHook", "auth bypass failed: $it")
                chain.proceed()
            }
        }

        /**
         * Dev 14：认证调用栈。实测（Dev 13 观察）认证流程对 AuthSession 的方法调用
         * **只有 b(error)** —— 发起方在 AuthSession 之外的其它类里，凭方法签名定位不了。
         * 从失败回调向上抓调用栈，能直接看到认证的发起/管理类（即使混淆了，包结构与
         * 调用层次也足以定位短路点）。
         */
        private fun stackText(limit: Int = 14): String =
            Throwable().stackTrace
                .drop(1)
                .filter { !it.className.startsWith("LSPosed") && !it.className.contains("de.robv") }
                .take(limit)
                .joinToString("\n") {
                    "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                }

        /** 读取 xmsf 认证错误对象的错误码（混淆字段 a）与可读信息，全部防御式 */
        private fun errorInfo(error: Any?): String {
            if (error == null) return "error=null"
            val code = runCatching {
                var c: Class<*>? = error.javaClass
                while (c != null) {
                    runCatching {
                        val f = c!!.getDeclaredField("a")
                        f.isAccessible = true
                        return@runCatching f.get(error)
                    }.onSuccess { return@runCatching it }
                    c = c.superclass
                }
                null
            }.getOrNull()
            val text = runCatching { error.toString() }.getOrDefault("?").take(120)
            return "code=$code msg=$text"
        }
    }

    /** 成功回调 h() 时间戳：认证链完成（岛渲染的最后前置条件满足） */
    private class AuthSuccessHooker(private val module: XposedModule) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val t = System.currentTimeMillis()
            module.log(
                android.util.Log.INFO, "NCIslandHook",
                "auth success callback DONE t=$t — island may render now",
            )
            cc.ytdttj.noticleaner.keepalive.HookLogSink.log(
                cc.ytdttj.noticleaner.keepalive.HookLogSink
                    .contextOf(chain.args, chain.thisObject),
                "auth-DONE", "t=$t",
            )
            return chain.proceed()
        }
    }
}

private fun setIntField(instance: Any, fieldName: String, value: Int) {
    var c: Class<*>? = instance.javaClass
    while (c != null) {
        runCatching {
            val f = c.getDeclaredField(fieldName)
            f.isAccessible = true
            f.set(instance, value)
            return
        }.onFailure { e ->
            if (e !is NoSuchFieldException) throw e
        }
        c = c.superclass
    }
}

private fun callNoArg(instance: Any?, methodName: String): Any? {
    if (instance == null) return null
    var c: Class<*>? = instance.javaClass
    while (c != null) {
        runCatching {
            val m = c.getDeclaredMethod(methodName)
            m.isAccessible = true
            return m.invoke(instance)
        }.onFailure { e ->
            if (e !is NoSuchMethodException) throw e
        }
        c = c.superclass
    }
    return null
}
