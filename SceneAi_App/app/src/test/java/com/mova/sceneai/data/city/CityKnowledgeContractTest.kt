package com.mova.sceneai.data.city

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 卡片升级保留旧记录；云端扩展字段不影响问答引用反序列化。 */
class CityKnowledgeContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun oldSavedCardStillLoadsWithEmptyKnowledge() {
        val card = json.decodeFromString<CityGuide>("""{"city_code":"370200","city_name":"青岛市","version":3,"foods":["旧名称"]}""")
        assertEquals(3, card.version)
        assertTrue(card.knowledge.isEmpty())
        assertEquals("", card.knowledgeStatus)
    }

    @Test fun answerKeepsSourceAndIgnoresServerMetrics() {
        val answer = json.decodeFromString<CityAnswer>("""{"answer":"测试资料 [1]","grounded":true,"mode":"rag","latency_ms":12,"citations":[{"id":"test","name":"测试景点","kind":"sight","text":"测试摘要","source_title":"资料标题","source_url":"https://example.com/source","verified_on":"2026-10-29"}]}""")
        assertTrue(answer.grounded)
        assertEquals("https://example.com/source", answer.citations.single().sourceUrl)
        assertEquals("2026-10-29", answer.citations.single().verifiedOn)
    }
}
