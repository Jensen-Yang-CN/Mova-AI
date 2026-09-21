package com.mova.sceneai.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mova.sceneai.MovaApp
import com.mova.sceneai.core.Fmt
import com.mova.sceneai.data.model.ExecutorKind
import com.mova.sceneai.data.model.HistoryEntry
import com.mova.sceneai.data.model.SceneGroup
import com.mova.sceneai.ui.components.EmptyState
import com.mova.sceneai.ui.components.ExecutorBadge
import com.mova.sceneai.ui.components.MovaCard
import com.mova.sceneai.ui.components.MovaChipRow
import com.mova.sceneai.ui.components.MovaScreen
import com.mova.sceneai.ui.components.SectionHeader

/**
 * 记录页。
 *
 * 每条记录带**执行器徽标**（端侧 / 云端），所以"端云协同"不是 PPT 上的架构图，
 * 而是用户翻一翻就能看到的实际分布。
 */
@Composable
fun HistoryScreen(onOpenEntry: (String) -> Unit, onOpenFood: () -> Unit) {
    val repository = MovaApp.instance.container.repository
    val entries by repository.history.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(SceneGroup.ALL) }

    val filtered = remember(entries, filter) {
        if (filter == SceneGroup.ALL) entries else entries.filter { it.scene.group == filter }
    }

    MovaScreen(title = "记录", subtitle = "做过的每一次，都能翻回来") {
        MovaChipRow(
            options = SceneGroup.filters.map { it.label },
            selected = filter.label,
            onSelect = { label ->
                filter = SceneGroup.filters.firstOrNull { it.label == label } ?: SceneGroup.ALL
            },
        )

        if (filtered.isEmpty()) {
            EmptyState(
                emoji = "🗂️",
                title = if (entries.isEmpty()) "还没有记录" else "这个分类下还没有记录",
                desc = if (entries.isEmpty()) {
                    "用过一次功能之后，结果会自动存在这里，方便随时翻回来复制。"
                } else {
                    "换个分类看看，或者去试试其他场景。"
                },
                actionLabel = if (entries.isEmpty()) "去试试做饭助手" else null,
                onAction = if (entries.isEmpty()) onOpenFood else null,
            )
            return@MovaScreen
        }

        SectionHeader("共 ${filtered.size} 条", "点击任意一条查看完整结果")

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            filtered.forEach { entry ->
                HistoryRow(entry) { onOpenEntry(entry.id) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    MovaCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.scene.emoji, fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title.ifBlank { entry.scene.label },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Fmt.relativeTime(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Fmt.duration(entry.trace.totalMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!entry.trace.success) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            ExecutorBadge(entry.trace.executorKind)
        }
    }
}
