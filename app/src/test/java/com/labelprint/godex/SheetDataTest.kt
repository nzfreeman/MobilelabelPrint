package com.labelprint.godex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetDataTest {

    @Test
    fun csvUrlFromLinkKeepsTab() {
        val link = "https://docs.google.com/spreadsheets/d/1AbC_def-GhIjKlMnOpQrStUvWxYz012345/edit?gid=987#gid=987"
        assertEquals(
            "https://docs.google.com/spreadsheets/d/1AbC_def-GhIjKlMnOpQrStUvWxYz012345/export?format=csv&gid=987",
            SheetData.csvUrl(link),
        )
        assertEquals(
            "https://docs.google.com/spreadsheets/d/1AbC_def-GhIjKlMnOpQrStUvWxYz012345/export?format=csv",
            SheetData.csvUrl("1AbC_def-GhIjKlMnOpQrStUvWxYz012345"),
        )
        assertNull(SheetData.csvUrl("hello"))
    }

    @Test
    fun parsesQuotedCells() {
        val t = SheetData.parseCsv("a,\"b,1\",\"say \"\"hi\"\"\"\r\nc,,d\n")
        assertEquals(listOf(listOf("a", "b,1", "say \"hi\""), listOf("c", "", "d")), t)
    }

    @Test
    fun realSheetLayoutWithMgoAndUmfColumns() {
        // 실제 시트와 같은 머리글. 둘째 줄의 설명 줄은 빼야 한다.
        val csv = "sequence,Batch,Best Before(MGO),Best Before(UMF),MFG Date\n" +
            ",Batch No,Best Before,,\n" +
            "1,232427,10/04/2031,,\n" +
            "20,QB2359 ,9/09/2030,9/09/2029,1/09/2026\n"
        val rows = SheetData.rows(SheetData.parseCsv(csv))
        assertEquals(
            listOf(
                BatchRow("232427", "", "10/04/2031", ""),
                BatchRow("QB2359", "1/09/2026", "9/09/2030", "9/09/2029"),
            ),
            rows,
        )
        assertEquals("9/09/2030", rows[1].bb(BB_MGO))
        assertEquals("9/09/2029", rows[1].bb(BB_UMF))
        assertEquals("", rows[0].bb(BB_UMF))
    }

    @Test
    fun headerDecidesColumnsInAnyOrder() {
        val csv = "Product,MFG Date,Best Before,Batch No.\n" +
            "Manuka 15+,14/09/2026,14/09/2030,QB2408\n" +
            ",,,\n" +
            "Manuka 20+,07/10/2026,07/10/2030,GCRB-32\n"
        assertEquals(
            listOf(BatchRow("QB2408", "14/09/2026", "14/09/2030"), BatchRow("GCRB-32", "07/10/2026", "07/10/2030")),
            SheetData.rows(SheetData.parseCsv(csv)),
        )
    }

    @Test
    fun koreanHeadersAndMissingMfgColumn() {
        val csv = "배치번호,유통기한\nB0481,14-04-2030\n"
        assertEquals(listOf(BatchRow("B0481", "", "14-04-2030")), SheetData.rows(SheetData.parseCsv(csv)))
    }

    @Test
    fun noHeaderUsesFirstThreeColumns() {
        val csv = "21B4B2,06-08-2030,06-08-2026\nB23078,20-11-2030,\n"
        assertEquals(
            listOf(BatchRow("21B4B2", "06-08-2026", "06-08-2030"), BatchRow("B23078", "", "20-11-2030")),
            SheetData.rows(SheetData.parseCsv(csv)),
        )
    }

    @Test
    fun formatsSheetDatesDayFirst() {
        assertEquals("06/08/2030", SheetData.formatDate("6/08/2030", "dd/MM/yyyy"))
        assertEquals("14-09-2030", SheetData.formatDate("14/9/2030", "dd-MM-yyyy"))
        assertEquals("2030-09-14", SheetData.formatDate("14.09.2030", "yyyy-MM-dd"))
        assertEquals("14/09/2030", SheetData.formatDate("2030-09-14", "dd/MM/yyyy"))
        // 날짜로 읽히지 않으면 그대로
        assertEquals("Sep 2030", SheetData.formatDate("Sep 2030", "dd/MM/yyyy"))
        assertEquals("", SheetData.formatDate("", "dd/MM/yyyy"))
    }

    @Test
    fun folderPageMarksSheets() {
        val html = """<div class="flip-entry" id="entry-1sheet"><a href="https://docs.google.com/spreadsheets/d/1sheet/edit">
            <div class="flip-entry-title">Batch list</div></a></div>
            <div class="flip-entry" id="entry-2ai"><a href="https://drive.google.com/file/d/2ai/view">
            <div class="flip-entry-title">label.ai</div></a></div>"""
        val files = Drive.parseFolderHtml(html)
        assertEquals(listOf(true, false), files.map { it.isSheet })
    }
}
