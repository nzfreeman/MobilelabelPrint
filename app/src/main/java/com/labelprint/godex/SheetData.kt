package com.labelprint.godex

/** Best Before 종류. */
const val BB_MGO = "MGO"
const val BB_UMF = "UMF"

/** 시트 한 줄: 배치번호와 날짜들(시트에 보이는 글자 그대로). Best Before는 MGO용과 UMF용이 따로 있다. */
data class BatchRow(val batch: String, val mfg: String, val bbMgo: String, val bbUmf: String = "") {
    fun bb(kind: String): String = if (kind == BB_UMF) bbUmf else bbMgo
}

/** 앱에서 직접 저장한 배치. synced는 구글 시트로 보냈는지. 지우는 기능은 없다. */
data class SavedBatch(val row: BatchRow, val time: Long, val synced: Boolean = false)

/** 구글 시트 주소와 CSV 내용 해석. 안드로이드 클래스에 의존하지 않는다. */
object SheetData {

    /** 시트 주소(또는 ID만)에서 문서 ID를 꺼낸다. */
    fun sheetId(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        Regex("/spreadsheets/d/([\\w-]+)").find(s)?.let { return it.groupValues[1] }
        return if (Regex("[\\w-]{20,}").matches(s)) s else null
    }

    /** 주소에 탭 번호(gid)가 있으면 꺼낸다. */
    fun gid(input: String): String? = Regex("[#&?]gid=(\\d+)").find(input)?.groupValues?.get(1)

    /** 공개 시트를 CSV로 받는 주소. */
    fun csvUrl(input: String): String? {
        val id = sheetId(input) ?: return null
        val g = gid(input)
        return "https://docs.google.com/spreadsheets/d/$id/export?format=csv" + if (g != null) "&gid=$g" else ""
    }

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /** 비어 있지 않은 칸만 덮어쓴다. */
    private fun overlay(base: BatchRow, top: BatchRow) = BatchRow(
        batch = base.batch,
        mfg = top.mfg.ifBlank { base.mfg },
        bbMgo = top.bbMgo.ifBlank { base.bbMgo },
        bbUmf = top.bbUmf.ifBlank { base.bbUmf },
    )

    /**
     * 배치를 저장 목록에 넣는다. 같은 배치번호가 있으면 줄을 늘리지 않고 입력한 칸만 고친다.
     * 고친 줄은 시트로 다시 보내도록 synced를 끈다.
     */
    fun addSaved(list: List<SavedBatch>, row: BatchRow, now: Long): List<SavedBatch> {
        val clean = BatchRow(row.batch.trim(), row.mfg.trim(), row.bbMgo.trim(), row.bbUmf.trim())
        if (clean.batch.isEmpty()) return list
        return if (list.any { same(it.row.batch, clean.batch) }) {
            list.map { if (same(it.row.batch, clean.batch)) SavedBatch(overlay(it.row, clean), now, false) else it }
        } else list + SavedBatch(clean, now, false)
    }

    /**
     * 고를 수 있는 배치 목록: 앱에 저장한 것(최근 것부터)을 먼저, 그 뒤에 시트 목록.
     * 양쪽에 다 있는 배치는 한 번만 보이고, 앱에 적은 칸이 시트 값보다 우선한다.
     */
    fun mergeBatches(saved: List<SavedBatch>, sheet: List<BatchRow>): List<BatchRow> {
        val mine = saved.sortedByDescending { it.time }.map { s ->
            sheet.firstOrNull { same(it.batch, s.row.batch) }?.let { overlay(it, s.row) } ?: s.row
        }
        return mine + sheet.filter { r -> saved.none { same(it.row.batch, r.batch) } }
    }

    fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    private fun isBatch(h: String) = "batch" in h || "배치" in h || "lot" in h
    private fun isBb(h: String) =
        "best" in h || h == "bb" || "b.b" in h || "bb " in h || " bb" in h || "bb(" in h ||
            "exp" in h || "유통" in h || "소비" in h
    private fun isMfg(h: String) = "mfg" in h || "manuf" in h || "제조" in h || "생산" in h || ("prod" in h && "date" in h)

    /**
     * 표에서 배치 목록을 만든다. 앞쪽 5줄 안에서 "Batch"가 들어간 머리글 줄을 찾아 열을 정한다.
     * Best Before 머리글에 "UMF"가 있으면 UMF용, 그 밖의 Best Before 열은 MGO용으로 읽는다.
     * 머리글이 없으면 1열=배치, 2열=Best Before(MGO), 3열=MFG Date로 읽는다.
     */
    fun rows(table: List<List<String>>): List<BatchRow> {
        var headerIndex = -1
        var cb = 0
        var cMgo = 1
        var cUmf = -1
        var cm = 2
        for (r in 0 until minOf(5, table.size)) {
            val h = table[r].map { it.trim().lowercase() }
            val b = h.indexOfFirst(::isBatch)
            if (b >= 0) {
                headerIndex = r
                cb = b
                val bbCols = h.indices.filter { isBb(h[it]) }
                cUmf = bbCols.firstOrNull { "umf" in h[it] } ?: -1
                cMgo = bbCols.firstOrNull { "mgo" in h[it] } ?: bbCols.firstOrNull { it != cUmf } ?: -1
                cm = h.indexOfFirst(::isMfg)
                break
            }
        }
        fun cell(row: List<String>, c: Int) = if (c in row.indices) row[c].trim() else ""
        return table.drop(headerIndex + 1)
            .map { BatchRow(cell(it, cb), cell(it, cm), cell(it, cMgo), cell(it, cUmf)) }
            // 빈 줄과, 머리글 아래에 한 줄 더 있는 설명 줄("Batch No" 등)은 뺀다
            .filter { it.batch.isNotEmpty() && !(headerIndex >= 0 && isBatch(it.batch.lowercase()) && it.batch.contains(' ')) }
    }

    /**
     * 시트 날짜(일/월/연 또는 연-월-일, 구분자는 / - .)를 고른 형식으로 바꾼다.
     * 날짜로 읽히지 않으면 원래 글자를 그대로 돌려준다.
     */
    fun formatDate(text: String, pattern: String): String {
        val s = text.trim()
        var d: Int; var m: Int; var y: Int
        val dmy = Regex("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})$").find(s)
        val ymd = Regex("^(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})$").find(s)
        when {
            dmy != null -> { d = dmy.groupValues[1].toInt(); m = dmy.groupValues[2].toInt(); y = dmy.groupValues[3].toInt() }
            ymd != null -> { y = ymd.groupValues[1].toInt(); m = ymd.groupValues[2].toInt(); d = ymd.groupValues[3].toInt() }
            else -> return s
        }
        if (m !in 1..12 || d !in 1..31) return s
        return pattern
            .replace("yyyy", y.toString().padStart(4, '0'))
            .replace("MM", m.toString().padStart(2, '0'))
            .replace("dd", d.toString().padStart(2, '0'))
    }
}
