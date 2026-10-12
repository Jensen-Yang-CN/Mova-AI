package com.mova.sceneai.data.edge

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 从训练契约导出的模板读入完整 Qwen3 非思考前缀，避免 Android 端再次套聊天模板。 */
class EdgePrompt(context: Context) {
    private val template = context.assets.open("edge_prompt.template.txt")
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    private val signalsMark = "__MOVA_SIGNALS__"
    private val utteranceMark = "__MOVA_UTTERANCE__"

    init {
        require(template.countOf(signalsMark) == 1 && template.countOf(utteranceMark) == 1) {
            "端侧提示模板损坏"
        }
    }

    /** 不索取位置/其他应用访问权限；缺失信号明确写未知。 */
    fun render(utterance: String): String {
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        val signals = "时间：$time | 前台 App：Mova-AI | 位置：未知 | 运动：未知 | 屏幕：亮"
        val first = template.indexOf(signalsMark)
        val second = template.indexOf(utteranceMark)
        require(first >= 0 && second > first) { "端侧提示模板占位符顺序错误" }
        return buildString {
            append(template, 0, first)
            append(signals)
            append(template, first + signalsMark.length, second)
            append(utterance)
            append(template, second + utteranceMark.length, template.length)
        }
    }

    private fun String.countOf(value: String): Int = windowed(value.length).count { it == value }
}
