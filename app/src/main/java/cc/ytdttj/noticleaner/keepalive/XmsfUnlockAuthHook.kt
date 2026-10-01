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
 * hyperos.developer.xiaomi.com 完成，**断网时 fail-closed**（onAuthFailed → removeByKey）。
 * 本 hook 在 xmsf 进程内拦截认证失败回调，强制走成功路径 —— 与 SignalDock/HyperIsland
 * 的 UnlockFocusAuthHook 同一机制（HyperIsland 为 MIT 许可，此处按公开机制自行实现）。
 *
 * hook 点：com.xiaomi.xms.auth.AuthSession.b(error)
 * - error == null：认证成功，照常 proceed
 * - error != null：将错误码字段 `a` 置 0，调用成功回调 `h()`，跳过原方法
 * 混淆名随 xmsf 版本可能漂移，找不到时记日志并静默退出。
 *
 * Dev 8 诊断：成功路径时间戳（b 入口 / h() 调用时刻），量化"岛等认证"的耗时。
 *
 * Dev 15 重要结论（Dev 13/14/15 三版观察与实测，勿再尝试短路）：
 * - 认证 100% 失败：code=-300 scope mismatch（包名不在小米云焦点通知作用域，未上架），
 *   每次靠本 hook 兜底，网络往返 0.3~0.8s
 * - 发起方在 AuthSession 之外（com.xiaomi.xms.auth.AuthManager$binder$1.innerAuth，
 *   SystemUI 跨进程的认证入口），AuthSession 在整个流程中只被调用 b()
 * - **"缓存成功 Bundle + innerAuth 短路"证伪**：短路生效（0ms 返回缓存、无网络请求）
 *   但岛不渲染——SystemUI 渲染岛依赖完整认证会话流程的完成事件，仅让 innerAuth
 *   返回 Bundle 不够。认证 0.4s 是岛流程固定开销（远小于系统投递积压），不再尝试。
 * - 已精简：方法 dump、调用序列观察、构造器观察、栈回溯、短路、Bundle 缓存全部移除
 */
class XmsfUnlockAuthHook(private val module: XposedModule) {

    companion object {
        private const val AUTH_SESSION_CLASS = "com.xiaomi.xms.auth.AuthSession"
    }

    fun onPackageLoaded(param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        // Dev 8：Hook 日志回流（认证时刻进 App 环形日志）；xmsf 进程无直接 Application，
        // 读现有 ActivityThread 的 SystemContext（仅 getter，安全）
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
    }

    /** 认证失败拦截：强制 errorCode=0 并调用成功回调 h() */
    private class AuthBypassHooker(private val module: XposedModule) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
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
                // 强制置 0 之前先把真实错误码与错误信息回流（诊断"为什么认证失败"）
                val errInfo = errorInfo(error)
                setIntField(error!!, "a", 0)
                val success = callNoArg(chain.thisObject, "h")
                module.log(android.util.Log.INFO, "NCIslandHook", "auth bypassed (errorCode forced to 0) $errInfo")
                cc.ytdttj.noticleaner.keepalive.HookLogSink.log(
                    ctx,
                    "auth-BYPASSED",
                    "云端认证失败已强制成功（fail-closed 兜底）：$errInfo",
                )
                success
            }.getOrElse {
                module.log(android.util.Log.WARN, "NCIslandHook", "auth bypass failed: $it")
                chain.proceed()
            }
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
            }
            c = c.superclass
        }
        null
    }.getOrNull()
    val text = runCatching { error.toString() }.getOrDefault("?").take(120)
    return "code=$code msg=$text"
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
