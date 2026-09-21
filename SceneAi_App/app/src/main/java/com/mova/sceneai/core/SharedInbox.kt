package com.mova.sceneai.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 分享来源，用于界面文案与埋点区分。 */
enum class ShareSource(val label: String) {
    /** 系统分享面板（微信长按消息 → 分享 → Mova-AI） */
    SEND("来自分享"),
    /** 文本选择菜单的「处理文本」 */
    PROCESS_TEXT("来自选中文字"),
}

data class SharedText(val text: String, val source: ShareSource)

/**
 * 分享收件箱。
 *
 * 解决的问题：`ACTION_SEND` 的内容是在 **Activity 的 Intent** 里进来的，
 * 而消费它的是 **Compose 里的 ViewModel**，两者之间没有直接通路。
 *
 * 用一个小小的 StateFlow 中转：
 *   MainActivity 收到 Intent → inbox.post(...)
 *   根组件观察到有内容 → 导航到聊天页
 *   聊天页的 ViewModel → inbox.take() 取走并消费
 *
 * 为什么不做成把文本直接塞进导航参数：因为消息内容可能很长（微信长文、群公告），
 * 塞进路由字符串既难看又容易在转义上出问题。用状态传递更干净。
 *
 * ⚠️ 取走即清空（take 而非 observe），避免用户切回聊天页时被重复触发一次生成。
 */
class SharedInbox {

    private val _pending = MutableStateFlow<SharedText?>(null)
    val pending: StateFlow<SharedText?> = _pending.asStateFlow()

    fun post(text: String, source: ShareSource = ShareSource.SEND) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // 截断到契约允许的范围，避免把整篇微信群公告丢给模型
        _pending.value = SharedText(trimmed.take(MAX_CHARS), source)
    }

    /** 取走并清空。返回 null 表示没有待处理的分享内容。 */
    fun take(): SharedText? {
        val current = _pending.value ?: return null
        _pending.value = null
        return current
    }

    companion object {
        const val MAX_CHARS = 2000
    }
}
