package com.mova.sceneai.data.edge

import org.json.JSONArray
import org.json.JSONObject

/** 0.6B 模型只负责这份结构化决策，不负责生成用户可见的短信或摘要。 */
data class EdgeDecision(
    val scene: String,
    val intent: String,
    val complexity: Double,
    val confidence: Double,
    val needCloud: Boolean,
    val slots: JSONObject,
    val reason: String?,
) {
    val goal: String? get() = slots.optString("goal").takeIf { it.isNotBlank() && it != "不指定" }
}

/**
 * 与 scene_ai_server/pipeline/contract.py 保持一致的端侧校验。
 * 模型自报的 confidence 没有校准；校验通过也不代表可用它跳过云端正文生成。
 */
object EdgeDecisionParser {
    private val intents = mapOf(
        "food" to setOf("diet_advice", "recipe_lookup", "ingredient_check", "none"),
        "reading" to setOf("summarize", "key_points", "difficulty_judge", "none"),
        "chat" to setOf("reply_suggest", "rewrite", "tone_adjust", "none"),
        "location" to setOf("nearby_hint", "timing_reminder", "none"),
        "none" to setOf("none"),
    )
    private val slotNames = mapOf(
        "food" to setOf("ingredients", "meal"),
        "reading" to setOf("text_length", "source", "topic"),
        "chat" to setOf("target_tone", "goal", "relation"),
        "location" to setOf("place_type", "time_bucket"),
        "none" to emptySet(),
    )

    fun parse(raw: String): EdgeDecision {
        val text = raw.trim()
        require(text.startsWith('{') && text.endsWith('}')) { "端侧未返回纯 JSON 对象" }
        val obj = JSONObject(text)
        val allowed = setOf("scene", "intent", "complexity", "confidence", "need_cloud", "slots", "reason")
        require(obj.keys().asSequence().all { it in allowed }) { "端侧输出含未定义字段" }

        val scene = requiredString(obj, "scene")
        val intent = requiredString(obj, "intent")
        require(intent in (intents[scene] ?: emptySet())) { "scene 与 intent 不匹配" }
        val complexity = unitNumber(obj, "complexity")
        val confidence = unitNumber(obj, "confidence")
        val needCloud = obj.opt("need_cloud") as? Boolean
            ?: throw IllegalArgumentException("need_cloud 必须是布尔值")
        val slots = obj.opt("slots") as? JSONObject
            ?: throw IllegalArgumentException("slots 必须是对象")
        require(slots.keys().asSequence().all { it in slotNames.getValue(scene) }) {
            "slots 含场景之外的字段"
        }
        // 服务端契约允许可选 reason 为任意字符串（包括空串）。
        val reason = if (obj.has("reason")) {
            obj.opt("reason") as? String
                ?: throw IllegalArgumentException("reason 必须是字符串")
        } else null
        require(reason == null || reason.length <= 60) { "reason 超过 60 字" }

        when (scene) {
            "food" -> {
                val ingredients = slots.opt("ingredients") as? JSONArray
                    ?: throw IllegalArgumentException("缺少 ingredients")
                require(ingredients.length() <= 8 &&
                    (0 until ingredients.length()).all { ingredients.opt(it) is String }) {
                    "ingredients 必须是最多 8 项的字符串数组"
                }
                optionalEnum(slots, "meal", setOf("早饭", "午饭", "晚饭", "夜宵"))
            }
            "reading" -> {
                val length = slots.opt("text_length")
                require(length is Int && length >= 0) { "text_length 必须是非负整数" }
                requiredEnum(slots, "source", setOf("text", "image", "pdf"))
                if (slots.has("topic")) {
                    require(requiredString(slots, "topic").length <= 12) { "topic 超过 12 字" }
                }
            }
            "chat" -> {
                requiredEnum(slots, "target_tone", setOf("自然", "礼貌", "委婉", "幽默", "职场", "亲密", "冷处理"))
                requiredEnum(slots, "goal", setOf("接受", "拒绝", "推迟", "转移话题", "降低冲突", "不指定"))
                optionalEnum(slots, "relation", setOf("家人", "朋友", "同事", "上级", "陌生人"))
            }
            "location" -> {
                requiredEnum(slots, "place_type", setOf("超市", "商场", "校园", "餐厅", "交通枢纽", "其他"))
                optionalEnum(slots, "time_bucket", setOf("morning", "noon", "afternoon", "evening", "night"))
            }
        }
        return EdgeDecision(scene, intent, complexity, confidence, needCloud, slots, reason)
    }

    private fun requiredString(obj: JSONObject, key: String): String =
        (obj.opt(key) as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$key 必须是非空字符串")

    private fun unitNumber(obj: JSONObject, key: String): Double {
        val number = obj.opt(key) as? Number
            ?: throw IllegalArgumentException("$key 必须是数字")
        val value = number.toDouble()
        require(value.isFinite() && value in 0.0..1.0) { "$key 超出 [0,1]" }
        return value
    }

    private fun requiredEnum(obj: JSONObject, key: String, values: Set<String>) {
        require(requiredString(obj, key) in values) { "$key 取值非法" }
    }

    private fun optionalEnum(obj: JSONObject, key: String, values: Set<String>) {
        if (obj.has(key)) requiredEnum(obj, key, values)
    }
}
