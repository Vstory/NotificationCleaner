package io.github.vstory.hook.notifyfilter

import android.app.Application
import io.github.vstory.hook.notifyfilter.data.ModelRepository
import io.github.vstory.hook.notifyfilter.data.ModuleConfigSync
import io.github.vstory.hook.notifyfilter.data.SettingsRepository
import io.github.vstory.hook.notifyfilter.data.db.AppDatabase
import io.github.vstory.hook.notifyfilter.notify.CleanerListenerService
import io.github.vstory.hook.notifyfilter.notify.KeepAliveManager
import io.github.vstory.hook.notifyfilter.notify.RuleEngine
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // 1.3.2（P1-4）：启动预热——模型（base+delta）与 Room 首连在 IO 线程提前加载，
        // 冷启动后首条通知的处理不再被 ~10-15ms 模型加载尖峰占住实时槽
        ServiceLocator.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ServiceLocator.modelRepo.get() }
        }
        // LSPosed 框架服务（模块激活时由框架绑定）：模块激活状态 + delta 远程文件通道
        runCatching { XposedServiceHelper.registerListener(this) }
        // 模块拦截记录回流：system_server 在 APP 未运行时缓冲，启动即请求刷出
        runCatching {
            sendBroadcast(
                android.content.Intent(io.github.vstory.hook.notifyfilter.data.ModuleConfigCodec.ACTION_FLUSH_LOGS)
                    .setPackage(packageName),
            )
        }
        // 1.2.2：后台定期更新检查（6 小时，有网络约束），有新版本发通知提醒
        runCatching { io.github.vstory.hook.notifyfilter.update.UpdateWorker.schedule(this) }
        // 预测性返回开关（外观与主题子页）：manifest 未声明 enableOnBackInvokedCallback 时
        // targetSdk 33+ 默认开启，关掉必须改写 ApplicationInfo，且要在任何 Activity 创建前生效
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val predictiveBack = kotlinx.coroutines.runBlocking { ServiceLocator.settings.predictiveBack.first() }
            setEnableOnBackInvokedCallback(applicationInfo, predictiveBack)
        }
    }

    override fun onServiceBind(service: XposedService) {
        // Dev 7：转发到激活检测器（框架绑定=模块已启用；scope/请求作用域能力集中于此）
        io.github.vstory.hook.notifyfilter.keepalive.LspServiceDetector.onServiceBound(service)
        ServiceLocator.onXposedServiceBound(service)
    }

    override fun onServiceDied(service: XposedService) {
        io.github.vstory.hook.notifyfilter.keepalive.LspServiceDetector.onServiceDied()
        ServiceLocator.onXposedServiceDied()
    }

    companion object {
        /**
         * 切换预测性返回动画。`setEnableOnBackInvokedCallback` 是 hidden API，
         * 先豁免检查再反射调用，否则在 targetSdk 33+ 上直接抛 NoSuchMethodException。
         */
        fun setEnableOnBackInvokedCallback(appInfo: android.content.pm.ApplicationInfo, enabled: Boolean) {
            runCatching {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback",
                )
                val method = android.content.pm.ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enabled)
            }
        }
    }
}

/** 手写 ServiceLocator（Plan.md §2：小项目不引入 Hilt） */
object ServiceLocator {
    private val appJob = kotlinx.coroutines.SupervisorJob()
    /** 应用级协程作用域（与进程同生命周期，供无 VM 场景使用） */
    val appScope = kotlinx.coroutines.CoroutineScope(appJob + kotlinx.coroutines.Dispatchers.Default)

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var modelRepo: ModelRepository
        private set
    lateinit var ruleEngine: RuleEngine
        private set
    lateinit var keepAlive: KeepAliveManager
        private set
    lateinit var moduleSync: ModuleConfigSync
        private set

    lateinit var appContext: android.content.Context
        private set

    fun modelDir(): String = "model" // delta 等学习文件相对 filesDir 的子目录

    fun init(app: Application) {
        appContext = app.applicationContext
        // 1.4.0 Dev 12：环形日志最先初始化（后续任何组件的埋点即刻生效）
        io.github.vstory.hook.notifyfilter.diagnostics.RingLog.init(app)
        db = AppDatabase.get(app)
        settings = SettingsRepository(app)
        modelRepo = ModelRepository(app)
        ruleEngine = RuleEngine(db.ruleDao(), db.whitelistDao())
        keepAlive = KeepAliveManager(app)
        moduleSync = ModuleConfigSync(db.ruleDao(), db.whitelistDao())
        moduleSync.start(appScope)
        CleanerListenerService.initScope(app)
    }

    /** LSPosed 框架服务绑定（模块激活）：注入同步器并补推 delta */
    fun onXposedServiceBound(service: XposedService) {
        moduleSync.xposedService = service
        appScope.launch { moduleSync.onServiceBound() }
    }

    fun onXposedServiceDied() {
        moduleSync.xposedService = null
    }
}
