package com.mova.sceneai.data.model

import kotlinx.serialization.Serializable

/**
 * 一次请求的执行轨迹。技术面板按此渲染。
 *
 * 设计意图：把"端云协同"从架构图变成**每天都能看到的真实数据**。
 * 端侧模型接入后，[executor] 会自动变成 EDGE，[routeReason] 会记录升级原因，
 * 界面与统计逻辑完全不需要改动。
 */
@Serializable
data class RequestTrace(
    val id: String,
    val timestamp: Long,
    val sceneId: String,
    val executor: String = ExecutorKind.CLOUD.id,
    val model: String? = null,
    /** 端到端总耗时（App 侧实测，含网络） */
    val totalMs: Long = 0,
    /** 服务端自报耗时 */
    val serverMs: Long? = null,
    /** 阶段拆解，用于延迟条形图 */
    val stages: Map<String, Long> = emptyMap(),
    val confidence: Double? = null,
    /** 路由原因，人类可读 */
    val routeReason: String = "",
    val success: Boolean = true,
    val errorTitle: String? = null,
) {
    val executorKind: ExecutorKind get() = ExecutorKind.from(executor)
    val scene: Scene get() = Scene.from(sceneId)
}

/**
 * 记录页的一条历史。payload 以 JSON 原文存储，
 * 好处是新增/修改结果字段时不需要做本地数据迁移。
 */
@Serializable
data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val sceneId: String,
    val title: String,
    val subtitle: String,
    val trace: RequestTrace,
    val payloadJson: String,
) {
    val scene: Scene get() = Scene.from(sceneId)
}
