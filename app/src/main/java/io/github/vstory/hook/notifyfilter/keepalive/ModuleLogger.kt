package io.github.vstory.hook.notifyfilter.keepalive

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 模块端（system_server）唯一的框架日志出口。
 *
 * `android.util.Log` 只进 logcat，LSPosed 管理器的「日志」页不读 logcat ⇒ 模块端日志在日志页
 * 一条都看不到（跨进程配置链路的验收判据曾据此写错）。
 *
 * 热重载会换 ClassLoader 重载模块类：本 object 随之重建、[api] 复位 ⇒ MainHook 每次
 * installHooks 都要重新 attach。
 */
internal object ModuleLogger {

    const val TAG = "NotifyFilter"

    @Volatile
    private var api: XposedInterface? = null

    fun attach(newApi: XposedInterface) {
        api = newApi
    }

    fun i(msg: String) = emit(Log.INFO, msg, null)

    fun e(msg: String, t: Throwable? = null) = emit(Log.ERROR, msg, t)

    private fun emit(level: Int, msg: String, t: Throwable?) {
        // 日志失败不能影响判定链（框架日志接口本身会在 daemon 未连上时抛）
        val a = api ?: return
        runCatching {
            if (t == null) a.log(level, TAG, msg) else a.log(level, TAG, msg, t)
        }
    }
}
