// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置页和三级配置页共用的卡片外壳 */
@Composable
internal fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** false 时整行置灰且不可操作（例如监测关闭后，自动切换不可用） */
    enabled: Boolean = true,
) {
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.6f,
                ),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
internal fun ConfigField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

/** 服务商预设按钮：只补 Base URL 和模型名，Key 仍由用户自己填 */
@Composable
internal fun LlmPreset(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 翻译引擎列表里的一行。
 *
 * 右侧的序号是**尝试顺序**：链是按这个顺序依次试的，前面的通了就不会用后面的。
 * 这解决了"三家都填了不知道在用哪个"——看序号和开关就知道。
 *
 * @param order 尝试顺序，1 开始；离线兜底引擎传 null 不显示序号
 * @param status 状态行（已配置/未配置/免费额度说明…）
 * @param highlighted 真的在链里生效 → 状态文字用主题色，一眼能看出来
 * @param onOpen 有独立配置页时传回调，行尾会出现 ›
 * @param usageLine 用量小字（"本月 12.4 / 200 万"）。null = 不显示，
 *                  没填 Key 的引擎不传，也就不会出现"0 / 100 万"这种空行
 * @param usageProgress 用量比例 0f..∞。null = 不画进度条；>1 变红
 */
@Composable
internal fun EngineRow(
    order: Int?,
    name: String,
    status: String,
    enabled: Boolean,
    highlighted: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: (() -> Unit)? = null,
    usageLine: String? = null,
    usageProgress: Float? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onOpen != null) {
                    Modifier.clickable { onOpen() }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (order != null) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$order",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (usageLine != null) {
                Spacer(Modifier.height(2.dp))
                QuotaBar(percent = usageProgress ?: 0f, height = 3.dp)
                Spacer(Modifier.height(3.dp))
                Text(
                    usageLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Switch(checked = enabled, onCheckedChange = onToggle)

        if (onOpen != null) {
            Text(
                "›",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** 列表行之间的细分隔线 */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
    )
}

/** 小号说明文字 */
@Composable
internal fun HintText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
    Spacer(Modifier.height(4.dp))
}

/**
 * 一条跳厂商官方文档的链接。
 *
 * 放在填密钥的地方，是因为新用户最先卡住的**不是**"密钥填在哪"，
 * 而是"去哪注册、去哪开通、密钥怎么生成"。这三步全在厂商文档里，
 * 在 App 里复述一遍既写不全，也很快会过期——不如直接把人送到官方页面。
 *
 * 打不开浏览器也静默吞掉：配置页不该因为一个链接崩掉。
 */
@Composable
internal fun DocLink(text: String, url: String) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
