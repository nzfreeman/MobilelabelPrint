package com.labelprint.godex

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** GoDEX EZPL 명령 생성. 안드로이드 클래스에 의존하지 않는다. */
object Ezpl {

    /** 인쇄 문구를 줄 단위로 만든다. */
    fun lines(o: PrintOptions, batch: String, mfg: String, bb: String): List<String> {
        val batchLine = (if (o.batchPrefix) "Batch: " else "") + batch.trim()
        val bbLine = (if (o.bbPrefix) "${o.bbStyle}: " else "") + bb.trim()
        val mfgLine = if (o.printMfg && mfg.isNotBlank())
            (if (o.mfgPrefix) "MFG Date: " else "") + mfg.trim() else null
        val parts = if (o.order == ORDER_BATCH_LAST)
            listOfNotNull(mfgLine, bbLine, batchLine)
        else
            listOfNotNull(batchLine, mfgLine, bbLine)
        return if (o.vertical) parts else listOf(parts.joinToString("   "))
    }

    /**
     * 무지 라벨 문구를 줄 단위로 만든다. {BATCH}, {MFG}, {BB} 자리에 값을 넣고,
     * MFG 값이 비어 있으면 {MFG}가 들어 있는 줄은 통째로 뺀다.
     */
    fun templateLines(template: String, batch: String, mfg: String, bb: String): List<String> {
        val ph = Regex("\\{(batch|mfg|bb)\\}", RegexOption.IGNORE_CASE)
        return template.replace("\r", "").trim('\n').split('\n')
            .filter { line -> mfg.isNotBlank() || !line.contains("{mfg}", ignoreCase = true) }
            .map { line ->
                ph.replace(line) { m ->
                    when (m.groupValues[1].lowercase()) {
                        "batch" -> batch.trim()
                        "mfg" -> mfg.trim()
                        else -> bb.trim()
                    }
                }.trimEnd()
            }
    }

    /** 라벨 종류에 맞는 인쇄 문구. */
    fun linesFor(t: LabelTemplate, batch: String, mfg: String, bb: String): List<String> =
        if (t.blank) templateLines(t.textTemplate, batch, mfg, bb) else lines(t.options, batch, mfg, bb)

    /** 사진 기준 인쇄 영역을 라벨 테두리 기준 0..1 좌표로 바꾼다. */
    fun areaInLabel(t: LabelTemplate): NRect {
        val o = t.outline.normalized()
        val a = t.area.normalized()
        val ow = if (o.w <= 0f) 1f else o.w
        val oh = if (o.h <= 0f) 1f else o.h
        return NRect((a.l - o.l) / ow, (a.t - o.t) / oh, (a.r - o.l) / ow, (a.b - o.t) / oh).normalized()
    }

    /** 인쇄 영역의 크기와 위치(mm). 디자인을 바로 놓고 본 방향 기준. */
    data class MmBox(val w: Float, val h: Float, val hOff: Float, val vOff: Float)

    /** 디자인을 바로 놓고 본 라벨의 (가로, 세로) mm. */
    fun designSize(t: LabelTemplate): Pair<Float, Float> =
        if (t.rotation % 180 != 0) t.lengthMm to t.widthMm else t.widthMm to t.lengthMm

    /** 현재 인쇄 영역을 mm 값으로 읽는다. 위치는 anchor 설정에 따라 오른쪽/왼쪽, 아래/위에서 잰다. */
    fun mmBox(t: LabelTemplate): MmBox {
        val (dw, dh) = designSize(t)
        val a = areaInLabel(t)
        return MmBox(
            w = a.w * dw, h = a.h * dh,
            hOff = if (t.anchorRight) (1 - a.r) * dw else a.l * dw,
            vOff = if (t.anchorBottom) (1 - a.b) * dh else a.t * dh,
        )
    }

    /** mm 값으로 인쇄 영역을 정한다. 여백 안쪽을 벗어나지 않게 맞춘다. */
    fun withMmBox(t: LabelTemplate, box: MmBox): LabelTemplate {
        val (dw, dh) = designSize(t)
        val minX = t.marginLeft.coerceIn(0f, dw / 2)
        val maxX = dw - t.marginRight.coerceIn(0f, dw / 2)
        val minY = t.marginTop.coerceIn(0f, dh / 2)
        val maxY = dh - t.marginBottom.coerceIn(0f, dh / 2)
        val w = box.w.coerceIn(0.5f, maxOf(0.5f, maxX - minX))
        val h = box.h.coerceIn(0.5f, maxOf(0.5f, maxY - minY))
        var l = if (t.anchorRight) dw - box.hOff - w else box.hOff
        var tp = if (t.anchorBottom) dh - box.vOff - h else box.vOff
        l = l.coerceIn(minX, maxOf(minX, maxX - w))
        tp = tp.coerceIn(minY, maxOf(minY, maxY - h))
        val o = t.outline.normalized()
        val area = NRect(
            o.l + o.w * (l / dw), o.t + o.h * (tp / dh),
            o.l + o.w * ((l + w) / dw), o.t + o.h * ((tp + h) / dh),
        )
        return t.copy(area = area)
    }

