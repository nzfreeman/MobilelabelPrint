package com.labelprint.godex

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val BLUE = Color(0xFF1565C0)
private val RED = Color(0xFFD32F2F)

/** 라벨 기준 0..1 좌표를 사진 좌표로 바꾼다. */
private fun toPhoto(rel: NRect, outline: NRect): NRect {
    val o = outline.normalized()
    return NRect(o.l + rel.l * o.w, o.t + rel.t * o.h, o.l + rel.r * o.w, o.t + rel.b * o.h)
}

/**
 * 라벨 설정 화면.
 * ① 종이(라벨) 크기를 먼저 입력하고 ② 사진에서 라벨이 찍힌 범위를 맞춘 뒤
 * ③ 입력한 크기의 종이 위에서 인쇄 위치를 정한다. 인쇄 위치는 항상 종이 크기 기준(mm)이다.
 */
@Composable
internal fun EditScreen(template: LabelTemplate, onSave: (LabelTemplate) -> Unit, onBack: () -> Unit) {
    var t by remember(template.id) { mutableStateOf(template) }
    var zoom by remember { mutableStateOf(1) }
    var note by remember { mutableStateOf("") }
    // 사각형을 손으로 옮기면 mm 입력 칸을 새 값으로 다시 그리기 위한 번호
    var rev by remember { mutableStateOf(0) }
    val bmp = rememberBitmap(t.imagePath, 1600)
    val scope = rememberCoroutineScope()

    fun detect(b: Bitmap) {
        scope.launch {
            val found = withContext(Dispatchers.Default) { Imaging.detectBlankArea(b, t.outline) }
            t = t.copy(area = found)
            rev++
            note = "빈 공간을 찾아 빨간 사각형으로 표시했습니다. 맞지 않으면 손으로 옮기거나 mm 값을 고치세요."
        }
    }

    LaunchedEffect(bmp) {
        if (bmp != null && !template.configured && !template.blank) detect(bmp)
    }

    val (designW, designH) = Ezpl.designSize(t)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Header("라벨 설정", onBack = onBack)
        OutlinedTextField(
            value = t.name, onValueChange = { t = t.copy(name = it) },
            label = { Text("라벨 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        // ───── ① 종이 크기 ─────
        Section("① 라벨(종이) 크기")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("가로 mm", mm(designW), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { v ->
                    if (v > 0) {
                        // 인쇄 영역의 mm 위치는 그대로 두고 종이 크기만 바꾼다.
                        val box = Ezpl.mmBox(t)
                        val sized = if (t.rotation % 180 != 0) t.copy(lengthMm = v) else t.copy(widthMm = v)
                        t = Ezpl.withMmBox(sized, box); rev++
                    }
                }
            }
            NumField("세로 mm", mm(designH), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { v ->
                    if (v > 0) {
                        val box = Ezpl.mmBox(t)
                        val sized = if (t.rotation % 180 != 0) t.copy(widthMm = v) else t.copy(lengthMm = v)
                        t = Ezpl.withMmBox(sized, box); rev++
                    }
                }
            }
            NumField("라벨 간격 mm", fmt(t.gapMm), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { if (it >= 0) t = t.copy(gapMm = it) }
            }
        }
        Text(
            "라벨을 글자가 바로 보이게 놓았을 때의 실제 크기입니다. 인쇄 위치는 모두 이 크기를 기준으로 계산합니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("프린터에서 먼저 나오는 쪽", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Chips(
            listOf(0 to "위쪽", 90 to "왼쪽", 180 to "아래쪽", 270 to "오른쪽"),
            t.rotation,
        ) { r ->
            // 가로·세로 mm는 그대로 두고 프린터 기준 폭/길이만 다시 정한다.
            val (w, h) = Ezpl.designSize(t)
            val box = Ezpl.mmBox(t)
            val turned = if (r % 180 != 0) t.copy(rotation = r, lengthMm = w, widthMm = h)
            else t.copy(rotation = r, widthMm = w, lengthMm = h)
            t = Ezpl.withMmBox(turned, box); rev++
        }
        Text(
            "프린터 기준: 폭 ${mm(t.widthMm)} mm × 길이 ${mm(t.lengthMm)} mm. " +
                "글자가 뒤집히거나 엉뚱한 곳에 찍히면 이 값을 바꿔 보세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (t.widthMm > 60f) {
            Text(
                "프린터 기준 폭이 ${mm(t.widthMm)} mm입니다. EZ2350i는 폭 60 mm까지만 넣을 수 있어서 " +
                    "가로·세로나 '먼저 나오는 쪽'이 바뀌었을 가능성이 큽니다. 이대로 보내면 용지 길이를 잘못 알아 인쇄 도중 멈출 수 있습니다.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }

        if (bmp == null) {
            Text("그림을 불러오는 중…", Modifier.padding(top = 12.dp))
        } else {
            // ───── ② 사진 맞추기 ─────
            if (!t.blank) {
                Section("② 사진에서 라벨 범위 맞추기")
                RectEditor(
                    bmp, src = NRect.FULL, aspect = bmp.width.toFloat() / bmp.height,
                    rect = t.outline, color = BLUE, zoom = 1,
                ) { r ->
                    // 인쇄 영역의 종이 기준 위치는 그대로 둔다.
                    val rel = Ezpl.areaInLabel(t)
                    t = t.copy(outline = r, area = toPhoto(rel, r))
                }
                Text(
                    "파란 사각형을 사진 속 라벨의 가장자리에 맞추세요. 이 범위가 위에서 입력한 " +
                        "${mm(designW)} × ${mm(designH)} mm 종이에 해당합니다. 사진은 위치를 눈으로 확인하는 용도입니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ───── ③ 인쇄 위치 ─────
            Section(if (t.blank) "② 인쇄 위치 (종이 기준)" else "③ 인쇄 위치 (종이 기준)")
            Text(
                "아래 그림은 ${mm(designW)} × ${mm(designH)} mm 종이입니다. 회색 눈금은 10 mm 간격입니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            RectEditor(
                bmp, src = t.outline.normalized(), aspect = designW / designH,
                rect = Ezpl.areaInLabel(t), color = RED, zoom = zoom,
                gridMm = designW to designH,
                margins = NRect(
                    t.marginLeft / designW, t.marginTop / designH,
                    1f - t.marginRight / designW, 1f - t.marginBottom / designH,
                ),
            ) { r ->
                t = t.copy(area = toPhoto(r, t.outline)); rev++
            }
            Chips(listOf(1 to "전체 보기", 2 to "2배 확대", 4 to "4배 확대"), zoom) { zoom = it }
            if (t.blank) {
                OutlinedButton(
                    onClick = { t = Ezpl.withMmBox(t, Ezpl.MmBox(9999f, 9999f, 0f, 0f)); rev++ },
                ) { Text("여백 안쪽 전체를 인쇄 영역으로") }
            } else {
                OutlinedButton(onClick = { detect(bmp) }) { Text("빈 공간 자동 인식") }
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        }

        Text("위치 (mm)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Chips(listOf(true to "오른쪽에서 잼", false to "왼쪽에서 잼"), t.anchorRight) { t = t.copy(anchorRight = it); rev++ }
        Chips(listOf(true to "아래쪽에서 잼", false to "위쪽에서 잼"), t.anchorBottom) { t = t.copy(anchorBottom = it); rev++ }
        key(rev) {
            val box = Ezpl.mmBox(t)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(if (t.anchorRight) "오른쪽에서 mm" else "왼쪽에서 mm", mm(box.hOff), Modifier.weight(1f), decimal = true) { s ->
                    s.toFloatOrNull()?.let { if (it >= 0) t = Ezpl.withMmBox(t, Ezpl.mmBox(t).copy(hOff = it)) }
                }
                NumField(if (t.anchorBottom) "아래쪽에서 mm" else "위쪽에서 mm", mm(box.vOff), Modifier.weight(1f), decimal = true) { s ->
                    s.toFloatOrNull()?.let { if (it >= 0) t = Ezpl.withMmBox(t, Ezpl.mmBox(t).copy(vOff = it)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("영역 가로 mm", mm(box.w), Modifier.weight(1f), decimal = true) { s ->
                    s.toFloatOrNull()?.let { if (it > 0) t = Ezpl.withMmBox(t, Ezpl.mmBox(t).copy(w = it)) }
                }
                NumField("영역 세로 mm", mm(box.h), Modifier.weight(1f), decimal = true) { s ->
                    s.toFloatOrNull()?.let { if (it > 0) t = Ezpl.withMmBox(t, Ezpl.mmBox(t).copy(h = it)) }
                }
            }
        }
        Text(
            "숫자를 넣으면 빨간 사각형이 그 위치로 옮겨집니다. 인쇄는 이 영역 안에서만 됩니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("라벨 여백 (mm, 이 안쪽으로만 인쇄)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("왼쪽", mm(t.marginLeft), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { if (it >= 0) { t = Ezpl.withMmBox(t.copy(marginLeft = it), Ezpl.mmBox(t)); rev++ } }
            }
            NumField("위", mm(t.marginTop), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { if (it >= 0) { t = Ezpl.withMmBox(t.copy(marginTop = it), Ezpl.mmBox(t)); rev++ } }
            }
            NumField("오른쪽", mm(t.marginRight), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { if (it >= 0) { t = Ezpl.withMmBox(t.copy(marginRight = it), Ezpl.mmBox(t)); rev++ } }
            }
            NumField("아래", mm(t.marginBottom), Modifier.weight(1f), decimal = true) { s ->
                s.toFloatOrNull()?.let { if (it >= 0) { t = Ezpl.withMmBox(t.copy(marginBottom = it), Ezpl.mmBox(t)); rev++ } }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val clean = t.copy(
                    outline = if (t.blank) NRect.FULL else t.outline.normalized(),
                    area = t.area.normalized(),
                )
                if (t.blank) {
                    val (w, h) = Ezpl.designSize(clean)
                    Imaging.writeBlank(File(clean.imagePath), w, h)
                }
                // 손으로 옮긴 영역도 여백 안쪽으로 맞춘 뒤 저장한다.
                onSave(Ezpl.withMmBox(clean, Ezpl.mmBox(clean)).copy(configured = true))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장하고 인쇄 화면으로") }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 그림의 src 범위를 지정한 비율(aspect)의 판에 펼쳐 그리고, 그 위에서 사각형 하나를 손으로 조정한다.
 * rect와 onChange의 좌표는 판 기준 0..1이다.
 */
@Composable
private fun RectEditor(
    bmp: Bitmap, src: NRect, aspect: Float, rect: NRect, color: Color, zoom: Int,
    gridMm: Pair<Float, Float>? = null, margins: NRect? = null,
    onChange: (NRect) -> Unit,
) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    val active by rememberUpdatedState(rect)
    val report by rememberUpdatedState(onChange)
    // 확대하면 사각형을 가운데에 둔 창만 보여 준다.
    val view = remember(zoom) {
        if (zoom <= 1) NRect.FULL else {
            val size = 1f / zoom
            val l = ((active.l + active.r) / 2 - size / 2).coerceIn(0f, 1f - size)
            val tp = ((active.t + active.b) / 2 - size / 2).coerceIn(0f, 1f - size)
            NRect(l, tp, l + size, tp + size)
        }
    }
    val viewState by rememberUpdatedState(view)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val safeAspect = aspect.coerceIn(0.1f, 10f)
        var w = maxWidth
        var h = w / safeAspect
        if (h > 420.dp) {
            h = 420.dp
            w = h * safeAspect
        }
        Canvas(
            Modifier.size(w, h).align(Alignment.Center).pointerInput(Unit) {
                var mode = 0
                detectDragGestures(
                    onDragStart = { p ->
                        val r = active
                        val v = viewState
                        fun sx(n: Float) = (n - v.l) / v.w * size.width
                        fun sy(n: Float) = (n - v.t) / v.h * size.height
                        val corners = listOf(
                            Offset(sx(r.l), sy(r.t)), Offset(sx(r.r), sy(r.t)),
                            Offset(sx(r.l), sy(r.b)), Offset(sx(r.r), sy(r.b)),
                        )
                        val nearest = corners.indices.minByOrNull { (corners[it] - p).getDistance() } ?: 0
                        mode = if ((corners[nearest] - p).getDistance() < 32.dp.toPx()) nearest + 1 else 0
                    },
                ) { change, drag ->
                    change.consume()
                    val v = viewState
                    val dx = drag.x / size.width * v.w
                    val dy = drag.y / size.height * v.h
                    val r = active
                    val min = 0.01f
                    val next = when (mode) {
                        1 -> r.copy(l = (r.l + dx).coerceIn(0f, r.r - min), t = (r.t + dy).coerceIn(0f, r.b - min))
                        2 -> r.copy(r = (r.r + dx).coerceIn(r.l + min, 1f), t = (r.t + dy).coerceIn(0f, r.b - min))
                        3 -> r.copy(l = (r.l + dx).coerceIn(0f, r.r - min), b = (r.b + dy).coerceIn(r.t + min, 1f))
                        4 -> r.copy(r = (r.r + dx).coerceIn(r.l + min, 1f), b = (r.b + dy).coerceIn(r.t + min, 1f))
                        else -> {
                            val mx = dx.coerceIn(-r.l, 1f - r.r)
                            val my = dy.coerceIn(-r.t, 1f - r.b)
                            NRect(r.l + mx, r.t + my, r.r + mx, r.b + my)
                        }
                    }
                    report(next)
                }
            },
        ) {
            val v = view
            fun px(n: Float) = (n - v.l) / v.w * size.width
            fun py(n: Float) = (n - v.t) / v.h * size.height
            drawRect(Color.White)
            drawImage(
                image,
                srcOffset = IntOffset(
                    ((src.l + v.l * src.w) * image.width).toInt().coerceIn(0, image.width - 1),
                    ((src.t + v.t * src.h) * image.height).toInt().coerceIn(0, image.height - 1),
                ),
                srcSize = IntSize(
                    (v.w * src.w * image.width).toInt().coerceAtLeast(1),
                    (v.h * src.h * image.height).toInt().coerceAtLeast(1),
                ),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
            if (gridMm != null) {
                val grid = Color(0x66808080)
                var x = 10f
                while (x < gridMm.first) {
                    drawLine(grid, Offset(px(x / gridMm.first), 0f), Offset(px(x / gridMm.first), size.height), 1.5f); x += 10f
                }
                var y = 10f
                while (y < gridMm.second) {
                    drawLine(grid, Offset(0f, py(y / gridMm.second)), Offset(size.width, py(y / gridMm.second)), 1.5f); y += 10f
                }
            }
            if (margins != null && (margins.l > 0f || margins.t > 0f || margins.r < 1f || margins.b < 1f)) {
                drawRect(
                    Color(0xFF2E7D32), Offset(px(margins.l), py(margins.t)),
                    Size(px(margins.r) - px(margins.l), py(margins.b) - py(margins.t)), style = Stroke(width = 2f),
                )
            }
            drawRect(Color.Gray, style = Stroke(width = 2f))
            val left = px(rect.l)
            val top = py(rect.t)
            val rw = px(rect.r) - left
            val rh = py(rect.b) - top
            drawRect(color, Offset(left, top), Size(rw, rh), style = Stroke(width = 6f))
            listOf(Offset(left, top), Offset(left + rw, top), Offset(left, top + rh), Offset(left + rw, top + rh))
                .forEach { drawCircle(color, radius = 12f, center = it) }
        }
    }
}
