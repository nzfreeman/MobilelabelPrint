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

    fun load(path: String, maxSide: Int = 1600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 사진에서 라벨만 차지하는 범위(0..1). 찾지 못하면 NRect.FULL. */
    fun detectLabel(bmp: Bitmap): NRect {
        val gw = 200
        val gh = (gw.toFloat() * bmp.height / bmp.width).toInt().coerceIn(8, 800)
        val small = Bitmap.createScaledBitmap(bmp, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        return Ezpl.findLabel(px, gw, gh)
    }

    fun detectLabel(path: String): NRect = load(path, 800)?.let { detectLabel(it) } ?: NRect.FULL

    /** 사진에서 rect 범위만 잘라 낸다. */
    fun crop(bmp: Bitmap, rect: NRect): Bitmap {
        val r = rect.normalized()
        if (r.w >= 0.999f && r.h >= 0.999f) return bmp
        val x = (r.l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val y = (r.t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val w = (r.w * bmp.width).toInt().coerceIn(1, bmp.width - x)
        val h = (r.h * bmp.height).toInt().coerceIn(1, bmp.height - y)
        return Bitmap.createBitmap(bmp, x, y, w, h)
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

    private const val PAD = 2f
    private const val LINE_GAP = 1.18f

    /** 영역 하나의 크기(dot, 라벨을 바로 놓은 방향). */
    fun areaDots(a: PrintArea, dpi: Int): Pair<Int, Int> =
        Ezpl.dots(a.wMm, dpi).coerceIn(8, 6000) to Ezpl.dots(a.hMm, dpi).coerceIn(8, 6000)

    /** 영역 하나를 그 크기의 비트맵으로 그린다. */
    fun renderArea(t: LabelTemplate, a: PrintArea, lines: List<String>, dpi: Int): Rendered {
        val (aw, ah) = areaDots(a, dpi)
        return renderText(aw, ah, lines, t.options.copy(fontPt = a.fontPt, bold = a.bold, centered = a.centered), dpi)
    }

    /** 인쇄 작업 데이터를 만든다. 내용이 없는 영역은 건너뛴다. */
    fun buildJob(t: LabelTemplate, s: PrinterSettings, batch: String, mfg: String, bb: String, count: Int): ByteArray {
        val graphics = t.areas.mapNotNull { a ->
            val lines = Ezpl.areaLines(a, batch, mfg, bb, t.options, t.picks)
            if (lines.isEmpty()) return@mapNotNull null
            val rotated = rotate(renderArea(t, a, lines, s.dpi).bmp, t.rotation)
            val p = Ezpl.areaPlacement(t, a, s.dpi)
            val px = IntArray(rotated.width * rotated.height)
            rotated.getPixels(px, 0, rotated.width, 0, 0, rotated.width, rotated.height)
            val (data, wb) = Ezpl.pack(px, rotated.width, rotated.height, s.invertBitmap, t.upsideDown)
            Ezpl.Graphic(p.x, p.y, data, wb, rotated.height)
        }
        return Ezpl.job(t, s, graphics, count)
    }

    /** 미리보기. areas는 영역 순서대로 그린 결과(내용이 없으면 null). */
    class LabelPreview(val bmp: Bitmap, val areas: List<Rendered?>)

    /** 입력한 라벨 크기대로 흰 종이를 그리고 그 위에 각 영역과 인쇄될 글자를 놓는다. */
    fun previewLabel(t: LabelTemplate, batch: String, mfg: String, bb: String, dpi: Int): LabelPreview {
        val (dw, dh) = Ezpl.designSize(t)
        val scale = minOf(dpi / 25.4f, 1400f / max(dw, dh).coerceAtLeast(1f))
        val bmp = Bitmap.createBitmap(
            (dw * scale).toInt().coerceAtLeast(20), (dh * scale).toInt().coerceAtLeast(20), Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val box = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = max(2f, scale * 0.25f) }
        val num = Paint().apply { color = Color.RED; isAntiAlias = true; textSize = max(14f, scale * 2.5f) }
        val draw = Paint().apply { isFilterBitmap = true }
        val out = t.areas.mapIndexed { i, a ->
            val dst = RectF(a.xMm * scale, a.yMm * scale, (a.xMm + a.wMm) * scale, (a.yMm + a.hMm) * scale)
            val lines = Ezpl.areaLines(a, batch, mfg, bb, t.options, t.picks)
            val r = if (lines.isEmpty() || a.wMm <= 0f || a.hMm <= 0f) null else renderArea(t, a, lines, dpi)
            if (r != null) c.drawBitmap(r.bmp, null, dst, draw)
            c.drawRect(dst, box)
            val ny = if (dst.top > num.textSize + 2f) dst.top - 3f else dst.bottom + num.textSize
            c.drawText("${i + 1}", dst.left, ny, num)
            r
        }
        val edge = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 4f }
        c.drawRect(2f, 2f, bmp.width - 2f, bmp.height - 2f, edge)
        return LabelPreview(bmp, out)
    }
}
