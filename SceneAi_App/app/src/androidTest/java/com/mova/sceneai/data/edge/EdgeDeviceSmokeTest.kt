package com.mova.sceneai.data.edge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mova.sceneai.MovaApp
import com.mova.sceneai.MainActivity
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机验收：直接调用 App 的 GGUF/JNI 路径，不经过云端接口或屏幕键盘。
 * 首次运行前须在「设置」中导入本轮 Q4_K_M 模型；普通 CI 没有权重时跳过。
 */
@RunWith(AndroidJUnit4::class)
class EdgeDeviceSmokeTest {
    @Test
    fun chineseReplyRequestProducesValidLocalDecision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.filesDir, "edge/qwen3-0.6b-epoch3-Q4_K_M.gguf")
        assumeTrue("先在 App 设置中导入本轮 GGUF", model.isFile)

        // ColorOS 会限制没有前台 Activity 的测试进程；按实际使用方式先打开 App。
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        // 使用 UI 的同一个实例，避免测试额外加载第二份 397 MB 模型。
        val manager = MovaApp.instance.container.edge
        withTimeout(60_000) { manager.state.first { it.ready || it.loadingError != null } }
        assertTrue("模型大小、哈希与 JNI 加载应通过", manager.state.value.ready)

        val observation = manager.observe(
            "帮我拟一段短信回复给合伙人，背景是他想让我帮忙搬家，但我那天要出远门"
        )
        Log.i("MovaSmoke", "latency_ms=${observation.latencyMs} failure=${observation.failure} scene=${observation.decision?.scene} intent=${observation.decision?.intent}")
        assertNull("本机推理不应报错：${observation.failure}", observation.failure)
        val decision = observation.decision
        assertNotNull("模型输出应通过结构化契约校验", decision)
        assertEquals("chat", decision!!.scene)
        assertEquals("reply_suggest", decision.intent)
        assertTrue("应记录本机推理耗时", observation.latencyMs > 0)
    }
}
