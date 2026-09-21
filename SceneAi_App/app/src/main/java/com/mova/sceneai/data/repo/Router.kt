package com.mova.sceneai.data.repo

import com.mova.sceneai.data.model.ExecutorKind
import com.mova.sceneai.data.model.Scene
import kotlin.math.max

/**
 * 端云路由决策。
 *
 * 这是 docs/01 §2 里那个"代价敏感级联路由"公式在代码里的落地：
 *
 *     停在端侧  ⟺  p_edge ≥ p_cloud − λ·(L_cloud − L_edge) − μ·C
 *
 * 直观含义：**端侧每次省下的毫秒，可以"买"多少精度损失。**
 * λ 越大，越倾向留在端侧（省时间）；λ 越小，越倾向升级云端（保精度）。
 *
 * ⚠️ 当前端侧模型尚未部署，因此 [decide] 实际上恒返回 CLOUD，但规则已经完整实现。
 * 端侧接入后只需把 `edgeReady = true`，整条链路自动生效，UI 与统计无需改动。
 */
object Router {

    data class Decision(
        val kind: ExecutorKind,
        /** 人类可读的路由原因，会直接显示在技术面板上 */
        val reason: String,
    )

    /** 标定常量的默认值。真实数值应由 docs/01 §7.2 的路由消融实验反推。 */
    const val DEFAULT_LAMBDA_MS = 0.00025
    const val DEFAULT_CLOUD_ACCURACY_TARGET = 0.85
    const val ASSUMED_CLOUD_LATENCY_MS = 1200L
    const val ASSUMED_EDGE_LATENCY_MS = 220L

    fun decide(
        scene: Scene,
        edgeReady: Boolean,
        edgePreferred: Boolean,
        /** 端侧模型自报并经校准后的置信度；未校准前不应传入 */
        edgeConfidence: Double? = null,
        /** 端侧输出的结构化校验是否通过；false 表示必须升级 */
        schemaValid: Boolean? = null,
        /** 端侧模型对任务复杂度的打分 [0,1]，越高越该上云 */
        complexity: Double? = null,
        cloudAccuracyTarget: Double = DEFAULT_CLOUD_ACCURACY_TARGET,
        lambdaMs: Double = DEFAULT_LAMBDA_MS,
        edgeLatencyMs: Long = ASSUMED_EDGE_LATENCY_MS,
        cloudLatencyMs: Long = ASSUMED_CLOUD_LATENCY_MS,
    ): Decision {

        // ① 端侧不可用 → 只能上云（当前的真实路径）
        if (!edgeReady) {
            return Decision(
                ExecutorKind.CLOUD,
                "端侧模型未部署，本次直连云端",
            )
        }

        // ② 用户显式要求优先云端
        if (!edgePreferred) {
            return Decision(
                ExecutorKind.CLOUD,
                "设置中已选择优先使用云端",
            )
        }

        // ③ 结构性兜底：槽位/JSON 校验失败不重试端侧，直接升级
        //    理由：同一模型同一种失败模式，重试的期望收益低而延迟翻倍
        if (schemaValid == false) {
            return Decision(
                ExecutorKind.CLOUD,
                "端侧结构化校验未通过，自动升级云端",
            )
        }

        // ④ 复杂度阈值：端侧自评很复杂的任务直接上云，不必先跑一遍
        if (complexity != null && complexity > COMPLEXITY_CEILING) {
            return Decision(
                ExecutorKind.CLOUD,
                "任务复杂度 %.2f 超过端侧处理上限，直接走云端".format(complexity),
            )
        }

        // ⑤ 代价敏感决策：用"省下的延迟"折算可接受的精度差
        val confidence = edgeConfidence
            ?: return Decision(
                ExecutorKind.CLOUD,
                "端侧置信度不可用，保守起见走云端",
            )

        val latencySavingMs = max(0L, cloudLatencyMs - edgeLatencyMs)
        val tolerableGap = lambdaMs * latencySavingMs
        val required = cloudAccuracyTarget - tolerableGap

        return if (confidence >= required) {
            Decision(
                ExecutorKind.EDGE,
                "端侧置信度 %.2f ≥ 门槛 %.2f（云端延迟代价折算后），本地直接完成"
                    .format(confidence, required),
            )
        } else {
            Decision(
                ExecutorKind.CLOUD,
                "端侧置信度 %.2f 低于门槛 %.2f，升级云端保精度"
                    .format(confidence, required),
            )
        }
    }

    /** 端侧自评复杂度超过此值即不再尝试端侧。 */
    private const val COMPLEXITY_CEILING = 0.85
}
