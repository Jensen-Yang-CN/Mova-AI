package com.mova.sceneai.ui.chat

/** 离线时只用端侧结构化意图选取短模板；不把模板冒充模型生成的短信。 */
data class OfflineReplyCard(val goal: String, val primary: String, val alternative: String)

object OfflineReplyTemplates {
    fun forGoal(goal: String, style: String): OfflineReplyCard {
        val polite = style == "礼貌" || style == "委婉" || style == "职场"
        val replies = when (goal) {
            "拒绝" -> if (polite)
                "谢谢你想到我，不过这次我没法答应，希望你能理解。" to "抱歉，这件事我现在没法帮上忙。"
            else "这次我没办法，抱歉。" to "我现在没法答应这件事。"
            "推迟" -> "我现在还不能确定，等安排清楚后再回复你，可以吗？" to "这件事我需要晚一点确认。"
            "接受" -> "好的，我可以。具体安排我们再确认一下。" to "可以，我们先确认一下时间和具体事项。"
            "降低冲突" -> "我理解你的想法，我们先把具体问题说清楚。" to "我想先听听你的顾虑，再一起商量。"
            "转移话题" -> "这件事我们先放一放，先聊聊眼前需要处理的部分吧。" to "我想先把当前的问题说清楚。"
            else -> "我看到了，想先确认一下具体安排，再给你明确答复。" to "我需要先了解一下具体情况。"
        }
        return OfflineReplyCard(goal, replies.first, replies.second)
    }
}
