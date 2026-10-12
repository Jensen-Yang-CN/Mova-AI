package com.mova.sceneai.data.city

/**
 * 与定位来源无关的城市状态机。真实 GPS 与模拟点位共用这里。
 * 初次识别只建立基线；新城市至少两次观测且持续一分钟才确认。
 * 以行政编码比较，避免同城不同区被当成跨城；一趟行程同城仅提醒一次。
 */
class CityChangeDetector(
    private val destination: String = "",
    private val confirmationMs: Long = 60_000,
    private val cooldownMs: Long = 10 * 60_000,
) {
    private var current: String? = null
    private var candidate: String? = null
    private var candidateSince = 0L
    private var observations = 0
    private var lastObserved = Long.MIN_VALUE
    private var lastAlert: Long? = null
    private val visited = mutableSetOf<String>()

    data class Result(val status: String, val entered: Boolean = false)

    fun accept(code: String, timeMs: Long): Result {
        if (timeMs < lastObserved) return Result("忽略过期观测")
        lastObserved = timeMs
        if (current == null) {
            current = code
            visited += code
            return Result("已建立出发城市基线")
        }
        if (code == current) {
            candidate = null
            observations = 0
            return Result("仍在当前城市，不重复提醒")
        }
        if (candidate != code) {
            candidate = code
            candidateSince = timeMs
            observations = 1
            return Result("发现新城市，等待第二次确认")
        }
        observations++
        if (observations < 2 || timeMs - candidateSince < confirmationMs) {
            return Result("新城市观测间隔不足，继续等待")
        }
        current = code
        candidate = null
        observations = 0
        if (!visited.add(code)) return Result("本次行程已见过该城市，不重复提醒")
        if (destination.isNotBlank() && destination != code) return Result("途经城市，等待目的地")
        if (lastAlert?.let { timeMs - it < cooldownMs } == true) return Result("提醒冷却中，仅更新城市")
        lastAlert = timeMs
        return Result("已稳定确认进入新城市", entered = true)
    }
}
