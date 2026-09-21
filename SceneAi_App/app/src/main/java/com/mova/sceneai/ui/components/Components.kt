package com.mova.sceneai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mova.sceneai.core.ErrorAction
import com.mova.sceneai.core.Fmt
import com.mova.sceneai.core.UiError
import com.mova.sceneai.data.model.ExecutorKind
import com.mova.sceneai.ui.theme.LocalMovaSemanticColors
import kotlinx.coroutines.launch

/** 全局 Snackbar。由 MovaRoot 提供，任何组件都能弹出统一风格的短提示。 */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbar 未提供：请确认界面位于 MovaRoot 之内")
}

// ============================================================
// 基础容器
// ============================================================

@Composable
fun MovaCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    contentPadding: Int = 16,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

    if (onClick == null) {
        Card(modifier.fillMaxWidth(), shape, colors, elevation) {
            Box(Modifier.padding(contentPadding.dp)) { content() }
        }
    } else {
        Card(onClick, modifier.fillMaxWidth(), shape = shape, colors = colors, elevation = elevation) {
            Box(Modifier.padding(contentPadding.dp)) { content() }
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ============================================================
// 徽标与状态
// ============================================================

/** 端侧 / 云端徽标 —— 端云协同在界面上的最小可见单元。 */
@Composable
fun ExecutorBadge(kind: ExecutorKind, modifier: Modifier = Modifier) {
    val semantic = LocalMovaSemanticColors.current
    val (bg, fg) = when (kind) {
        ExecutorKind.EDGE -> semantic.edgeContainer to semantic.onEdgeContainer
        ExecutorKind.CLOUD -> semantic.cloudContainer to semantic.onCloudContainer
    }
    Box(modifier.background(bg, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(kind.label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
fun Tag(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(container, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
fun Dot(color: Color, size: Int = 8, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).background(color, CircleShape))
}

// ============================================================
// 输入 / 选择
// ============================================================

/**
 * 单选 chips 行。风格、目标、筛选全部复用它 ——
 * 全 App 只有一种"多选一"交互，用户学一次就够。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MovaChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option) },
                leadingIcon = if (option == selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
}

// ============================================================
// 状态占位：空 / 错误 / 加载
// ============================================================

@Composable
fun EmptyState(
    emoji: String,
    title: String,
    desc: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * 错误卡：严格遵循"三件套"文案规范 —— 发生了什么 / 为什么 / 怎么办，
 * 并且永远给用户留一个可点的出路。
 */
@Composable
fun ErrorCard(
    error: UiError,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    error.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                error.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (error.hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    error.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (error.action) {
                    ErrorAction.OpenSettings -> Button(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(error.actionLabel ?: "去设置")
                    }

                    ErrorAction.Retry -> Button(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(error.actionLabel ?: "重试")
                    }

                    ErrorAction.None -> if (error.retryable) {
                        OutlinedButton(onClick = onRetry) { Text("重试") }
                    }
                }
                if (error.action != ErrorAction.OpenSettings) {
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }
        }
    }
}

/**
 * 骨架屏 + 阶段文案。
 * 阶段名直接对应后端两级流水线的真实阶段，让"技术过程"对用户可见。
 */
@Composable
fun LoadingStages(stages: List<String>, activeIndex: Int, modifier: Modifier = Modifier) {
    MovaCard(modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    stages.getOrElse(activeIndex) { stages.lastOrNull() ?: "处理中…" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(14.dp))
            stages.forEachIndexed { index, stage ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    val done = index < activeIndex
                    val active = index == activeIndex
                    if (done) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = LocalMovaSemanticColors.current.success,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Dot(
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            size = if (active) 10 else 8,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stage,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            done -> MaterialTheme.colorScheme.onSurfaceVariant
                            active -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
    }
}

/** 顶部细横幅：用于降级等"知情但不必打断"的信息。 */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Row(
        modifier.fillMaxWidth().background(container, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = contentColor)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = contentColor)
    }
}

// ============================================================
// 结果展示
// ============================================================

@Composable
fun CopyIconButton(text: String, modifier: Modifier = Modifier, toast: String = "已复制") {
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            scope.launch { snackbar.showSnackbar(toast) }
        },
        modifier = modifier,
    ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "复制",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 结果卡统一头部：标题 + 副标题 + 端侧/云端徽标 + 复制。 */
@Composable
fun ResultHeader(
    title: String,
    copyText: String,
    subtitle: String? = null,
    executor: ExecutorKind? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (executor != null) {
            ExecutorBadge(executor)
            Spacer(Modifier.width(4.dp))
        }
        CopyIconButton(copyText)
    }
}

/** 带序号的步骤列表。 */
@Composable
fun NumberedList(items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        items.forEachIndexed { index, item ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Box(
                    Modifier.size(22.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 标签云：食材、要点共用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipCloud(items: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Tag(
                text = item,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun BulletList(items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        items.forEach { item ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Text("·", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 关键指标行：技术面板与设置页复用。 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 延迟拆解用的横向条。 */
@Composable
fun MeterRow(label: String, value: Long, total: Long, color: Color, modifier: Modifier = Modifier) {
    val fraction = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
    Column(modifier.padding(vertical = 4.dp)) {
        Row {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(Fmt.duration(value), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
fun OutlinedActionButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

@Composable
fun SolidActionButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

@Composable
fun SurfaceSection(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Box(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
fun BadgeText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
