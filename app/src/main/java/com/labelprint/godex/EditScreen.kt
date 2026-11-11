package com.labelprint.godex

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 라벨 설정 화면. 사진 없이 모두 숫자로 정한다.
 * ① 라벨(종이) 크기 ② 인쇄 영역 개수 ③ 영역마다 위치·크기(mm, 라벨 왼쪽 위 기준)와 들어갈 내용.
 */
@Composable
internal fun EditScreen(
    template: LabelTemplate, settings: PrinterSettings,
    onSave: (LabelTemplate) -> Unit, onBack: () -> Unit,
) {
    var t by remember(template.id) {
        mutableStateOf(if (template.areas.isEmpty()) template.copy(areas = listOf(Ezpl.newArea(template))) else template)
    }
    val (designW, designH) = Ezpl.designSize(t)

    fun setArea(i: Int, change: (PrintArea) -> PrintArea) {
        t = t.copy(areas = t.areas.mapIndexed { n, a -> if (n == i) change(a) else a })
    }

    var preview by remember { mutableStateOf<Imaging.LabelPreview?>(null) }
    LaunchedEffect(t, settings.dpi) {
        preview = withContext(Dispatchers.Default) {
            Imaging.previewLabel(t, "QB0000", "01/01/2026", "01/01/2030", settings.dpi)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Header("라벨 설정", onBack = onBack)
        OutlinedTextField(
            value = t.name, onValueChange = { t = t.copy(name = it) },
            label = { Text("라벨 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        // ───── ① 라벨 크기 ─────
        Section("① 라벨(종이) 크기")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("가로 mm", mm(designW), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { v -> if (v > 0) t = Ezpl.withDesignSize(t, v, Ezpl.designSize(t).second) }
            }
            NumField("세로 mm", mm(designH), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { v -> if (v > 0) t = Ezpl.withDesignSize(t, Ezpl.designSize(t).first, v) }
            }
            NumField("라벨 간격 mm", fmt(t.gapMm), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { if (it >= 0) t = t.copy(gapMm = it) }
            }
        }
        Text(
            "라벨 한 장을 글자가 바로 보이게 놓았을 때의 실제 크기를 자로 재서 넣으세요. 간격은 라벨과 라벨 사이의 빈 띠입니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("프린터에서 먼저 나오는 쪽", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Chips(listOf(0 to "위쪽", 90 to "왼쪽", 180 to "아래쪽", 270 to "오른쪽"), t.rotation) { r ->
            val (w, h) = Ezpl.designSize(t)
            t = Ezpl.withDesignSize(t, w, h, r)
        }
        Text(
            "프린터로 보내는 값: 폭 ${mm(t.widthMm)} mm × 길이(용지 진행 방향) ${mm(t.lengthMm)} mm, 간격 ${mm(t.gapMm)} mm. " +
                "이 길이가 실제 라벨과 다르면 프린터가 라벨을 찾지 못해 인쇄 도중 멈춥니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (t.widthMm > 60f) {
            Text(
                "프린터 기준 폭이 ${mm(t.widthMm)} mm입니다. EZ2350i는 폭 60 mm까지만 넣을 수 있습니다. " +
                    "가로·세로 값이나 '먼저 나오는 쪽'을 다시 확인하세요.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }

        // ───── ② 인쇄 영역 ─────
        Section("② 인쇄 영역 개수")
        Chips((1..MAX_AREAS).map { it to "${it}개" }, t.areas.size) { n ->
            var list = t.areas.take(n)
            while (list.size < n) list = list + Ezpl.newArea(t.copy(areas = list))
            t = t.copy(areas = list)
        }

        t.areas.forEachIndexed { i, a ->
            key(i) {
                Section("인쇄 영역 ${i + 1}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("왼쪽에서 mm", mm(a.xMm), Modifier.weight(1f), decimal = true) { s ->
                        s.toFloatOrNull()?.let { v -> setArea(i) { it.copy(xMm = v) } }
                    }
                    NumField("위에서 mm", mm(a.yMm), Modifier.weight(1f), decimal = true) { s ->
                        s.toFloatOrNull()?.let { v -> setArea(i) { it.copy(yMm = v) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("영역 가로 mm", mm(a.wMm), Modifier.weight(1f), decimal = true) { s ->
                        s.toFloatOrNull()?.let { v -> setArea(i) { it.copy(wMm = v) } }
                    }
                    NumField("영역 세로 mm", mm(a.hMm), Modifier.weight(1f), decimal = true) { s ->
                        s.toFloatOrNull()?.let { v -> setArea(i) { it.copy(hMm = v) } }
                    }
                }
                Ezpl.areaProblem(t, a)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = a.text, onValueChange = { v -> setArea(i) { it.copy(text = v) } },
                    label = { Text("이 영역에 인쇄할 내용 (줄마다 한 줄)") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                NumField("글자 크기 pt (0 = 영역에 꽉 차게)", fmt(a.fontPt), Modifier.fillMaxWidth(), decimal = true) { s ->
                    s.toFloatOrNull()?.let { v -> if (v >= 0) setArea(i) { it.copy(fontPt = v) } }
                }
                Check("굵은 글씨 (Bold)", a.bold) { v -> setArea(i) { it.copy(bold = v) } }
                Check("가운데 정렬", a.centered) { v -> setArea(i) { it.copy(centered = v) } }
                preview?.areas?.getOrNull(i)?.let { r ->
                    if (!r.fits) {
                        Text(
                            "가장 작은 글씨로도 이 영역에 들어가지 않습니다. 영역을 키우거나 내용을 줄이세요.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (a.fontPt > 0f && r.pt < a.fontPt - 0.3f) {
                        Text(
                            "${fmt(a.fontPt)} pt는 이 영역보다 커서 ${mm(r.pt)} pt로 줄여 인쇄합니다. 그 크기로 찍으려면 영역을 키우세요.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("인쇄될 글자 크기 ${mm(r.pt)} pt", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text(
            "내용 안의 {BATCH}, {MFG}, {BB} 자리에는 인쇄 화면에서 입력한 배치번호, MFG Date, Best Before가 들어갑니다. " +
                "예: Batch: {BATCH}  ·  MFG Date를 비우면 {MFG}가 있는 줄은 인쇄하지 않습니다. 고정 문구만 넣어도 됩니다.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
        )

        Section("미리보기 (${mm(designW)} × ${mm(designH)} mm)")
        preview?.let {
            Image(
                it.bmp.asImageBitmap(), contentDescription = "라벨 미리보기",
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit,
            )
        }
        Text("빨간 사각형이 인쇄 영역입니다. 예시 값으로 채워서 보여 줍니다.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        val problem = t.areas.indices.firstOrNull { Ezpl.areaProblem(t, t.areas[it]) != null }
        Button(
            enabled = problem == null,
            onClick = { onSave(t.copy(configured = true)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장하고 인쇄 화면으로") }
        if (problem != null) {
            Text("인쇄 영역 ${problem + 1}의 위치·크기를 먼저 고치세요.", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }
}
