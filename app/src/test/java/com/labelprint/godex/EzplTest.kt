package com.labelprint.godex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EzplTest {

    @Test
    fun linesVerticalWithPrefixes() {
        val o = PrintOptions(printMfg = true)
        assertEquals(
            listOf("Batch: QB2408", "MFG Date: 14-09-2026", "Best Before: 14-09-2030"),
            Ezpl.lines(o, "QB2408", "14-09-2026", "14-09-2030"),
        )
    }

    @Test
    fun linesHorizontalNoPrefixBbStyle() {
        val o = PrintOptions(batchPrefix = false, bbStyle = "BB", vertical = false)
        assertEquals(listOf("QB2408   BB: 14-09-2030"), Ezpl.lines(o, "QB2408", "ignored", "14-09-2030"))
    }

    @Test
    fun linesBatchLastOrder() {
        val o = PrintOptions(batchPrefix = false, bbPrefix = false, printMfg = true, mfgPrefix = false, order = ORDER_BATCH_LAST)
        assertEquals(listOf("M", "B", "X"), Ezpl.lines(o, "X", "M", "B"))
    }

    @Test
    fun blankLabelTemplateFillsValues() {
        val t = LabelTemplate(id = "a", name = "a", imagePath = "", blank = true)
        assertEquals(
            listOf(
                "NZQueenBee Multiflora Honey", "BATCH : QJ4", "MFG: 29/07/2026", "BB: 28/07/2031",
                "RMP: NZQBEE1/01", "Store Below 20 degrees", "Product of New Zealand",
            ),
            Ezpl.linesFor(t, "QJ4", "29/07/2026", "28/07/2031"),
        )
    }

    @Test
    fun blankLabelDropsMfgLineWhenEmptyAndKeepsSpacing() {
        val lines = Ezpl.templateLines("BATCH : {batch}\nMFG:    {MFG}\n\nBB:      {Bb}\n", " QJ4 ", "", "28/07/2031")
        assertEquals(listOf("BATCH : QJ4", "", "BB:      28/07/2031"), lines)
    }

    @Test
    fun rotateRectKeepsSizeAndMovesCorners() {
        val r = NRect(0.8f, 0.7f, 0.95f, 0.9f) // 사진 오른쪽 아래
        val r90 = Ezpl.rotateRect(r, 90)       // 왼쪽이 먼저 나옴 → 프린터 기준 왼쪽 아래
        assertEquals(0.1f, r90.l, 1e-4f); assertEquals(0.8f, r90.t, 1e-4f)
        assertEquals(0.3f, r90.r, 1e-4f); assertEquals(0.95f, r90.b, 1e-4f)
        val r180 = Ezpl.rotateRect(r, 180)
        assertEquals(0.05f, r180.l, 1e-4f); assertEquals(0.1f, r180.t, 1e-4f)
        val back = Ezpl.rotateRect(Ezpl.rotateRect(r, 90), 270)
        assertEquals(r.l, back.l, 1e-4f); assertEquals(r.b, back.b, 1e-4f)
    }

    @Test
    fun placementInDots() {
        // 47 x 143 mm, 300 dpi → 555 x 1689 dot
        val t = LabelTemplate(id = "a", name = "a", imagePath = "", rotation = 0, area = NRect(0.5f, 0.5f, 1f, 1f))
        val p = Ezpl.placement(t, 300)
        assertEquals(555, Ezpl.dots(47f, 300))
        assertEquals(1689, Ezpl.dots(143f, 300))
        assertEquals(278, p.x); assertEquals(845, p.y)
        assertTrue(p.x + p.w <= 556 && p.y + p.h <= 1690)
    }

    @Test
    fun mmBoxFromRightBottomRoundTrips() {
        // 가로 143 × 세로 47 mm 디자인(왼쪽이 먼저 나옴), 오른쪽에서 5mm·아래에서 3mm, 30 × 12 mm
        val t = LabelTemplate(id = "a", name = "a", imagePath = "", rotation = 90)
        assertEquals(143f to 47f, Ezpl.designSize(t))
        val placed = Ezpl.withMmBox(t, Ezpl.MmBox(w = 30f, h = 12f, hOff = 5f, vOff = 3f))
        val a = placed.area
        assertEquals(108f / 143f, a.l, 1e-4f); assertEquals(138f / 143f, a.r, 1e-4f)
        assertEquals(32f / 47f, a.t, 1e-4f); assertEquals(44f / 47f, a.b, 1e-4f)
        val back = Ezpl.mmBox(placed)
        assertEquals(30f, back.w, 0.01f); assertEquals(12f, back.h, 0.01f)
        assertEquals(5f, back.hOff, 0.01f); assertEquals(3f, back.vOff, 0.01f)
    }

    @Test
    fun mmBoxStaysInsideMargins() {
        val t = LabelTemplate(id = "a", name = "a", imagePath = "", rotation = 90, marginRight = 4f, marginBottom = 2f)
        // 오른쪽에서 0mm를 요청해도 여백 4mm 안쪽으로 밀린다
        val box = Ezpl.mmBox(Ezpl.withMmBox(t, Ezpl.MmBox(w = 30f, h = 12f, hOff = 0f, vOff = 0f)))
        assertEquals(4f, box.hOff, 0.01f); assertEquals(2f, box.vOff, 0.01f)
        // 여백 안쪽보다 큰 영역은 줄어든다
        val big = Ezpl.mmBox(Ezpl.withMmBox(t, Ezpl.MmBox(w = 500f, h = 500f, hOff = 0f, vOff = 0f)))
        assertEquals(139f, big.w, 0.01f); assertEquals(45f, big.h, 0.01f)
    }

    @Test
    fun packMsbFirstBlackIsOne() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        // 10 x 2: 첫 줄은 0번·9번 픽셀이 검정, 둘째 줄은 모두 흰색
        val px = IntArray(20) { white }
        px[0] = black; px[9] = black
        val (data, wb) = Ezpl.pack(px, 10, 2, invert = false)
        assertEquals(2, wb)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x40, 0, 0), data)
    }

    @Test
    fun jobHasHeaderBitmapAndEnd() {
        val t = LabelTemplate(id = "a", name = "a", imagePath = "")
        val s = PrinterSettings(darkness = 12, speed = 4, printMode = 2)
        val job = Ezpl.job(t, s, 10, 20, byteArrayOf(1, 2, 3, 4), 2, 2, 5)
        val text = String(job, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("^Q143,3\r\n^W47\r\n^H12\r\n^P5\r\n^S4\r\n^AD\r\n^C1\r\n^R0\r\n^L\r\nQ10,20,2,2\r\n"))
        assertTrue(text.endsWith("\u0001\u0002\u0003\u0004\r\nE\r\n"))
    }

    @Test
    fun parsesPublicFolderPage() {
        val html = """<div class="flip-entry" id="entry-1bdNV_x-9" tabindex="0"><div class="flip-entry-info">
            <a href="x"><div class="flip-entry-title">QB_15 &amp; 250g.ai</div></a></div></div>
            <div class="flip-entry" id="entry-2abc" tabindex="0"><div class="flip-entry-title">b.png</div></div>"""
        val files = Drive.parseFolderHtml(html)
        assertEquals(listOf(Drive.DFile("1bdNV_x-9", "QB_15 & 250g.ai"), Drive.DFile("2abc", "b.png")), files)
    }
}
