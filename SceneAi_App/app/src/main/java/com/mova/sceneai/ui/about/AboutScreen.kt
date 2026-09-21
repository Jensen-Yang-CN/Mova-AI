package com.mova.sceneai.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mova.sceneai.BuildConfig
import com.mova.sceneai.ui.components.BulletList
import com.mova.sceneai.ui.components.Divider
import com.mova.sceneai.ui.components.InfoBanner
import com.mova.sceneai.ui.components.KeyValueRow
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.SectionHeader

/**
 * 关于页。
 *
 * 刻意写清楚"端侧部分尚未接入"，而不是含糊其辞 ——
 * 一个诚实的项目说明比一个吹嘘的项目说明更经得起追问。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    MovaScreen(title = "关于 Mova-AI", onBack = onBack) {

        MovaCard {
            Column {
                Text("Mova-AI", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "不是等你开口，而是在你需要的那一刻，刚好出现。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeader("它想解决的问题")
        MovaCard {
            Column {
                Text(
                    "手机里的 AI 助手大多是「你问它才答」：先打开 App、先想好怎么问、再等它回。" +
                        "Mova-AI 想反过来 —— 让 AI 自己判断该不该出现。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionHeader("端云是怎么分工的")
        MovaCard {
            Column {
                Text(
                    "判断用最便宜的方式完成，不够再升级。这条规则同时管两层：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                BulletList(
                    listOf(
                        "视觉层：端侧轻量分类器先判断场景，命中且需要细看才上云端多模态模型",
                        "语言层：端侧小模型先做意图、难度与结构化抽取，置信度不足才升级云端大模型",
                        "结构性兜底：端侧输出的 JSON 与槽位校验不通过时，直接升级，不重试端侧",
                    )
                )
            }
        }

        SectionHeader("当前实现状态")
        MovaCard {
            Column {
                KeyValueRow("版本", BuildConfig.VERSION_NAME)
                KeyValueRow("构建类型", if (BuildConfig.DEBUG) "debug" else "release")
                KeyValueRow("云端能力", "已接入")
                KeyValueRow("端侧模型", "尚未接入")
                Divider()
                Spacer(Modifier.height(10.dp))
                InfoBanner(
                    text = "端侧小模型（蒸馏 + 量化后的 Qwen3 级别模型）尚未部署到 App 内。" +
                        "技术面板会如实显示这一状态；接入后无需改动界面，统计会自动开始区分端侧与云端。",
                )
            }
        }

        SectionHeader("技术要点")
        MovaCard {
            Column {
                BulletList(
                    listOf(
                        "端云路由写成代价敏感决策：用端侧省下的延迟，折算可接受的精度差",
                        "置信度经过校准后才用于决策，避免小模型过度自信导致误判",
                        "模型蒸馏分 Response / Logit / Feature 三层，端侧只学场景理解类能力",
                        "量化采用分层敏感性分析 + 比特分配求解，而非整模型一刀切",
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "详细设计见仓库 docs/ 目录下的三份文档。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
