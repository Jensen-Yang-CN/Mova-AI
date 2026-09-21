package com.mova.sceneai.data.model

import kotlinx.serialization.Serializable

/**
 * 场景标识。集中定义，避免各页面用裸字符串。
 * [group] 用于记录页的筛选 chips。
 */
enum class Scene(
    val id: String,
    val label: String,
    val emoji: String,
    val group: SceneGroup,
) {
    FOOD("food", "做饭助手", "🍜", SceneGroup.FOOD),
    READING_TEXT("reading_text", "阅读总结", "📄", SceneGroup.READING),
    READING_IMAGE("reading_image", "图片阅读", "🖼️", SceneGroup.READING),
    READING_PDF("reading_pdf", "PDF 阅读", "📕", SceneGroup.READING),
    CHAT("chat", "聊天回复", "💬", SceneGroup.CHAT),
    CHAT_REWRITE("chat_rewrite", "语气改写", "✍️", SceneGroup.CHAT),
    CHAT_CONTEXT("chat_context", "多轮回复", "🧵", SceneGroup.CHAT),
    UNKNOWN("unknown", "其他", "✨", SceneGroup.OTHER);

    companion object {
        fun from(id: String?): Scene = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

enum class SceneGroup(val label: String) {
    ALL("全部"),
    FOOD("做饭"),
    READING("阅读"),
    CHAT("聊天"),
    OTHER("其他");

    companion object {
        /** 记录页的筛选顺序（不含 OTHER） */
        val filters: List<SceneGroup> = listOf(ALL, FOOD, READING, CHAT)
    }
}

/** 执行器类型。端云协同在 UI 上的最小表达。 */
enum class ExecutorKind(val id: String, val label: String) {
    EDGE("edge", "端侧"),
    CLOUD("cloud", "云端");

    companion object {
        fun from(id: String?): ExecutorKind = if (id == "edge") EDGE else CLOUD
    }
}
