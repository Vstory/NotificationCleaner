package io.github.vstory.hook.notifyfilter.update

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.vstory.hook.notifyfilter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新（UpgradePlan.md）：单通道，检查与下载都只走本仓库（见 [UpdateChecker]）。
 * 下载地址由本端按版本号构造，不读 latest.json 的 url 字段（该字段仅留给旧客户端兜底）。
 * latest.json: {versionCode, versionName, notes, url?, sha256?}
 */
@Serializable
data class LatestRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String = "",
    val url: String = "",
    val sha256: String? = null,
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val release: LatestRelease) : UpdateState()
    data class Downloading(val release: LatestRelease, val progress: Int) : UpdateState()
    data class ReadyToInstall(val release: LatestRelease, val file: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateViewModel : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var downloadJob: Job? = null

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /** 检查更新（逻辑在 [UpdateChecker]，与后台 Worker 共用） */
    fun checkUpdate() {
        if (_state.value is UpdateState.Checking) return
        downloadJob?.cancel()
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val release = UpdateChecker.checkLatest()
            _state.value = when {
                release == null -> UpdateState.Error("检查失败：无法访问更新源")
                release.versionCode > BuildConfig.VERSION_CODE -> {
                    // Dev 9：更新检查结果落环形日志（UPDATE 模块，覆盖 24 小时）
                    io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
                        io.github.vstory.hook.notifyfilter.diagnostics.LogModules.UPDATE,
                        "发现新版本 ${release.versionName} (vc${release.versionCode}) " +
                            "当前=vc${BuildConfig.VERSION_CODE}",
                    )
                    UpdateState.Available(release)
                }
                else -> UpdateState.UpToDate
            }
        }
    }

    /** 下载 APK：地址按版本号拼，与 build-release.yml 的 tag / 资产名一一对应 */
    fun startDownload(release: LatestRelease) {
        downloadJob?.cancel()
        val appCtx = io.github.vstory.hook.notifyfilter.ServiceLocator.appContext
        // 版本名去空格（上游 Dev 版形如 "1.3.2 Dev 1" → "1.3.2Dev1"）：tag 与资产名都不允许空格
        val ver = release.versionName.replace(" ", "")
        val tag = "v$ver.${release.versionCode}"
        val apkName = "NotifyFilter.$ver.${release.versionCode}.release.apk"
        val url = "${BuildConfig.UPDATE_APK_BASE}/$tag/$apkName"
        val dest = File(appCtx.getExternalFilesDir(null), "update/$apkName")
        _state.value = UpdateState.Downloading(release, 0)
        downloadJob = viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { directDownload(url, dest, release) }
            if (err == null) {
                io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
                    io.github.vstory.hook.notifyfilter.diagnostics.LogModules.UPDATE,
                    "APK 下载完成 → ${release.versionName}",
                )
                _state.value = UpdateState.ReadyToInstall(release, dest)
            } else {
                io.github.vstory.hook.notifyfilter.diagnostics.RingLog.log(
                    io.github.vstory.hook.notifyfilter.diagnostics.LogModules.UPDATE,
                    "✗ APK 下载失败 $err",
                )
                android.util.Log.w("UpdateVM", "download failed $url: $err")
                dest.delete()
                File(dest.parentFile, dest.name + ".tmp").delete()
                _state.value = UpdateState.Error("下载失败：$err")
            }
        }
    }

    /** 安装：未授权"安装未知应用"则先跳转授权页；已授权则直接拉起安装器 */
    fun install(release: LatestRelease, file: File) {
        val ctx = io.github.vstory.hook.notifyfilter.ServiceLocator.appContext
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = UpdateState.Idle
    }

    // ---- 内部实现 ----

    /**
     * 应用内直连下载（1.1.9，替代系统 DownloadManager）：
     * - 与 fetchJson 同一网络通道（手机端已验证可用）
     * - 显式 UA + 手动跟随重定向；分块写临时文件并汇报进度
     * - 完成后 sha256 校验；@return null=成功，否则失败原因（写入错误提示）
     */
    private suspend fun directDownload(urlStr: String, dest: File, release: LatestRelease): String? {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var url = urlStr
        repeat(5) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "NotifyFilter/${BuildConfig.VERSION_NAME}")
                when (conn.responseCode) {
                    in 200..299 -> {
                        val total = conn.contentLengthLong
                        tmp.parentFile?.mkdirs()
                        tmp.delete()
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        conn.inputStream.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(1 shl 16)
                                var done = 0L
                                while (true) {
                                    if (!kotlin.coroutines.coroutineContext.isActive) return "已取消"
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    md.update(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val s = _state.value
                                        if (s is UpdateState.Downloading) {
                                            _state.value = s.copy(progress = (done * 100 / total).toInt())
                                        }
                                    }
                                }
                            }
                        }
                        val actual = md.digest().joinToString("") { "%02x".format(it) }
                        // 2.0.1 Dev 10（P1-6）：fail-closed——更新源未声明 sha256 一律拒绝安装。
                        // 旧实现 `if (!expected.isNullOrBlank() && actual != expected)`，
                        // 即 latest*.json 不带 sha256 字段时**完全不校验**就把 APK 交给安装器，
                        // 最后一道完整性防线形同虚设（中间人只要删掉该字段即可）。
                        val expected = release.sha256?.lowercase()
                        if (expected.isNullOrBlank()) {
                            return "更新源未声明 sha256，拒绝下载（完整性无法校验）"
                        }
                        if (actual != expected) {
                            return "sha256 不匹配（响应被篡改或 CDN 污染）"
                        }
                        if (dest.exists()) dest.delete()
                        return if (tmp.renameTo(dest)) null else "写入失败"
                    }
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location")
                            ?: return "重定向缺少 Location"
                        url = loc
                    }
                    else -> return "HTTP ${conn.responseCode}"
                }
            } catch (e: Exception) {
                return if (!kotlin.coroutines.coroutineContext.isActive) "已取消" else (e.message ?: e.toString())
            } finally {
                conn?.disconnect()
            }
        }
        return "重定向次数过多"
    }

    override fun onCleared() {
        downloadJob?.cancel()
    }
}
