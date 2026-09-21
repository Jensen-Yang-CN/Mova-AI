package com.mova.sceneai.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mova.sceneai.MovaApp
import com.mova.sceneai.data.model.ChatDto
import com.mova.sceneai.data.model.ExecutorKind
import com.mova.sceneai.data.model.FoodDto
import com.mova.sceneai.data.model.ReadingDto
import com.mova.sceneai.data.model.Scene
import com.mova.sceneai.ui.components.BulletList
import com.mova.sceneai.ui.components.ChipCloud
import com.mova.sceneai.ui.components.EmptyState
import com.mova.sceneai.ui.components.KeyValueRow
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.NumberedList
import com.mova.sceneai.ui.components.ResultHeader
import com.mova.sceneai.ui.components.Tag
import com.mova.sceneai.core.Fmt

/**
 * 记录详情。
 *
 * 直接复用与场景页一致的结果结构 —— 用户在任何入口看到的结果长得一样，
 * 不需要重新学一遍怎么读。
 */
@Composable
fun ResultDetailScreen(entryId: String, onBack: () -> Unit) {
    val repository = MovaApp.instance.container.repository
    val json = MovaApp.instance.container.json
    val entries by repository.history.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.id == entryId }

    MovaScreen(
        title = entry?.scene?.label ?: "结果详情",
        subtitle = entry?.let { Fmt.relativeTime(it.timestamp) },
        onBack = onBack,
    ) {
        if (entry == null) {
            EmptyState(
                emoji = "🔍",
                title = "这条记录已经不在了",
                desc = "可能已经被清理，或者记录列表发生了变化。",
            )
            return@MovaScreen
        }

        val executor = ExecutorKind.from(entry.trace.executor)

        when (entry.scene) {
            Scene.FOOD -> runCatching {
                json.decodeFromString(FoodDto.serializer(), entry.payloadJson)
            }.getOrNull()?.let { dto ->
                MovaCard {
                    Column {
                        ResultHeader(
                            title = dto.dish.ifBlank { "识别结果" },
                            subtitle = Fmt.duration(entry.trace.totalMs),
                            executor = executor,
                            copyText = dto.steps.joinToString("\n"),
                        )
                        if (dto.ingredients.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("食材", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            ChipCloud(dto.ingredients)
                        }
                        if (dto.steps.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("做法", style = MaterialTheme.typography.titleSmall)
                            NumberedList(dto.steps)
                        }
                        if (dto.tips.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Text("💡", fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(dto.tips, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Scene.READING_TEXT, Scene.READING_IMAGE, Scene.READING_PDF -> runCatching {
                json.decodeFromString(ReadingDto.serializer(), entry.payloadJson)
            }.getOrNull()?.let { dto ->
                MovaCard {
                    Column {
                        ResultHeader(
                            title = "阅读总结",
                            subtitle = Fmt.duration(entry.trace.totalMs),
                            executor = executor,
                            copyText = dto.summary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(dto.summary, style = MaterialTheme.typography.bodyMedium)
                        if (dto.keyPoints.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("要点", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.weight(1f))
                                dto.difficulty?.let {
                                    Tag(
                                        it,
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            BulletList(dto.keyPoints)
                        }
                    }
                }
            }

            else -> runCatching {
                json.decodeFromString(ChatDto.serializer(), entry.payloadJson)
            }.getOrNull()?.let { dto ->
                MovaCard {
                    Column {
                        ResultHeader(
                            title = "回复建议",
                            subtitle = dto.style,
                            executor = executor,
                            copyText = dto.reply,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(dto.reply, style = MaterialTheme.typography.bodyLarge)
                        if (dto.alternatives.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("备选", style = MaterialTheme.typography.titleSmall)
                            BulletList(dto.alternatives)
                        }
                        if (dto.explain.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                dto.explain,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 同一条记录的执行元信息：与记录页徽标同源，保证口径一致
        MovaCard {
            Column {
                Text("这次是怎么完成的", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                KeyValueRow("执行方式", executor.label)
                KeyValueRow("端到端耗时", Fmt.duration(entry.trace.totalMs))
                entry.trace.serverMs?.let { KeyValueRow("服务端耗时", Fmt.duration(it)) }
                entry.trace.model?.let { KeyValueRow("模型", it) }
                KeyValueRow("路由原因", entry.trace.routeReason.ifBlank { "—" })
            }
        }
    }
}
