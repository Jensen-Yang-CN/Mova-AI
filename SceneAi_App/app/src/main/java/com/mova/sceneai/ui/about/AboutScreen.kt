package com.mova.sceneai.ui.about

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mova.sceneai.BuildConfig
import com.mova.sceneai.MovaApp
import com.mova.sceneai.ui.components.*

/** 产品动机与实际完成边界放在同一页，避免叙事与运行状态脱节。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val edge by MovaApp.instance.container.repository.edgeState.collectAsStateWithLifecycle()
    MovaScreen(title = "关于照应", subtitle = "小事有人接住，生活慢慢上手", onBack = onBack) {
        StoryIntro("MOVA", "离家之后，小事也有重量", "一条难回复的消息，一页没读完的通知，一顿不知道怎样做的饭。我们希望先接住眼前的小求助。")
        SectionHeader("一个轻松的起点")
        MovaCard {
            Text("面向离家求学、实习与独立生活的青年，让 AI 求助从具体场景开始。先明确自己的目标，再选择表达与行动。建议提供参考，决定权始终属于你。", style = MaterialTheme.typography.bodyMedium)
        }
        SectionHeader("手机理解，云端表达")
        MovaCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BulletList(listOf("本机小模型识别场景、意图和槽位，输出经过结构化校验。", "回复正文与复杂生成由云端模型完成，用户主动提交的内容会发送至所配置的服务。", "只处理主动输入或分享的内容；不自动读取第三方聊天。", "回复经用户确认后复制，App 不自动代为发送。"))
            }
        }
        SectionHeader("可以核对的完成状态")
        MovaCard {
            Column {
                KeyValueRow("版本", BuildConfig.VERSION_NAME)
                KeyValueRow("本机模型", edge.statusLabel)
                KeyValueRow("模型体积", "约 397 MB · Q4_K_M")
                KeyValueRow("主动位置触发", "后续规划")
                Spacer(Modifier.height(12.dp))
                Text("训练、评测、量化与 Android 接入过程可在项目仓库核对。离线分类可单独试运行，回复正文仍需要可用的模型服务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionHeader("作品与开源来源")
        MovaCard {
            Text("项目名 Mova-AI；产品名 Mova·照应。模型基于 Qwen3-0.6B 微调，推理使用 llama.cpp，客户端使用 Kotlin / Jetpack Compose，服务使用 FastAPI。品牌光球由 Compose Canvas 绘制。具体依赖、模型来源和 AI 辅助环节见项目说明。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