    /** 사진을 시계 방향으로 deg만큼 돌렸을 때의 좌표. */
    fun rotateRect(r: NRect, deg: Int): NRect = when (((deg % 360) + 360) % 360) {
        90 -> NRect(1 - r.b, r.l, 1 - r.t, r.r)
        180 -> NRect(1 - r.r, 1 - r.b, 1 - r.l, 1 - r.t)
        270 -> NRect(r.t, 1 - r.r, r.b, 1 - r.l)
        else -> r
    }

    fun dots(mm: Float, dpi: Int): Int = (mm * dpi / 25.4f).roundToInt()

    data class Placement(val x: Int, val y: Int, val w: Int, val h: Int)

    /** 프린터 좌표계(dot)에서 인쇄 영역의 위치와 크기. */
    fun placement(t: LabelTemplate, dpi: Int): Placement {
        val r = rotateRect(areaInLabel(t), t.rotation)
        val wd = dots(t.widthMm, dpi)
        val ld = dots(t.lengthMm, dpi)
        val x = (r.l * wd).roundToInt()
        val y = (r.t * ld).roundToInt()
        val w = (r.w * wd).roundToInt().coerceAtLeast(8)
        val h = (r.h * ld).roundToInt().coerceAtLeast(8)
        return Placement(x, y, w, h)
    }

    /**
     * ARGB 픽셀을 1bpp로 묶는다. 어두운 픽셀이 1(인쇄).
     * @return (데이터, 한 줄의 바이트 수)
     */
    fun pack(pixels: IntArray, width: Int, height: Int, invert: Boolean): Pair<ByteArray, Int> {
        val wb = (width + 7) / 8
        val out = ByteArray(wb * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val a = (p ushr 24) and 0xff
                val lum = (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
                var black = a > 127 && lum < 128
                if (invert) black = !black
                if (black) {
                    val i = y * wb + x / 8
                    out[i] = (out[i].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        return out to wb
    }

    /** 라벨 한 종류를 count장 인쇄하는 작업 데이터. */
    fun job(
        t: LabelTemplate, s: PrinterSettings, x: Int, y: Int,
        data: ByteArray, widthBytes: Int, height: Int, count: Int,
    ): ByteArray {
        val head = buildString {
            append("^Q${fmt(t.lengthMm)},${fmt(t.gapMm)}\r\n")
            append("^W${fmt(t.widthMm)}\r\n")
            append("^H${s.darkness.coerceIn(0, 19)}\r\n")
            append("^P${count.coerceIn(1, 9999)}\r\n")
            append("^S${s.speed.coerceIn(2, 7)}\r\n")
            if (s.printMode == 1) append("^AT\r\n") else if (s.printMode == 2) append("^AD\r\n")
            append("^C1\r\n")
            append("^R${s.leftMargin.coerceIn(0, 399)}\r\n")
            if (s.stopPosition >= 0) append("^E${s.stopPosition}\r\n")
            append("^L\r\n")
            append("Q$x,$y,$widthBytes,$height\r\n")
        }
        val bos = ByteArrayOutputStream()
        bos.write(head.toByteArray(Charsets.US_ASCII))
        bos.write(data)
        bos.write("\r\nE\r\n".toByteArray(Charsets.US_ASCII))
        return bos.toByteArray()
    }

    private fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    /** 정상(대기·인쇄 중·처리 중) 상태인지. */
    fun isOk(code: String): Boolean = code.trim().take(2) in setOf("00", "50", "60")

    /** 아직 인쇄하거나 데이터를 처리하는 중인지. */
    fun isBusy(code: String): Boolean = code.trim().take(2) in setOf("50", "60")

    fun statusText(code: String): String = when (code.trim().take(2)) {
        "00" -> "준비됨"
        "50" -> "인쇄 중"
        "60" -> "데이터 처리 중"
        "06" -> "파일 저장 공간 가득 참"
        "09" -> "명령 문법 오류"
        "10" -> "커터 걸림"
        "20" -> "일시 정지"
        "21" -> "설정 모드"
        "22" -> "키보드 모드"
        "01", "02" -> "용지 없음 또는 용지 걸림"
        "03" -> "리본 없음"
        "04" -> "프린트 헤드(덮개) 열림"
        "05" -> "리와인더 가득 참"
        "" -> "응답 없음"
        else -> "상태 코드 ${code.trim()}"
    }
}
