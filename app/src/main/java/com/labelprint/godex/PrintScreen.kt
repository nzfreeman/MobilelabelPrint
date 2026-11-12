package com.labelprint.godex

import android.app.DatePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

// ───────────────────────── 라벨 목록 ─────────────────────────

@Composable
internal fun HomeScreen(
    store: Store,
    labels: List<LabelTemplate>,
    logs: List<PrintLog>,
    onLog: () -> Unit,
    onPhrases: () -> Unit,
    onAdd: (LabelTemplate) -> Unit,
    onOpen: (LabelTemplate) -> Unit,
    onEdit: (LabelTemplate) -> Unit,
    onDelete: (LabelTemplate) -> Unit,
    onSettings: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<LabelTemplate?>(null) }
    var message by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun register(photo: String, crop: NRect = NRect.FULL) {
        val base = LabelTemplate(
            id = UUID.randomUUID().toString(), name = "새 라벨 ${labels.size + 1}", imagePath = photo, outline = crop,
        )
        val t = base.copy(areas = listOf(Ezpl.newArea(base)))
        onAdd(t)
        onEdit(t)
    }

    val picker = rememberPhotoPicker(store) { path ->
        if (path == null) message = "사진을 읽지 못했습니다." else scope.launch {
            // 사진에서 라벨만 보이게 자를 범위를 찾아 함께 저장한다.
            register(path, withContext(Dispatchers.IO) { Imaging.detectLabel(path) })
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("라벨 인쇄", action = {
            Row {
                TextButton(onClick = onLog) { Text("인쇄 기록") }
                TextButton(onClick = onSettings) { Text("프린터 설정") }
            }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = picker.camera) { Text("사진 찍어 등록") }
            OutlinedButton(onClick = picker.gallery) { Text("갤러리에서") }
            OutlinedButton(onClick = { register("") }) { Text("사진 없이") }
        }
        Text(
            "라벨 사진을 찍어 등록한 뒤 크기와 인쇄 영역을 mm로 입력합니다. 사진은 라벨을 알아보는 용도이고, 등록한 값은 라벨마다 저장됩니다.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
        )
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onPhrases) { Text("미리 써 둔 문구 관리 (제품명, 무게, 보관방법 등)") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        if (labels.isEmpty()) Text("등록된 라벨이 없습니다. 라벨 사진을 찍어 등록하세요.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(labels, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(t) }) {
                    Column(Modifier.padding(12.dp)) {
                        val (w, h) = Ezpl.designSize(t)
                        val bmp = rememberLabelPhoto(t.imagePath, t.outline, 900)
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), contentDescription = t.name,
                                modifier = Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Fit,
                            )
                        }
                        Text(t.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Text(
                            if (t.configured) "${mm(w)} × ${mm(h)} mm · 인쇄 영역 ${t.areas.size}개 · 눌러서 인쇄"
                            else "라벨 설정 필요 · 눌러서 설정",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (t.configured) Color.Unspecified else MaterialTheme.colorScheme.error,
                        )
                        val mine = logs.filter { it.labelId == t.id }
                        if (mine.isNotEmpty()) {
                            Text(
                                "지금까지 ${mine.sumOf { it.count }}장 인쇄 · 마지막 ${logTime(mine.last().time)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row {
                            TextButton(onClick = { onEdit(t) }) { Text("라벨 설정") }
                            TextButton(onClick = { confirmDelete = t }) { Text("삭제") }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("라벨 삭제") },
            text = { Text("'${t.name}' 라벨과 설정을 이 폰에서 삭제합니다.") },
            confirmButton = { TextButton(onClick = { onDelete(t); confirmDelete = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }
}

internal fun logTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(ms)

// ───────────────────────── 미리 써 둔 문구 ─────────────────────────

@Composable
internal fun PhraseScreen(phrases: List<Phrase>, onChange: (List<Phrase>) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Header("미리 써 둔 문구", onBack = onBack)
        Text(
            "항목마다 쓸 문구를 한 줄에 하나씩 적어 두세요. 라벨 내용에 {PRODUCT} 같은 자리를 넣으면 " +
                "인쇄 화면에서 이 목록 중 하나를 고르거나 '인쇄 안 함'을 고를 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        phrases.forEach { p ->
            key(p.key) {
                // 입력 중인 줄바꿈과 빈 줄은 그대로 두고, 저장할 때만 빈 줄을 뺀다.
                var text by remember { mutableStateOf(p.options.joinToString("\n")) }
                Section("${p.title}  ·  내용에 넣는 자리 {${p.key}}")
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v
                        val options = v.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                        onChange(phrases.map { if (it.key == p.key) it.copy(options = options) else it })
                    },
                    label = { Text("${p.title} 문구 (줄마다 하나)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────────────────── 인쇄 기록 ─────────────────────────

@Composable
internal fun LogScreen(logs: List<PrintLog>, onClear: () -> Unit, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("인쇄 기록", onBack = onBack, action = {
            TextButton(onClick = { confirm = true }, enabled = logs.isNotEmpty()) { Text("기록 지우기") }
        })
        if (logs.isEmpty()) {
            Text("아직 인쇄 기록이 없습니다. 프린터로 보낼 때마다 여기에 남습니다.")
        } else {
            Text("전체 ${logs.sumOf { it.count }}장 (${logs.size}번 인쇄)", fontWeight = FontWeight.Bold)
            logs.groupBy { it.labelName }.forEach { (name, list) ->
                Text("$name: ${list.sumOf { it.count }}장", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn {
                items(logs.asReversed()) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("${logTime(e.time)} · ${e.labelName} · ${e.count}장", fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(
                                e.batch.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                e.bb.takeIf { it.isNotBlank() }?.let { "BB $it" },
                                e.mfg.takeIf { it.isNotBlank() }?.let { "MFG $it" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("기록 지우기") },
            text = { Text("인쇄 기록 ${logs.size}건을 모두 지웁니다. 되돌릴 수 없습니다.") },
            confirmButton = { TextButton(onClick = { onClear(); confirm = false }) { Text("지우기") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}

internal fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

/** mm 값 표시용: 소수 첫째 자리까지. */
internal fun mm(v: Float): String = fmt(Math.round(v * 10f) / 10f)

// ───────────────────────── 인쇄 ─────────────────────────

@Composable
internal fun PrintScreen(
    t: LabelTemplate,
    settings: PrinterSettings,
    batches: List<BatchRow>,
    phrases: List<Phrase>,
    savedCount: Int,
    waitingCount: Int,
    onSaveBatch: (BatchRow) -> Unit,
    onPhrases: () -> Unit,
    onRefreshBatches: suspend () -> String,
    onTemplate: (LabelTemplate) -> Unit,
    onPrinted: (PrintLog) -> Unit,
    onSettings: (PrinterSettings) -> Unit,
    onEditArea: () -> Unit,
    onBack: () -> Unit,
) {
    var showBatches by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val o = t.options
    var batch by remember { mutableStateOf("") }
    var mfg by remember { mutableStateOf("") }
    var bb by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("1") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun setOptions(n: PrintOptions) = onTemplate(t.copy(options = n))

    val needBatch = Ezpl.uses(t, "batch")
    val needBb = Ezpl.uses(t, "bb")
    val needMfg = Ezpl.uses(t, "mfg")
    // 문구 항목은 라벨에서 고른 것(없으면 목록의 첫 문구)을 넣어서 인쇄한다.
    val usedPhrases = phrases.filter { Ezpl.uses(t, it.key) }
    val pt = t.copy(picks = Ezpl.effectivePicks(t, phrases))

    // 시트에서 고른 줄. MGO/UMF를 바꾸면 이 줄에서 날짜를 다시 가져온다.
    var picked by remember { mutableStateOf<BatchRow?>(null) }
    var sheetNote by remember { mutableStateOf("") }

    fun applyRow(row: BatchRow, kind: String) {
        batch = row.batch
        val date = row.bb(kind)
        if (date.isNotBlank()) {
            bb = SheetData.formatDate(date, o.dateFormat)
            sheetNote = "시트의 ${kind}용 Best Before를 넣었습니다."
        } else {
            bb = ""
            sheetNote = "시트에 이 배치의 ${kind}용 Best Before가 없습니다. 직접 입력하거나 종류를 바꾸세요."
        }
        if (row.mfg.isNotBlank()) mfg = SheetData.formatDate(row.mfg, o.dateFormat)
    }

    fun pickDate(onPicked: (String) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(context, { _, y, m, d ->
            val day = Calendar.getInstance().apply { set(y, m, d) }
            onPicked(SimpleDateFormat(o.dateFormat, Locale.US).format(day.time))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    var preview by remember { mutableStateOf<Imaging.LabelPreview?>(null) }
    LaunchedEffect(pt, batch, mfg, bb, settings.dpi) {
        preview = withContext(Dispatchers.Default) {
            Imaging.previewLabel(
                pt, batch.ifBlank { "QB0000" }, if (batch.isBlank() && mfg.isBlank()) "01/01/2026" else mfg,
                bb.ifBlank { "01/01/2030" }, settings.dpi, printerView = true,
            )
        }
    }

    var offsetsValid by remember(t.id) { mutableStateOf(true) }
    val n = count.toIntOrNull() ?: 0
    val badSize = t.areas.indices.firstOrNull { Ezpl.printAreaProblem(t, t.areas[it], settings.dpi) != null }
    val badFit = preview?.areas?.indexOfFirst { it != null && !it.fits }?.takeIf { it >= 0 }
    val ready = t.areas.isNotEmpty() && (!needBatch || batch.isNotBlank()) && (!needBb || bb.isNotBlank()) &&
        n in 1..9999 && !busy && offsetsValid && badSize == null && badFit == null
    val (designW, designH) = Ezpl.designSize(t)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Header(t.name, onBack = onBack, action = { TextButton(onClick = onEditArea) { Text("라벨 설정") } })
        Text("앱 버전 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        Check("거꾸로 인쇄 (180° 회전 · 즉시 저장)", t.upsideDown, enabled = !busy) { v ->
            onTemplate(t.copy(upsideDown = v))
        }
        Text(
            "출력 방향: ${if (t.upsideDown) "거꾸로" else "기본"} · 미리보기의 위쪽이 먼저 나옵니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        preview?.let { p ->
            Image(
                p.bmp.asImageBitmap(), contentDescription = "위치 보정이 반영된 인쇄 미리보기 (위쪽이 먼저 나옴)",
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit,
            )
        }
        PrintPositionControls(t, enabled = !busy, onValid = { offsetsValid = it }, onChange = onTemplate)
        if (badSize != null) Text(
            "영역 ${badSize + 1}: ${Ezpl.printAreaProblem(t, t.areas[badSize], settings.dpi)}",
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "라벨 ${mm(designW)} × ${mm(designH)} mm (프린터 폭 ${mm(t.widthMm)} × 길이 ${mm(t.lengthMm)} mm, 간격 ${mm(t.gapMm)} mm). " +
                "빨간 사각형이 인쇄 영역이고, 값을 비워 두면 예시 글자가 보입니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        preview?.areas?.forEachIndexed { i, r ->
            val a = t.areas.getOrNull(i)
            if (r != null && a != null && r.fits && a.fontPt > 0f && r.pt < a.fontPt - 0.3f) {
                Text(
                    "영역 ${i + 1}: ${fmt(a.fontPt)} pt는 영역보다 커서 ${mm(r.pt)} pt로 줄여 인쇄합니다.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Section("인쇄할 내용")
        OutlinedButton(onClick = { showBatches = true }, modifier = Modifier.fillMaxWidth()) {
            Text("배치 목록에서 선택" + if (batches.isNotEmpty()) " (${batches.size}건)" else "")
        }
        Text("목록에서 고르거나 아래 칸에 직접 입력하세요. 고른 뒤에도 고칠 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        Text("시트에서 가져올 Best Before 종류", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Chips(listOf(BB_MGO to "MGO용", BB_UMF to "UMF용"), o.bbKind) { kind ->
            setOptions(o.copy(bbKind = kind))
            picked?.let { applyRow(it, kind) }
        }
        if (sheetNote.isNotBlank()) Text(sheetNote, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = batch, onValueChange = { batch = it },
            label = { Text(if (needBatch) "배치번호 (필수)" else "배치번호 (이 라벨에서는 안 씀)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = bb, onValueChange = { bb = it },
                label = { Text(if (needBb) "Best Before (필수)" else "Best Before (이 라벨에서는 안 씀)") },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pickDate { bb = it } }) { Text("달력") }
        }
        if (needMfg) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = mfg, onValueChange = { mfg = it }, label = { Text("MFG Date (비우면 그 줄은 인쇄 안 함)") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pickDate { mfg = it } }) { Text("달력") }
            }
        }
        OutlinedButton(
            enabled = batch.isNotBlank() && bb.isNotBlank(),
            onClick = {
                onSaveBatch(
                    BatchRow(
                        batch = batch, mfg = mfg,
                        bbMgo = if (o.bbKind == BB_UMF) "" else bb, bbUmf = if (o.bbKind == BB_UMF) bb else "",
                    )
                )
                sheetNote = "'${batch.trim()}' 배치를 ${o.bbKind}용 Best Before와 함께 목록에 저장했습니다."
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) { Text("이 배치를 목록에 저장") }
        Text(
            "앱에 저장한 배치 ${savedCount}건" + (if (waitingCount > 0) " (시트에 아직 안 올라간 것 ${waitingCount}건)" else "") +
                ". 저장한 배치는 지울 수 없고, 같은 번호로 다시 저장하면 입력한 날짜만 고쳐집니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("달력 날짜 형식", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Chips(DATE_FORMATS.map { it to it.uppercase(Locale.US) }, o.dateFormat) {
            setOptions(o.copy(dateFormat = it))
            // 이미 들어 있는 날짜도 새 형식으로 바꾼다(날짜로 읽히지 않으면 그대로 둔다).
            bb = SheetData.formatDate(bb, it)
            mfg = SheetData.formatDate(mfg, it)
        }

        if (usedPhrases.isNotEmpty()) {
            Section("라벨 문구 선택")
            usedPhrases.forEach { ph ->
                val cur = pt.picks[ph.key] ?: ""
                Text(ph.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Chips(ph.options.map { it to it } + ("" to "인쇄 안 함"), cur) { v ->
                    onTemplate(t.copy(picks = t.picks + (ph.key to v)))
                }
                OutlinedTextField(
                    value = cur, onValueChange = { v -> onTemplate(t.copy(picks = t.picks + (ph.key to v))) },
                    label = { Text("${ph.title} 직접 입력 (비우면 인쇄 안 함)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = onPhrases) { Text("미리 써 둔 문구 고치기") }
        }

        Section("머리말 (값 앞에 붙는 글자)")
        if (needBatch) Check("\"Batch: \" 인쇄", o.batchPrefix) { setOptions(o.copy(batchPrefix = it)) }
        if (needBb) {
            Check("Best Before 머리말 인쇄", o.bbPrefix) { setOptions(o.copy(bbPrefix = it)) }
            if (o.bbPrefix) Chips(BB_STYLES.map { it to "$it:" }, o.bbStyle) { setOptions(o.copy(bbStyle = it)) }
        }
        if (needMfg) {
            Check("MFG 머리말 인쇄", o.mfgPrefix) { setOptions(o.copy(mfgPrefix = it)) }
            if (o.mfgPrefix) Chips(MFG_STYLES.map { it to "$it:" }, o.mfgStyle) { setOptions(o.copy(mfgStyle = it)) }
        }
        Text("끄면 값만 인쇄합니다. 예: 켜면 \"Batch: QB0000\", 끄면 \"QB0000\".", style = MaterialTheme.typography.bodySmall)

        Section("글자")
        Chips(FONT_FAMILIES, o.fontFamily) { setOptions(o.copy(fontFamily = it)) }
        Text("자간 ${Math.round(o.letterSpacing * 100)}%")
        Slider(
            value = o.letterSpacing,
            onValueChange = { setOptions(o.copy(letterSpacing = Math.round(it * 100) / 100f)) },
            valueRange = -0.1f..0.3f,
        )
        t.areas.forEachIndexed { i, a ->
            key(t.id, i) {
                NumField(
                    "영역 ${i + 1} 글자 크기 pt (0 = 영역에 꽉 차게)", fmt(a.fontPt),
                    Modifier.fillMaxWidth(), decimal = true,
                ) { s ->
                    s.toFloatOrNull()?.let { v ->
                        if (v >= 0) onTemplate(t.copy(areas = t.areas.mapIndexed { k, x -> if (k == i) x.copy(fontPt = v) else x }))
                    }
                }
            }
        }
        Text("영역의 위치·크기와 내용, 굵기는 '라벨 설정'에서 바꿉니다.", style = MaterialTheme.typography.bodySmall)

        Section("프린터")
        Text("농도 ${settings.darkness}")
        Slider(
            value = settings.darkness.toFloat(), onValueChange = { onSettings(settings.copy(darkness = it.toInt())) },
            valueRange = 0f..19f, steps = 18,
        )
        Text("속도 ${settings.speed} inch/s")
        Slider(
            value = settings.speed.toFloat(), onValueChange = { onSettings(settings.copy(speed = it.toInt())) },
            valueRange = 2f..7f, steps = 4,
        )
        OutlinedTextField(
            value = count, onValueChange = { count = it.filter(Char::isDigit).take(4) },
            label = { Text("인쇄 매수") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = ready, modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                message = "프린터로 보내는 중…"
                scope.launch {
                    var sentAt = 0L
                    var state = ""
                    message = withContext(Dispatchers.IO) {
                        try {
                            val overflow = t.areas.indices.firstOrNull { i ->
                                val lines = Ezpl.areaLines(t.areas[i], batch, mfg, bb, o, pt.picks)
                                lines.isNotEmpty() && !Imaging.renderArea(t, t.areas[i], lines, settings.dpi).fits
                            }
                            val st = PrinterClient.status(settings)
                            if (overflow != null) {
                                "인쇄 영역 ${overflow + 1}에 내용이 들어가지 않아 인쇄하지 않았습니다."
                            } else if (st.isNotBlank() && !Ezpl.isOk(st)) {
                                "프린터 상태: ${Ezpl.statusText(st)}. 인쇄하지 않았습니다."
                            } else {
                                val job = Imaging.buildJob(pt, settings, batch, mfg, bb, n)
                                val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(job).take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
                                val direction = "${if (pt.upsideDown) "거꾸로" else "기본"} (${Ezpl.printRotation(pt)}°), 보정 X ${mm(pt.printOffsetXmm)} / Y ${mm(pt.printOffsetYmm)} mm, 데이터 $fingerprint"
                                PrinterClient.send(settings.ip, settings.port, job)
                                sentAt = System.currentTimeMillis()
                                // 인쇄 중(50)·처리 중(60)에는 묻지 않고, 다 찍을 만큼 기다린 뒤 확인한다.
                                val perLabelMs = ((t.lengthMm + t.gapMm) / (settings.speed.coerceIn(2, 7) * 25.4f) * 1000).toLong()
                                Thread.sleep((perLabelMs * n + 1500).coerceAtMost(20000))
                                var after = try { PrinterClient.status(settings) } catch (e: Exception) { "" }
                                var tries = 0
                                while (Ezpl.isBusy(after) && tries < 15) {
                                    Thread.sleep(2000); tries++
                                    after = try { PrinterClient.status(settings) } catch (e: Exception) { "" }
                                }
                                state = "출력 방향: $direction. 보낸 뒤 프린터 상태: ${Ezpl.statusText(after)}"
                                if (after.isNotBlank() && !Ezpl.isOk(after)) {
                                    "${n}장을 보냈지만 프린터가 오류 상태입니다: ${Ezpl.statusText(after)}. $direction"
                                } else {
                                    "${n}장을 프린터로 보냈습니다. (프린터 상태: ${Ezpl.statusText(after)}). $direction"
                                }
                            }
                        } catch (e: Exception) {
                            "프린터에 연결하지 못했습니다 (${settings.ip}): ${e.message}"
                        }
                    }
                    // 프린터로 보낸 것은 뒤에 상태 확인이 실패했더라도 기록에 남긴다.
                    if (sentAt > 0L) onPrinted(PrintLog(sentAt, t.id, t.name, batch.trim(), mfg.trim(), bb.trim(), n, state))
                    busy = false
                }
            },
        ) { Text(if (n > 0) "${n}장 인쇄" else "인쇄") }
        if (!ready && !busy) {
            // 버튼이 눌리지 않는 이유를 정확히 알려 준다.
            val why = when {
                t.areas.isEmpty() -> "'라벨 설정'에서 인쇄 영역을 먼저 만드세요."
                badSize != null -> "인쇄 영역 ${badSize + 1}이 라벨 밖으로 나갑니다. 위치 보정이나 '라벨 설정'을 확인하세요."
                needBatch && batch.isBlank() -> "배치번호를 입력하세요."
                needBb && bb.isBlank() -> "Best Before를 입력하세요."
                !offsetsValid -> "위치 보정 칸에 mm 숫자를 입력하세요."
                n !in 1..9999 -> "인쇄 매수를 1 이상으로 입력하세요."
                badFit != null -> "인쇄 영역 ${badFit + 1}에 내용이 들어가지 않습니다. '라벨 설정'에서 영역을 키우거나 내용을 줄이세요."
                else -> ""
            }
            if (why.isNotBlank()) Text(why, color = MaterialTheme.colorScheme.error)
        }
        if (message.isNotBlank()) {
            Text(message, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showBatches) {
        BatchPicker(
            batches, o.bbKind, onRefreshBatches,
            onPick = { row ->
                picked = row
                applyRow(row, o.bbKind)
                showBatches = false
            },
            onClose = { showBatches = false },
        )
    }
}
