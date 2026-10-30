// Copyright (c) 2026 January
// Licensed under the PolyForm Noncommercial License 1.0.0 (see LICENSE).

package com.screensub.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/**
 * 滑块 + 可手填数值。
 *
 * 为什么不能只有滑块：字号 12~32 的区间里，每一档只有 1sp 的差别，
 * 手指在滑块上根本对不准；不透明度 0~55% 更是 1% 一档。
 * 想精确设成 21sp / 38% 只能靠输入框。
 *
 * 输入框是"边输边生效"：只要解析出的数字在范围内就立刻应用，
 * 超出范围或半截输入（"1." 这种）就只是留在框里不生效——
 * 不能一边打字一边把值改掉，否则输 "2" 会先跳到 2 再跳到 25。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderWithInput(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    decimals: Int = 0,
    sliderSteps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    val fmt = "%.${decimals}f"
    val display = remember(value, decimals) { String.format(Locale.US, fmt, value) }
    var text by remember { mutableStateOf(display) }

    // 外部值变了（拖滑块、切换预设、加载设置）才回写输入框；
    // 用户自己输入引起的变化不回写，避免光标乱跳、吞字符。
    LaunchedEffect(value) {
        val typed = text.toFloatOrNull()
        if (typed == null || abs(typed - value) > 0.0001f) {
            text = display
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // 自己搭一个紧凑输入框，不用 OutlinedTextField。
            //
            // Material3 的 OutlinedTextField 有 56dp 的**最小高度**和 16dp 的内部留白，
            // 一行里只放一个两位数却要占掉 112×56，比左边的标签还显眼。
            // 这里用 BasicTextField 套个浅色圆角框：36dp 高、数字居中。
            //
            // 单位（sp / %）放在框**外面**：塞进框里时框内宽度不够，
            // "sp" 会被挤成 "s" / "p" 两行，特别难看；放外面既不换行也好读。
            Box(
                Modifier
                    .width(64.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
                        shape = RoundedCornerShape(9.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { input ->
                        // 只允许数字和一个小数点
                        val cleaned = input.filter { it.isDigit() || it == '.' }
                        text = cleaned
                        cleaned.toFloatOrNull()?.let { v ->
                            if (v >= range.start && v <= range.endInclusive) {
                                onValueChange(v)
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    ),
                    // 光标跟着主题色，浅色背景上默认的黑色光标不显眼
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
            if (suffix.isNotBlank()) {
                Spacer(Modifier.width(5.dp))
                Text(
                    suffix,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    // 固定最小宽度：sp 和 % 宽度不同，不固定的话两行的输入框会左右错开
                    modifier = Modifier.widthIn(min = 20.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
        // 自定义滑块外观。
        //
        // Material3 默认的滑块手柄是一根 4×44dp 的竖条（看着像一根"长 I"），
        // 在这么紧凑的一行里又高又突兀，还把行高撑到 48dp；
        // 默认轨道还会在每个 step 画刻度点——字号 12~32 有 19 档，一排点点非常花。
        // 这里换成 15dp 圆点 + 4dp 细轨道，刻度点全部去掉（拖动依然吸附到整数）。
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = sliderSteps,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
            thumb = {
                Box(
                    Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
            track = { _ ->
                val span = range.endInclusive - range.start
                val fraction = if (span <= 0f) {
                    0f
                } else {
                    ((value.coerceIn(range.start, range.endInclusive) - range.start) / span)
                        .coerceIn(0f, 1f)
                }
                // 用 Canvas 自己画而不是摆两个 Box：
                // 轨道槽的可用高度由 Slider 内部决定，摆 Box 得猜它是顶端对齐还是居中，
                // Canvas 直接按 size.height/2 算中心，任何高度都不会歪。
                Canvas(Modifier.fillMaxSize()) {
                    val trackH = 4.dp.toPx()
                    val top = (size.height - trackH) / 2f
                    val radius = CornerRadius(trackH / 2f)
                    drawRoundRect(
                        color = inactiveColor,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, trackH),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, top),
                        size = Size(size.width * fraction, trackH),
                        cornerRadius = radius,
                    )
                }
            },
        )
    }
}

/** 字幕可选文字色。白/黄/青/绿/粉/橙 —— 覆盖深色、浅色、偏色背景。 */
internal val SUBTITLE_COLORS: List<Pair<Int, String>> = listOf(
    0xFFFFFFFF.toInt() to "白",
    0xFFFFEB3B.toInt() to "黄",
    0xFF4DD0E1.toInt() to "青",
    0xFF81C784.toInt() to "绿",
    0xFFF06292.toInt() to "粉",
    0xFFFFB74D.toInt() to "橙",
)

/**
 * 横向色板。
 *
 * 不用系统取色器：字幕就那么几种颜色好用，六格点一下就切换，
 * 比进对话框、拖色轮快得多。选中的加一圈主题色描边。
 */
@Composable
internal fun SubtitleColorPicker(
    current: Int,
    onPick: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SUBTITLE_COLORS.forEach { (argb, name) ->
            val selected = argb == current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            },
                            shape = CircleShape,
                        )
                        .clickable { onPick(argb) },
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
