package com.labelprint.godex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 라벨과 설정을 폰 안의 JSON 파일로 보관한다. */
class Store(context: Context) {
    private val dir = context.filesDir
    val imageDir = File(dir, "images").apply { mkdirs() }
    private val labelsFile = File(dir, "labels.json")
    private val settingsFile = File(dir, "settings.json")

    fun loadLabels(): List<LabelTemplate> = try {
        val arr = JSONArray(labelsFile.readText())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    fun saveLabels(list: List<LabelTemplate>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        labelsFile.writeText(arr.toString())
    }

    fun loadSettings(): PrinterSettings = try {
        val j = JSONObject(settingsFile.readText())
        val d = PrinterSettings()
        PrinterSettings(
            ip = j.optString("ip", d.ip),
            port = j.optInt("port", d.port),
            dpi = j.optInt("dpi", d.dpi),
            darkness = j.optInt("darkness", d.darkness),
            speed = j.optInt("speed", d.speed),
            printMode = j.optInt("printMode", d.printMode),
            leftMargin = j.optInt("leftMargin", d.leftMargin),
            stopPosition = j.optInt("stopPosition", d.stopPosition),
            invertBitmap = j.optBoolean("invert", d.invertBitmap),
            driveFolderId = j.optString("driveFolderId", d.driveFolderId),
            driveApiKey = j.optString("driveApiKey", d.driveApiKey),
            sheetUrl = j.optString("sheetUrl", d.sheetUrl),
        )
    } catch (e: Exception) { PrinterSettings() }

    fun saveSettings(s: PrinterSettings) {
        val j = JSONObject()
            .put("ip", s.ip).put("port", s.port).put("dpi", s.dpi)
            .put("darkness", s.darkness).put("speed", s.speed).put("printMode", s.printMode)
            .put("leftMargin", s.leftMargin).put("stopPosition", s.stopPosition)
            .put("invert", s.invertBitmap)
            .put("driveFolderId", s.driveFolderId).put("driveApiKey", s.driveApiKey)
            .put("sheetUrl", s.sheetUrl)
        settingsFile.writeText(j.toString())
    }

    private val batchesFile = File(dir, "batches.json")

    /** 마지막으로 읽어 온 배치 목록(인터넷이 없을 때도 쓰려고 보관). */
    fun loadBatches(): List<BatchRow> = try {
        val arr = JSONArray(batchesFile.readText())
        (0 until arr.length()).map { arr.getJSONArray(it) }
            .map { BatchRow(it.getString(0), it.getString(1), it.getString(2), it.optString(3, "")) }
    } catch (e: Exception) { emptyList() }

    fun saveBatches(rows: List<BatchRow>) {
        val arr = JSONArray()
        rows.forEach { arr.put(JSONArray().put(it.batch).put(it.mfg).put(it.bbMgo).put(it.bbUmf)) }
        batchesFile.writeText(arr.toString())
    }

    private fun rect(r: NRect) = JSONArray().put(r.l.toDouble()).put(r.t.toDouble()).put(r.r.toDouble()).put(r.b.toDouble())
    private fun rect(a: JSONArray?, d: NRect) = if (a == null || a.length() < 4) d else
        NRect(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())

    private fun toJson(t: LabelTemplate): JSONObject {
        val o = t.options
        return JSONObject()
            .put("id", t.id).put("name", t.name).put("imagePath", t.imagePath)
            .put("driveFileId", t.driveFileId ?: "")
            .put("widthMm", t.widthMm.toDouble()).put("lengthMm", t.lengthMm.toDouble())
            .put("gapMm", t.gapMm.toDouble()).put("rotation", t.rotation)
            .put("outline", rect(t.outline)).put("area", rect(t.area))
            .put("configured", t.configured)
            .put("marginLeft", t.marginLeft.toDouble()).put("marginTop", t.marginTop.toDouble())
            .put("marginRight", t.marginRight.toDouble()).put("marginBottom", t.marginBottom.toDouble())
            .put("anchorRight", t.anchorRight).put("anchorBottom", t.anchorBottom)
            .put("blank", t.blank).put("textTemplate", t.textTemplate)
            .put("areas", JSONArray().also { arr ->
                t.areas.forEach { a ->
                    arr.put(JSONObject()
                        .put("x", a.xMm.toDouble()).put("y", a.yMm.toDouble())
                        .put("w", a.wMm.toDouble()).put("h", a.hMm.toDouble())
                        .put("text", a.text).put("fontPt", a.fontPt.toDouble())
                        .put("bold", a.bold).put("centered", a.centered))
                }
            })
            .put("options", JSONObject()
                .put("batchPrefix", o.batchPrefix).put("bbPrefix", o.bbPrefix).put("bbStyle", o.bbStyle)
                .put("printMfg", o.printMfg).put("mfgPrefix", o.mfgPrefix).put("vertical", o.vertical)
                .put("order", o.order).put("bold", o.bold).put("centered", o.centered)
                .put("fontPt", o.fontPt.toDouble()).put("dateFormat", o.dateFormat)
                .put("bbKind", o.bbKind).put("fontFamily", o.fontFamily).put("letterSpacing", o.letterSpacing.toDouble()))
    }

    private fun fromJson(j: JSONObject): LabelTemplate {
        val d = LabelTemplate(id = "", name = "", imagePath = "")
        val oj = j.optJSONObject("options") ?: JSONObject()
        val od = PrintOptions()
        val loaded = LabelTemplate(
            id = j.getString("id"),
            name = j.optString("name"),
            imagePath = j.optString("imagePath"),
            driveFileId = j.optString("driveFileId").ifBlank { null },
            widthMm = j.optDouble("widthMm", d.widthMm.toDouble()).toFloat(),
            lengthMm = j.optDouble("lengthMm", d.lengthMm.toDouble()).toFloat(),
            gapMm = j.optDouble("gapMm", d.gapMm.toDouble()).toFloat(),
            rotation = j.optInt("rotation", 0),
            outline = rect(j.optJSONArray("outline"), d.outline),
            area = rect(j.optJSONArray("area"), d.area),
            configured = j.optBoolean("configured", false),
            marginLeft = j.optDouble("marginLeft", 0.0).toFloat(),
            marginTop = j.optDouble("marginTop", 0.0).toFloat(),
            marginRight = j.optDouble("marginRight", 0.0).toFloat(),
            marginBottom = j.optDouble("marginBottom", 0.0).toFloat(),
            anchorRight = j.optBoolean("anchorRight", true),
            anchorBottom = j.optBoolean("anchorBottom", true),
            blank = j.optBoolean("blank", false),
            textTemplate = j.optString("textTemplate", DEFAULT_TEXT_TEMPLATE),
            options = PrintOptions(
                batchPrefix = oj.optBoolean("batchPrefix", od.batchPrefix),
                bbPrefix = oj.optBoolean("bbPrefix", od.bbPrefix),
                bbStyle = oj.optString("bbStyle", od.bbStyle),
                printMfg = oj.optBoolean("printMfg", od.printMfg),
                mfgPrefix = oj.optBoolean("mfgPrefix", od.mfgPrefix),
                vertical = oj.optBoolean("vertical", od.vertical),
                order = oj.optInt("order", od.order),
                bold = oj.optBoolean("bold", od.bold),
                centered = oj.optBoolean("centered", od.centered),
                fontPt = oj.optDouble("fontPt", od.fontPt.toDouble()).toFloat(),
                dateFormat = oj.optString("dateFormat", od.dateFormat),
                fontFamily = oj.optString("fontFamily", od.fontFamily),
                bbKind = oj.optString("bbKind", od.bbKind),
                letterSpacing = oj.optDouble("letterSpacing", od.letterSpacing.toDouble()).toFloat(),
            ),
        )
        // 직접 입력한 영역이 없으면(예전 버전에서 저장한 라벨) 사진 기준 영역을 mm 영역으로 옮긴다.
        val arr = j.optJSONArray("areas")
        val areas = if (arr == null) Ezpl.legacyAreas(loaded) else (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val d0 = PrintArea()
            PrintArea(
                xMm = it.optDouble("x", d0.xMm.toDouble()).toFloat(), yMm = it.optDouble("y", d0.yMm.toDouble()).toFloat(),
                wMm = it.optDouble("w", d0.wMm.toDouble()).toFloat(), hMm = it.optDouble("h", d0.hMm.toDouble()).toFloat(),
                text = it.optString("text", ""), fontPt = it.optDouble("fontPt", 0.0).toFloat(),
                bold = it.optBoolean("bold", true), centered = it.optBoolean("centered", false),
            )
        }
        return loaded.copy(areas = areas)
    }
}
