package com.mova.sceneai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 全 App 统一的页面骨架。
 *
 * 统一在这里处理状态栏 inset 与标题栏，避免每个页面各写一套导致
 * 有的页面被状态栏遮住、有的页面顶部多出一块空白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovaScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    horizontalPadding: Int = 16,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        val contentModifier = Modifier
            .fillMaxSize()
            .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
            .padding(horizontal = horizontalPadding.dp)
            .padding(bottom = 20.dp)

        Column(
            modifier = contentModifier,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** 页面内的标准间距，避免各处出现 8/10/12/14 混用。 */
object Spacing {
    val contentPadding = PaddingValues(horizontal = 16.dp)
}
