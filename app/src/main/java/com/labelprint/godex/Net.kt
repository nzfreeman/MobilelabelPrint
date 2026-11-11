package com.labelprint.godex

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** 공개 Google Drive 폴더 읽기(로그인 없음). */
object Drive {
    data class DFile(val id: String, val name: String, val isSheet: Boolean = false)

    /** 공개 시트(또는 설정이 비어 있으면 라벨 폴더 안의 첫 시트)에서 배치 목록을 읽는다. */
    fun fetchBatches(s: PrinterSettings): List<BatchRow> {
        val source = s.sheetUrl.ifBlank {
            list(s.driveFolderId, s.driveApiKey).firstOrNull { it.isSheet }?.id
                ?: throw IllegalStateException("시트 주소가 설정되지 않았고 라벨 폴더에도 시트가 없습니다")
        }
        val url = SheetData.csvUrl(source) ?: throw IllegalStateException("시트 주소를 알아볼 수 없습니다")
        val c = get(url)
        val type = c.contentType ?: ""
        if (c.responseCode != 200 || !type.contains("csv")) {
            throw IllegalStateException("시트를 읽을 수 없습니다. '링크가 있는 모든 사용자'에게 공개되어 있는지 확인하세요")
        }
        val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return SheetData.rows(SheetData.parseCsv(text))
    }

    private fun get(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LabelPrint")
        return c
    }

    /**
     * 배치 한 줄을 시트의 Apps Script 웹 앱으로 보낸다(docs/sheet-sync.md). 실패하면 예외를 던진다.
     * Apps Script는 POST에 302로 답하므로 그 주소를 한 번 더 읽어 결과를 확인한다.
     */
    fun postBatch(url: String, r: BatchRow) {
        val body = JSONObject()
            .put("batch", r.batch).put("mfg", r.mfg).put("bbMgo", r.bbMgo).put("bbUmf", r.bbUmf)
            .toString().toByteArray(Charsets.UTF_8)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
        c.outputStream.use { it.write(body) }
        var code = c.responseCode
        val text = if (code in 301..303 || code == 307) {
            val next = get(c.getHeaderField("Location") ?: throw IllegalStateException("시트 응답에 주소가 없습니다"))
            code = next.responseCode
            if (code == 200) next.inputStream.bufferedReader().use { it.readText() } else ""
        } else if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
        if (code != 200) throw IllegalStateException("시트 응답 $code")
        if (!Regex("\"ok\"\\s*:\\s*true").containsMatchIn(text)) throw IllegalStateException("시트가 저장을 거부했습니다")
    }

    fun list(folderId: String, apiKey: String): List<DFile> {
        if (apiKey.isNotBlank()) {
            val q = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
            val c = get(
                "https://www.googleapis.com/drive/v3/files?q=$q&pageSize=200" +
                    "&fields=files(id,name,mimeType)&key=${URLEncoder.encode(apiKey, "UTF-8")}"
            )
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).getJSONArray("files")
            return (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("mimeType") != "application/vnd.google-apps.folder" }
                .map {
                    DFile(
                        it.getString("id"), it.getString("name"),
                        it.optString("mimeType") == "application/vnd.google-apps.spreadsheet",
                    )
                }
        }
        // API 키가 없으면 공개 폴더의 목록 페이지를 읽는다.
        val html = get("https://drive.google.com/embeddedfolderview?id=$folderId")
            .inputStream.bufferedReader().use { it.readText() }
        return parseFolderHtml(html)
    }

    fun parseFolderHtml(html: String): List<DFile> {
        val re = Regex("id=\"entry-([\\w-]+)\"([\\s\\S]*?)flip-entry-title\">([^<]*)<")
        return re.findAll(html).map {
            DFile(it.groupValues[1], unescape(it.groupValues[3]), "/spreadsheets/" in it.groupValues[2])
        }.toList()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    /** 파일의 미리보기 이미지를 받는다(.ai, .pdf도 이미지로 온다). */
    fun downloadPreview(fileId: String, dest: File) {
        val c = get("https://drive.google.com/thumbnail?id=$fileId&sz=w2000")
        if (c.responseCode != 200) throw IllegalStateException("드라이브 응답 ${c.responseCode}")
        dest.parentFile?.mkdirs()
        c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }
}

/** 프린터와 9100 포트로 직접 통신. */
object PrinterClient {

    fun send(ip: String, port: Int, data: ByteArray, readReply: Boolean = false): String {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 5000)
            s.soTimeout = 2500
            val out = s.getOutputStream()
            out.write(data)
            out.flush()
            if (!readReply) return ""
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            try {
                while (true) {
                    val n = s.getInputStream().read(buf)
                    if (n <= 0) break
                    sb.append(String(buf, 0, n, Charsets.US_ASCII))
                    if (sb.contains('\n')) break
                }
            } catch (e: SocketTimeoutException) {
                // 응답 없음
            }
            return sb.toString().trim()
        }
    }

    /** 상태 코드(예: "00"). */
    fun status(s: PrinterSettings): String =
        send(s.ip, s.port, "~S,CHECK\r\n".toByteArray(Charsets.US_ASCII), readReply = true)

    /** 오류 상태 해제(패널의 취소 키와 같음). */
    fun cancel(s: PrinterSettings) {
        send(s.ip, s.port, "~S,CANCEL\r\n".toByteArray(Charsets.US_ASCII))
    }
}
