package io.github.vstory.hook.notifyfilter.diagnostics

/**
 * 诊断日志模块划分（2.0.1 Dev 9）。
 *
 * 背景：此前所有模块的留痕都堆在同一个环形日志里（导出为一个大文本），
 * 排查某个链路时要在一整坨文本里 grep；且各模块共用一份容量配额，
 * 通知风暴（PIPE）会把看门狗等模块 24 小时内的历史挤出缓冲。
 *
 * 现在每个模块独立落盘（filesDir/logs/ring/<TAG>.log）+ 独立配额 + 独立导出文件，
 * 任一模块刷屏不再影响其它模块的 24 小时覆盖。
 *
 * TAG 一律用 ASCII：既作文件名（避免编码/解压乱码），也作落盘段名。
 */
object LogModules {

    /** 通知监听服务生命周期：连接/断线/重绑/补扫/服务销毁（NLS 进程视角） */
    const val NLS = "NLS"

    /** 过滤决策管线：每条通知的接收 → 打分 → 决策 → 清除（业务主链路） */
    const val PIPE = "PIPE"

    /** LSPosed Hook 回流：system_server 进程内的关键事件 */
    const val HOOK = "HOOK"

    /** 保活与看门狗：闹钟心跳、断连自愈、Shizuku 修复、系统侧诊断 */
    const val KEEP = "KEEP"

    /** 模型：内置基线加载、端上学习 delta 应用/重拟合/重置 */
    const val MODEL = "MODEL"

    /** 应用内更新：检查/下载/校验/安装 */
    const val UPDATE = "UPDATE"

    /** 崩溃与未捕获异常（崩溃栈同步落盘） */
    const val CRASH = "CRASH"

    /** 进程与系统：进程启动、环形日志轮转、未归类事件 */
    const val SYS = "SYS"

    /** 导出顺序（= zip 内文件序号 01..08） */
    val ALL: List<String> = listOf(NLS, PIPE, HOOK, KEEP, MODEL, UPDATE, CRASH, SYS)

    /** 模块中文名（导出文件标题用） */
    fun title(module: String): String = when (module) {
        NLS -> "通知监听服务（NLS）"
        PIPE -> "过滤决策管线"
        HOOK -> "Hook 回流（system_server）"
        KEEP -> "保活与看门狗"
        MODEL -> "模型与学习"
        UPDATE -> "应用内更新"
        CRASH -> "崩溃与异常"
        SYS -> "进程与系统"
        else -> module
    }

    /** 模块说明（导出文件头用，一句话讲清这个模块装了什么） */
    fun desc(module: String): String = when (module) {
        NLS -> "监听服务生命周期：onListenerConnected/Disconnected、请求重绑、重连补扫、服务销毁"
        PIPE -> "每条通知的处理流水：接收 → AI/规则决策 → 拦截清除（放行/拦截/白名单/保护类型）"
        HOOK -> "LSPosed 模块从 system_server 回流的关键事件（NMS 拦截、模块心跳）"
        KEEP -> "闹钟看门狗心跳、断连自愈与 Shizuku 强制修复、系统侧诊断（dumpsys notification）"
        MODEL -> "内置模型加载、端上学习 delta 应用与重拟合、重置基线"
        UPDATE -> "应用内更新检查、APK 下载与 sha256 校验、安装提示"
        CRASH -> "未捕获异常与崩溃栈（进程垂死时同步落盘）"
        SYS -> "进程启动、环形日志轮转标记、未归类事件"
        else -> ""
    }

    /** zip 内文件名：序号 + 中文名 + TAG，解压后按模块顺序排列 */
    fun fileName(module: String): String {
        val idx = ALL.indexOf(module)
        val no = if (idx >= 0) String.format("%02d", idx + 1) else "99"
        return "$no-${title(module)}-$module.log"
    }

    /**
     * 该模块在 logcat 里的补充来源（tag → 模块）。
     * logcat 缓冲通常不足 24 小时，只作补充；24 小时覆盖由环形日志保证。
     */
    fun logcatTagFor(module: String): String? = when (module) {
        NLS -> "NfWatch"
        HOOK -> "NotifyFilter"
        UPDATE -> "UpdateVM"
        MODEL -> "ModelRepository"
        else -> null
    }

    /** logcat 抓取的全部 tag（其它一律 *:S 静默，避免导出里混进系统噪声） */
    val LOGCAT_TAGS: List<String> = listOf("NfWatch", "NotifyFilter", "UpdateVM", "ModelRepository")
}
