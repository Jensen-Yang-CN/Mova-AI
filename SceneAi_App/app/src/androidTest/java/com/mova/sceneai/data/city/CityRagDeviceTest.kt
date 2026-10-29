package com.mova.sceneai.data.city

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mova.sceneai.MainActivity
import com.mova.sceneai.MovaApp
import com.mova.sceneai.data.remote.ApiFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真机联调使用手机当前服务地址，不改设置；点位是公开模拟输入。 */
@RunWith(AndroidJUnit4::class)
class CityRagDeviceTest {
    private fun openApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun guideAndAnswerKeepTheirSourcesOnDevice(): Unit = runBlocking {
        openApp()
        val container = MovaApp.instance.container
        val settings = container.settings.current()
        val api = ApiFactory.createApi(ApiFactory.createClient(), container.json)
        val guide = withTimeout(20_000) { api.cityGuide(settings.endpoint("city/guide"), "370200", "青岛市") }
        assertEquals(4, guide.version)
        assertEquals("available", guide.knowledgeStatus)
        assertEquals(5, guide.knowledge.size)
        assertTrue(guide.knowledge.all { it.sourceUrl.startsWith("https://") })

        // 调用 UI 的同一个管理器，覆盖 Retrofit 解码、状态更新与真实后端。
        val trip = container.cityTrip
        trip.askCity("370200", "当地有哪些特色美食")
        val state = withTimeout(25_000) { trip.state.first {
            "370200" !in it.askingCities && (it.answers["370200"] != null || it.answerErrors["370200"] != null)
        } }
        assertNull(state.answerErrors["370200"])
        val answer = state.answers.getValue("370200")
        assertTrue(answer.grounded)
        assertTrue(answer.citations.isNotEmpty())
        assertTrue(answer.citations.all { it.id.startsWith("qd-food-") })
        Log.i("MovaRagTest", "food mode=${answer.mode} citations=${answer.citations.size} revision=${answer.kbRevision}")

        val live = withTimeout(20_000) { api.askCity(settings.endpoint("city/ask"),
            CityQuestionRequest("370200", "八大关今天营业到几点")) }
        assertFalse(live.grounded)
        assertTrue(live.citations.isEmpty())
        assertEquals("insufficient", live.mode)
    }

    @Test fun simulatedArrivalSavesKnowledgeCardWithoutRealLocation(): Unit = runBlocking {
        openApp()
        val trip = MovaApp.instance.container.cityTrip
        trip.begin(simulated = true)
        try {
            withTimeout(60_000) {
                trip.submit(CityFixRequest(114.30, 30.60, "gcj02"), 0L)
                trip.submit(CityFixRequest(120.3844, 36.0625, "gcj02"), 120_000L)
                trip.submit(CityFixRequest(120.3844, 36.0625, "gcj02"), 180_000L)
            }
            val arrival = trip.state.value.arrivals.first()
            assertEquals("370200", arrival.guide.code)
            assertEquals(4, arrival.guide.version)
            assertTrue(arrival.simulated)
            assertEquals("available", arrival.guide.knowledgeStatus)
            Log.i("MovaRagTest", "arrival code=370200 simulated=true knowledge=${arrival.guide.knowledge.size}")
        } finally {
            trip.end()
        }
    }
}
