package com.labelprint.godex

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 사진 전체를 보여 주고 그 위에서 자를 범위(0..1 좌표)를 손으로 맞춘다.
 * 모서리 근처를 끌면 그 모서리가, 가장자리 근처를 끌면 그 변이, 안쪽을 끌면 사각형 전체가 움직인다.
 */
@Composable
internal fun CropEditor(bmp: Bitmap, rect: NRect, onChange: (NRect) -> Unit) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    val active by rememberUpdatedState(rect)
    val report by rememberUpdatedState(onChange)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val aspect = (bmp.width.toFloat() / bmp.height).coerceIn(0.1f, 10f)
        var w = maxWidth
        var h = w / aspect
        if (h > 420.dp) {
            h = 420.dp
            w = h * aspect
        }
        Canvas(
            Modifier.size(w, h).align(Alignment.Center).pointerInput(Unit) {
                // 끌기 시작한 곳에 따라 움직일 변: 왼쪽, 위, 오른쪽, 아래
                var left = false; var top = false; var right = false; var bottom = false
                detectDragGestures(
                    onDragStart = { p ->
                        val r = active
                        val reach = 40.dp.toPx()
                        val x0 = r.l * size.width; val x1 = r.r * size.width
                        val y0 = r.t * size.height; val y1 = r.b * size.height
                        val inX = p.x > x0 - reach && p.x < x1 + reach
                        val inY = p.y > y0 - reach && p.y < y1 + reach
                        left = inY && kotlin.math.abs(p.x - x0) < reach
                        right = inY && !left && kotlin.math.abs(p.x - x1) < reach
                        top = inX && kotlin.math.abs(p.y - y0) < reach
                        bottom = inX && !top && kotlin.math.abs(p.y - y1) < reach
                    },
                ) { change, drag ->
                    change.consume()
                    val dx = drag.x / size.width
                    val dy = drag.y / size.height
                    val r = active
                    val min = 0.03f
                    val next = if (!left && !right && !top && !bottom) {
                        val mx = dx.coerceIn(-r.l, 1f - r.r)
                        val my = dy.coerceIn(-r.t, 1f - r.b)
                        NRect(r.l + mx, r.t + my, r.r + mx, r.b + my)
                    } else NRect(
                        if (left) (r.l + dx).coerceIn(0f, r.r - min) else r.l,
                        if (top) (r.t + dy).coerceIn(0f, r.b - min) else r.t,
                        if (right) (r.r + dx).coerceIn(r.l + min, 1f) else r.r,
                        if (bottom) (r.b + dy).coerceIn(r.t + min, 1f) else r.b,
                    )
                    report(next)
                }
            },
        ) {
            drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            val l = rect.l * size.width
            val t = rect.t * size.height
            val rw = rect.w * size.width
            val rh = rect.h * size.height
            // 잘려 나갈 바깥쪽은 어둡게
            val shade = Color(0x99000000)
            drawRect(shade, Offset(0f, 0f), Size(size.width, t))
            drawRect(shade, Offset(0f, t + rh), Size(size.width, size.height - t - rh))
            drawRect(shade, Offset(0f, t), Size(l, rh))
            drawRect(shade, Offset(l + rw, t), Size(size.width - l - rw, rh))
            val line = Color(0xFF1565C0)
            drawRect(line, Offset(l, t), Size(rw, rh), style = Stroke(width = 5f))
            listOf(Offset(l, t), Offset(l + rw, t), Offset(l, t + rh), Offset(l + rw, t + rh))
                .forEach { drawCircle(line, radius = 16f, center = it) }
        }
    }
}
