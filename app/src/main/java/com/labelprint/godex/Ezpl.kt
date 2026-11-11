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
    fun templateLines(
        template: String, batch: String, mfg: String, bb: String, o: PrintOptions? = null,
        picks: Map<String, String> = emptyMap(),
    ): List<String> {
        fun raw(k: String): String = when (k) {
            "batch" -> batch.trim()
            "mfg" -> mfg.trim()
            "bb" -> bb.trim()
            else -> (picks[k.uppercase()] ?: "").trim()
        }
        return template.replace("\r", "").trim('\n').split('\n')
            // MFG나 문구 항목의 값이 비어 있으면 그 자리가 있는 줄은 통째로 뺀다.
            .filter { line ->
                PLACEHOLDER.findAll(line).none { m ->
                    val k = m.groupValues[1].lowercase()
                    k != "batch" && k != "bb" && raw(k).isBlank()
                }
            }
            .map { line ->
                PLACEHOLDER.replace(line) { m ->
                    // o가 있으면 설정에 따라 값 앞에 머리말을 붙인다.
                    when (val k = m.groupValues[1].lowercase()) {
                        "batch" -> (if (o?.batchPrefix == true) "Batch: " else "") + raw(k)
                        "mfg" -> (if (o?.mfgPrefix == true) "${o.mfgStyle}: " else "") + raw(k)
                        "bb" -> (if (o?.bbPrefix == true) "${o.bbStyle}: " else "") + raw(k)
                        else -> raw(k)
                    }
                }.trimEnd()
            }
    }

    private val PLACEHOLDER = Regex(
        "\\{(batch|mfg|bb|product|weight|qty|storage|rmp|origin|company)\\}", RegexOption.IGNORE_CASE,
    )

    /**
     * 이 라벨이 쓰는 문구 항목마다 실제로 찍을 글자. 라벨에서 고른 것이 없으면 목록의 첫 문구를 쓴다.
     */
    fun effectivePicks(t: LabelTemplate, phrases: List<Phrase>): Map<String, String> =
        phrases.filter { uses(t, it.key) }.associate { it.key to (t.picks[it.key] ?: it.options.firstOrNull() ?: "") }

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

    /** 다른 라벨의 크기·방향·인쇄 영역·글자 설정을 가져온다. 이름과 사진은 그대로 둔다. */
    fun copySettings(to: LabelTemplate, from: LabelTemplate): LabelTemplate = to.copy(
        widthMm = from.widthMm, lengthMm = from.lengthMm, gapMm = from.gapMm, rotation = from.rotation,
        areas = from.areas, options = from.options,
    )

    /**
     * 사진에서 라벨이 차지하는 범위를 찾는다. 사진 가장자리의 색을 바탕(책상)으로 보고,
     * 바탕과 다른 픽셀이 많이 모인 가로줄·세로줄의 가장 긴 구간을 라벨로 본다.
     * @param px ARGB 픽셀(w × h). 찾지 못하면 NRect.FULL.
     */
    fun findLabel(px: IntArray, w: Int, h: Int): NRect {
        if (w < 8 || h < 8) return NRect.FULL
        val ring = maxOf(1, minOf(w, h) / 25)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x < ring || y < ring || x >= w - ring || y >= h - ring) {
                val p = px[y * w + x]
                rs += (p shr 16) and 0xff; gs += (p shr 8) and 0xff; bs += p and 0xff
            }
        }
        rs.sort(); gs.sort(); bs.sort()
        val br = rs[rs.size / 2]; val bg = gs[gs.size / 2]; val bb = bs[bs.size / 2]
        val diff = BooleanArray(w * h) { i ->
            val p = px[i]
            Math.abs(((p shr 16) and 0xff) - br) + Math.abs(((p shr 8) and 0xff) - bg) + Math.abs((p and 0xff) - bb) > 110
        }
        // 조건을 만족하는 칸들의 가장 긴 구간(2칸까지의 끊김은 이어 붙인다)
        fun longest(n: Int, hit: (Int) -> Boolean): IntRange? {
            var best: IntRange? = null
            var start = -1; var last = -1
            for (i in 0..n) {
                val on = i < n && hit(i)
                if (on) {
                    if (start < 0 || i - last > 3) {
                        if (start >= 0 && (best == null || last - start > best.last - best.first)) best = start..last
                        start = i
                    }
                    last = i
                }
            }
            if (start >= 0 && (best == null || last - start > best.last - best.first)) best = start..last
            return best
        }
        val rows = longest(h) { y -> (0 until w).count { diff[y * w + it] } > w * 0.35f } ?: return NRect.FULL
        val rowCount = rows.last - rows.first + 1
        val cols = longest(w) { x -> rows.count { diff[it * w + x] } > rowCount * 0.35f } ?: return NRect.FULL
        val r = NRect(
            cols.first.toFloat() / w, rows.first.toFloat() / h,
            (cols.last + 1f) / w, (rows.last + 1f) / h,
        ).normalized()
        val share = r.w * r.h
        return if (share < 0.03f || share > 0.97f || r.w < 0.1f || r.h < 0.05f) NRect.FULL else r
    }

    // ───── 직접 입력한 인쇄 영역 ─────

    private fun round1(v: Float): Float = Math.round(v * 10f) / 10f

    /** 디자인 기준 가로·세로(mm)와 먼저 나오는 쪽으로 프린터 기준 폭·길이를 정한다. */
    fun withDesignSize(t: LabelTemplate, w: Float, h: Float, rotation: Int = t.rotation): LabelTemplate =
        if (rotation % 180 != 0) t.copy(rotation = rotation, lengthMm = w, widthMm = h)
        else t.copy(rotation = rotation, widthMm = w, lengthMm = h)

    /** 새 영역: 마지막 영역 바로 아래(없으면 왼쪽 위)에 라벨 안에 들어가는 크기로 둔다. */
    fun newArea(t: LabelTemplate): PrintArea {
        val (dw, dh) = designSize(t)
        val w = minOf(30f, maxOf(1f, dw - 4f))
        val h = minOf(10f, maxOf(1f, dh - 4f))
        val last = t.areas.lastOrNull()
        val y = if (last == null) 2f else last.yMm + last.hMm + 2f
        return PrintArea(
            xMm = minOf(2f, maxOf(0f, dw - w)), yMm = round1(y.coerceIn(0f, maxOf(0f, dh - h))),
            wMm = round1(w), hMm = round1(h),
            text = if (last == null) DEFAULT_AREA_TEXT else "",
        )
    }

    /** 예전(사진 기준) 설정을 mm 영역 하나로 옮긴다. */
    fun legacyAreas(t: LabelTemplate): List<PrintArea> {
        val (dw, dh) = designSize(t)
        val a = areaInLabel(t)
        val o = t.options
        // 머리말은 글자로 넣지 않고 options의 켜고 끄기 설정으로 붙인다.
        val bare = o.copy(batchPrefix = false, bbPrefix = false, mfgPrefix = false)
        val text = if (t.blank) t.textTemplate else lines(bare, "{BATCH}", "{MFG}", "{BB}").joinToString("\n")
        return listOf(
            PrintArea(
                xMm = round1(a.l * dw), yMm = round1(a.t * dh), wMm = round1(a.w * dw), hMm = round1(a.h * dh),
                text = text, fontPt = o.fontPt, bold = o.bold, centered = o.centered,
            )
        )
    }

    /** 영역에 찍을 줄들. 찍을 글자가 없으면 빈 목록. */
    fun areaLines(
        a: PrintArea, batch: String, mfg: String, bb: String, o: PrintOptions,
        picks: Map<String, String> = emptyMap(),
    ): List<String> {
        if (a.text.isBlank()) return emptyList()
        val out = templateLines(a.text, batch, mfg, bb, o, picks)
        return if (out.any { it.isNotBlank() }) out else emptyList()
    }

    private val PREFIX_RE = Regex(
        "(batch|best\\s*before|b\\.?\\s*b\\.?|mfg(?:\\s*date)?)\\s*:\\s*(?=\\{(batch|bb|mfg)\\})",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 예전 버전에서 문구에 글자로 적어 둔 머리말("Batch: {BATCH}")을 켜고 끄는 설정으로 옮긴다.
     * 알아본 머리말은 문구에서 빼고 설정을 켠다. 값 자리는 있는데 알아본 머리말이 없으면 설정을 꺼서
     * 인쇄 결과가 예전과 같게 한다.
     */
    fun migratePrefixes(t: LabelTemplate): LabelTemplate {
        var o = t.options
        val found = HashSet<String>()
        val areas = t.areas.map { a ->
            a.copy(text = PREFIX_RE.replace(a.text) { m ->
                val word = m.groupValues[1]
                val key = m.groupValues[2].lowercase()
                found += key
                if (key == "bb") {
                    o = o.copy(
                        bbStyle = when {
                            word.contains("before", ignoreCase = true) -> "Best Before"
                            word.contains(".") -> "B.B"
                            else -> "BB"
                        }
                    )
                } else if (key == "mfg") {
                    o = o.copy(mfgStyle = if (word.contains("date", ignoreCase = true)) "MFG Date" else "MFG")
                }
                ""
            })
        }
        val out = t.copy(areas = areas)
        return out.copy(
            options = o.copy(
                batchPrefix = if (uses(out, "batch")) "batch" in found else o.batchPrefix,
                bbPrefix = if (uses(out, "bb")) "bb" in found else o.bbPrefix,
                mfgPrefix = if (uses(out, "mfg")) "mfg" in found else o.mfgPrefix,
            )
        )
    }

    /** 어느 영역이든 {key} 자리를 쓰는지. key는 batch, mfg, bb. */
    fun uses(t: LabelTemplate, key: String): Boolean =
        t.areas.any { it.text.contains("{$key}", ignoreCase = true) }

    /** 영역이 라벨 안에 들어가는지 확인한다. 문제가 없으면 null. */
    fun areaProblem(t: LabelTemplate, a: PrintArea): String? {
        val (dw, dh) = designSize(t)
        val over = 0.05f
        return when {
            a.wMm <= 0f || a.hMm <= 0f -> "영역의 가로·세로는 0보다 커야 합니다."
            a.xMm < 0f || a.yMm < 0f -> "위치는 0 이상이어야 합니다."
            a.xMm + a.wMm > dw + over ->
                "영역이 라벨 오른쪽 밖으로 ${round1(a.xMm + a.wMm - dw)} mm 나갑니다 (라벨 가로 ${round1(dw)} mm)."
            a.yMm + a.hMm > dh + over ->
                "영역이 라벨 아래쪽 밖으로 ${round1(a.yMm + a.hMm - dh)} mm 나갑니다 (라벨 세로 ${round1(dh)} mm)."
            else -> null
        }
    }

    /** 프린터 좌표계(dot)에서 영역의 위치와 크기. */
    fun areaPlacement(t: LabelTemplate, a: PrintArea, dpi: Int): Placement {
        val (dw, dh) = designSize(t)
        val r = rotateRect(
            NRect(a.xMm / dw, a.yMm / dh, (a.xMm + a.wMm) / dw, (a.yMm + a.hMm) / dh).normalized(), t.rotation,
        )
        val wd = dots(t.widthMm, dpi)
        val ld = dots(t.lengthMm, dpi)
        return Placement(
            (r.l * wd).roundToInt(), (r.t * ld).roundToInt(),
            (r.w * wd).roundToInt().coerceAtLeast(8), (r.h * ld).roundToInt().coerceAtLeast(8),
        )
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

    /** 프린터 좌표 (x, y)에 찍을 1bpp 그림 하나. */
    class Graphic(val x: Int, val y: Int, val data: ByteArray, val widthBytes: Int, val height: Int)

    fun job(
        t: LabelTemplate, s: PrinterSettings, x: Int, y: Int,
        data: ByteArray, widthBytes: Int, height: Int, count: Int,
    ): ByteArray = job(t, s, listOf(Graphic(x, y, data, widthBytes, height)), count)

    /** 라벨 한 종류를 count장 인쇄하는 작업 데이터. 영역마다 그림 하나씩 넣는다. */
    fun job(t: LabelTemplate, s: PrinterSettings, graphics: List<Graphic>, count: Int): ByteArray {
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
        }
        val bos = ByteArrayOutputStream()
        bos.write(head.toByteArray(Charsets.US_ASCII))
        for (g in graphics) {
            bos.write("Q${g.x},${g.y},${g.widthBytes},${g.height}\r\n".toByteArray(Charsets.US_ASCII))
            bos.write(g.data)
            bos.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        bos.write("E\r\n".toByteArray(Charsets.US_ASCII))
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
