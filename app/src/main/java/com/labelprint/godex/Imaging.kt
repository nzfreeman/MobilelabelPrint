package com.labelprint.godex

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

object Imaging {

    /** 사진을 EXIF 방향대로 바로 세우고 긴 변 2000px 이하 JPEG로 저장한다. */
    fun importPhoto(src: File, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4000) sample *= 2
        val raw = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val deg = try {
            when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) { 0 }
        val longSide = max(raw.width, raw.height)
        val scale = if (longSide > 2000) 2000f / longSide else 1f
        val m = Matrix().apply { postScale(scale, scale); postRotate(deg.toFloat()) }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return true
    }

    /** 무지 라벨용 흰 바탕 그림을 라벨 비율대로 만들어 저장한다. */
    fun writeBlank(dest: File, wMm: Float, hMm: Float) {
        val scale = 1200f / max(wMm, hMm).coerceAtLeast(1f)
        val w = (wMm * scale).toInt().coerceAtLeast(50)
        val h = (hMm * scale).toInt().coerceAtLeast(50)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val edge = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 6f }
        c.drawRect(3f, 3f, w - 3f, h - 3f, edge)
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    fun load(path: String, maxSide: Int = 1600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * 라벨 범위 안에서 글자·그림이 없는 가장 큰 밝은 사각형을 찾는다.
     * 결과는 사진 기준 0..1 좌표.
     */
    fun detectBlankArea(bmp: Bitmap, within: NRect): NRect {
        val o = within.normalized()
        val sx = (o.l * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val sy = (o.t * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val sw = (o.w * bmp.width).toInt().coerceIn(2, bmp.width - sx)
        val sh = (o.h * bmp.height).toInt().coerceIn(2, bmp.height - sy)
        val gw = 160
        val gh = (gw.toFloat() * sh / sw).toInt().coerceIn(8, 400)
        val region = Bitmap.createBitmap(bmp, sx, sy, sw, sh)
        val small = Bitmap.createScaledBitmap(region, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        val lum = IntArray(gw * gh) { i ->
            val p = px[i]
            (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
        }
        // 밝은 쪽 기준값: 상위 10% 밝기
        val sorted = lum.sortedArray()
        val bright = sorted[(sorted.size * 0.9f).toInt().coerceAtMost(sorted.size - 1)]
        val blank = BooleanArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x
            val v = lum[i]
            val dx = if (x + 1 < gw) abs(v - lum[i + 1]) else 0
            val dy = if (y + 1 < gh) abs(v - lum[i + gw]) else 0
            blank[i] = v > bright - 40 && dx < 14 && dy < 14
        }
        // 이진 행렬에서 가장 큰 사각형(히스토그램 방식)
        val heights = IntArray(gw)
        var best = 0
        var bl = 0; var bt = 0; var br = 0; var bb = 0
        val stack = IntArray(gw + 1)
        for (y in 0 until gh) {
            for (x in 0 until gw) heights[x] = if (blank[y * gw + x]) heights[x] + 1 else 0
            var top = -1
            var x = 0
            while (x <= gw) {
                val h = if (x == gw) 0 else heights[x]
                if (top < 0 || h >= heights[stack[top]]) {
                    stack[++top] = x; x++
                } else {
                    val hh = heights[stack[top--]]
                    val left = if (top < 0) 0 else stack[top] + 1
                    val area = hh * (x - left)
                    if (area > best) {
                        best = area; bl = left; br = x; bb = y + 1; bt = y + 1 - hh
                    }
                }
            }
        }
        if (best < 12) return NRect(o.l + o.w * 0.6f, o.t + o.h * 0.6f, o.l + o.w * 0.95f, o.t + o.h * 0.95f)
        // 가장자리에서 한 칸 안쪽으로
        val il = (bl + 1).coerceAtMost(br - 1); val ir = (br - 1).coerceAtLeast(il + 1)
        val it = (bt + 1).coerceAtMost(bb - 1); val ib = (bb - 1).coerceAtLeast(it + 1)
        return NRect(
            o.l + o.w * il / gw, o.t + o.h * it / gh,
            o.l + o.w * ir / gw, o.t + o.h * ib / gh,
        )
    }

    /** 그린 결과. pt는 실제 쓰인 글자 크기, fits는 영역 안에 다 들어갔는지. */
    class Rendered(val bmp: Bitmap, val pt: Float, val fits: Boolean, val grown: Boolean = false)

    /** 앱에 넣어 둔 글꼴을 읽기 위한 자산 관리자. 앱 시작 때 넣는다. */
    var assets: AssetManager? = null
    private val fontCache = HashMap<String, Typeface>()

    private fun typeface(o: PrintOptions): Typeface {
        val style = if (o.bold) Typeface.BOLD else Typeface.NORMAL
        return when (o.fontFamily) {
            "barlow" -> {
                val file = "fonts/BarlowSemiCondensed-" + (if (o.bold) "Bold" else "Regular") + ".ttf"
                fontCache[file] ?: try {
                    Typeface.createFromAsset(assets, file).also { fontCache[file] = it }
                } catch (e: Exception) {
                    Typeface.create("sans-serif-condensed", style)
                }
            }
            "condensed" -> Typeface.create("sans-serif-condensed", style)
            "serif" -> Typeface.create(Typeface.SERIF, style)
            "mono" -> Typeface.create(Typeface.MONOSPACE, style)
            else -> Typeface.create(Typeface.SANS_SERIF, style)
        }
    }

    /**
     * 영역 크기(dot)에 맞춰 문구를 흰 바탕 검은 글씨로 그린다.
     * 비트맵이 곧 영역이라 영역 밖으로는 인쇄되지 않는다. 지정한 크기가 넘치면 들어갈 때까지 줄인다.
     */
    fun renderText(w: Int, h: Int, lines: List<String>, o: PrintOptions, dpi: Int): Rendered {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(8), h.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        if (lines.isEmpty()) return Rendered(bmp, 0f, true)
        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            typeface = typeface(o)
            letterSpacing = o.letterSpacing.coerceIn(-0.1f, 0.5f)
        }
        val pad = PAD
        val lineGap = LINE_GAP
        val minSize = 8f
        var size = if (o.fontPt > 0f) o.fontPt / 72f * dpi else (h - pad * 2) / (lines.size * lineGap)
        size = size.coerceAtLeast(minSize)
        fun fitsAt(s: Float): Boolean {
            paint.textSize = s
            val widest = lines.maxOf { paint.measureText(it) }
            return widest <= w - pad * 2 && s * lineGap * lines.size <= h - pad * 2 + 0.5f
        }
        // 영역을 넘지 않을 때까지 줄인다
        while (size > minSize && !fitsAt(size)) size -= 0.5f
        val fits = fitsAt(size)
        paint.textSize = size
        val fm = paint.fontMetrics
        val lineH = size * lineGap
        val total = lineH * lines.size
        var y = (h - total) / 2f + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
        for (line in lines) {
            val x = if (o.centered) (w - paint.measureText(line)) / 2f else pad
            c.drawText(line, x, y, paint)
            y += lineH
        }
        return Rendered(bmp, size * 72f / dpi, fits)
    }

    fun rotate(b: Bitmap, deg: Int): Bitmap {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(d.toFloat()) }, false)
    }

    /** 템플릿의 인쇄 영역 크기(사진 방향 기준, dot). */
    fun areaDots(t: LabelTemplate, dpi: Int): Pair<Int, Int> {
        val a = Ezpl.areaInLabel(t)
        val wd = Ezpl.dots(t.widthMm, dpi)
        val ld = Ezpl.dots(t.lengthMm, dpi)
        val sideways = t.rotation % 180 != 0
        val photoW = if (sideways) ld else wd
        val photoH = if (sideways) wd else ld
        return (a.w * photoW).toInt().coerceAtLeast(8) to (a.h * photoH).toInt().coerceAtLeast(8)
    }

    private const val PAD = 2f
    private const val LINE_GAP = 1.18f

    /**
     * 글자 크기를 직접 정했는데 인쇄 영역보다 크면, 라벨 여백 안에서 영역을 그 크기에 맞게 넓힌다.
     * 기준(anchor) 쪽 가장자리는 그대로 두고 반대쪽으로 넓어진다. 자동(0)이면 그대로 돌려준다.
     */
    fun fitArea(t: LabelTemplate, lines: List<String>, dpi: Int): LabelTemplate {
        val o = t.options
        if (o.fontPt <= 0f || lines.isEmpty()) return t
        val paint = Paint().apply {
            isAntiAlias = false
            typeface = typeface(o)
            letterSpacing = o.letterSpacing.coerceIn(-0.1f, 0.5f)
            textSize = o.fontPt / 72f * dpi
        }
        val needW = lines.maxOf { paint.measureText(it) } + PAD * 2 + 3f
        val needH = paint.textSize * LINE_GAP * lines.size + PAD * 2 + 3f
        val box = Ezpl.mmBox(t)
        val wMm = max(box.w, needW * 25.4f / dpi)
        val hMm = max(box.h, needH * 25.4f / dpi)
        if (wMm <= box.w && hMm <= box.h) return t
        return Ezpl.withMmBox(t, box.copy(w = wMm, h = hMm))
    }

    /** 인쇄 작업 데이터를 만든다. */
    fun buildJob(t0: LabelTemplate, s: PrinterSettings, lines: List<String>, count: Int): ByteArray {
        val t = fitArea(t0, lines, s.dpi)
        val (aw, ah) = areaDots(t, s.dpi)
        val text = renderText(aw, ah, lines, t.options, s.dpi).bmp
        val rotated = rotate(text, t.rotation)
        val p = Ezpl.placement(t, s.dpi)
        val px = IntArray(rotated.width * rotated.height)
        rotated.getPixels(px, 0, rotated.width, 0, 0, rotated.width, rotated.height)
        val (data, wb) = Ezpl.pack(px, rotated.width, rotated.height, s.invertBitmap)
        return Ezpl.job(t, s, p.x, p.y, data, wb, rotated.height, count)
    }

    /** 라벨 사진 위에 인쇄 결과를 겹쳐 보여 주는 미리보기. */
    fun preview(photo: Bitmap, t0: LabelTemplate, lines: List<String>, dpi: Int): Pair<Bitmap, Rendered> {
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        val t = fitArea(t0, lines, dpi)
        val (aw, ah) = areaDots(t, dpi)
        val r = renderText(aw, ah, lines, t.options, dpi)
        val rendered = Rendered(r.bmp, r.pt, r.fits, grown = t.area != t0.area)
        val text = rendered.bmp
        val a = t.area.normalized()
        val dst = RectF(a.l * out.width, a.t * out.height, a.r * out.width, a.b * out.height)
        val paint = Paint().apply {
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        Canvas(out).drawBitmap(text, null, dst, paint)
        return out to rendered
    }
}
