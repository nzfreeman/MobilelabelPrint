package com.labelprint.godex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.round

@Composable
internal fun PrintPositionControls(t: LabelTemplate, enabled: Boolean, onValid: (Boolean) -> Unit, onChange: (LabelTemplate) -> Unit) {
    var step by remember { mutableStateOf(0.5f) }
    var xValid by remember { mutableStateOf(true) }
    var yValid by remember { mutableStateOf(true) }
    var resetRevision by remember { mutableStateOf(0) }
    LaunchedEffect(xValid, yValid) { onValid(xValid && yValid) }
    fun move(dx: Float, dy: Float) {
        onChange(t.copy(
            printOffsetXmm = round((t.printOffsetXmm + dx).coerceIn(-10000f, 10000f) * 1000f) / 1000f,
            printOffsetYmm = round((t.printOffsetYmm + dy).coerceIn(-10000f, 10000f) * 1000f) / 1000f,
        ))
    }
    Section("인쇄 위치 보정 (라벨별 자동 저장)")
    Text("위 미리보기 기준으로 모든 인쇄 영역을 함께 옮깁니다. 위쪽이 먼저 나오는 변입니다.", style = MaterialTheme.typography.bodySmall)
    Chips(listOf(0.1f to "0.1 mm씩", 0.5f to "0.5 mm씩", 1f to "1 mm씩"), step) { step = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, onClick = { move(-step, 0f) }, modifier = Modifier.weight(1f)) { Text("← 왼쪽") }
        OutlinedButton(enabled = enabled, onClick = { move(step, 0f) }, modifier = Modifier.weight(1f)) { Text("오른쪽 →") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, onClick = { move(0f, -step) }, modifier = Modifier.weight(1f)) { Text("↑ 위쪽") }
        OutlinedButton(enabled = enabled, onClick = { move(0f, step) }, modifier = Modifier.weight(1f)) { Text("↓ 아래쪽") }
    }
    key(resetRevision) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OffsetField("좌우 mm (+오른쪽)", t.printOffsetXmm, enabled, Modifier.weight(1f), { xValid = it }) { onChange(t.copy(printOffsetXmm = it)) }
            OffsetField("상하 mm (+아래쪽)", t.printOffsetYmm, enabled, Modifier.weight(1f), { yValid = it }) { onChange(t.copy(printOffsetYmm = it)) }
        }
    }
    TextButton(enabled = enabled, onClick = {
        onChange(t.copy(printOffsetXmm = 0f, printOffsetYmm = 0f))
        resetRevision++
    }) { Text("위치 보정 0으로 초기화") }
}

@Composable
private fun OffsetField(label: String, value: Float, enabled: Boolean, modifier: Modifier, onValid: (Boolean) -> Unit, onValue: (Float) -> Unit) {
    var text by remember { mutableStateOf(fmt(value)) }
    LaunchedEffect(value) {
        if (text.toFloatOrNull() != value) text = fmt(value)
    }
    val parsed = text.toFloatOrNull()
    val valid = parsed != null && parsed.isFinite() && parsed in -10000f..10000f
    LaunchedEffect(valid) { onValid(valid) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.toFloatOrNull()?.takeIf { it.isFinite() && it in -10000f..10000f }?.let(onValue)
        },
        label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}
