package io.github.vstory.hook.notifyfilter.notify

import android.app.Notification
import io.github.vstory.hook.notifyfilter.data.ProtectTypes
import io.github.vstory.hook.notifyfilter.data.db.DECISION_CONVERSATION
import io.github.vstory.hook.notifyfilter.data.db.DECISION_MEDIA
import io.github.vstory.hook.notifyfilter.data.db.DECISION_ONGOING

/**
 * 内置通知类型保护（可关，见 [ProtectTypes]）：
 * - 媒体通知：音乐/视频 APP 的播放控件（MediaStyle / MediaSession / category=transport）
 * - 对话通知：各类会话通知（category=msg / MessagingStyle / 会话标题与消息列表）
 * - 常驻通知：进度条、来电等（FLAG_ONGOING_EVENT / category=call）
 *
 * 命中且该类型保护开启 → 返回对应决策常量（仅入库留档）；命中但保护被关 → 返回 null，
 * 通知照常进入「规则 → 白名单 → AI」判定链。
 * 白名单逻辑在 [RuleEngine] 中（APP 级，跳过 AI 过滤，规则仍生效）。
 */
object NotificationProtector {

    fun classifyType(
        notification: Notification?,
        protection: ProtectTypes = ProtectTypes(),
    ): String? {
        val type = detectType(notification ?: return null) ?: return null
        return type.takeIf { protection.isProtected(it) }
    }

    /**
     * 类型识别本体，与开关无关。
     * 判断顺序（常驻 → 媒体 → 对话）不要动：FLAG_ONGOING_EVENT 与 CATEGORY_MESSAGE 可能同时成立，
     * 换序会让同一条通知的类型在 NLS 端与模块端之间漂移。
     */
    private fun detectType(notification: Notification): String? {
        val extras = notification.extras

        // 常驻：进度条 / 来电 / 下载进度等
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return DECISION_ONGOING
        if (notification.category == Notification.CATEGORY_CALL) return DECISION_ONGOING

        // 媒体：播放控件（Android 无 CATEGORY_MEDIA，媒体会话通知用 CATEGORY_TRANSPORT）
        if (notification.category == Notification.CATEGORY_TRANSPORT) return DECISION_MEDIA
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if (template.contains("MediaStyle")) return DECISION_MEDIA
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return DECISION_MEDIA

        // 对话：MessagingStyle / 消息类
        if (notification.category == Notification.CATEGORY_MESSAGE) return DECISION_CONVERSATION
        if (template.contains("MessagingStyle")) return DECISION_CONVERSATION
        if (extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE)) return DECISION_CONVERSATION
        if (extras.containsKey(Notification.EXTRA_MESSAGES)) return DECISION_CONVERSATION

        return null
    }
}
