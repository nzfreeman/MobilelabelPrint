@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.labelprint.godex

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        Imaging.assets = assets
        setContent { MaterialTheme { App(store) } }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Edit(val id: String) : Screen
    data class Print(val id: String) : Screen
}

@Composable
internal fun App(store: Store) {
    var labels by remember { mutableStateOf(store.loadLabels()) }
    var settings by remember { mutableStateOf(store.loadSettings()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    fun upsert(t: LabelTemplate) {
        labels = if (labels.any { it.id == t.id }) labels.map { if (it.id == t.id) t else it } else labels + t
        store.saveLabels(labels)
    }

    fun remove(t: LabelTemplate) {
        labels = labels.filter { it.id != t.id }
        store.saveLabels(labels)
        File(t.imagePath).delete()
    }

    fun updateSettings(s: PrinterSettings) {
        settings = s
        store.saveSettings(s)
    }

    var batches by remember { mutableStateOf(store.loadBatches()) }

    suspend fun refreshBatches(): String = try {
        val rows = withContext(Dispatchers.IO) { Drive.fetchBatches(settings) }
        batches = rows
        store.saveBatches(rows)
        "시트에서 ${rows.size}건을 읽었습니다."
    } catch (e: Exception) {
        "시트를 읽지 못했습니다: ${e.message}"
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val sc = screen) {
            Screen.Home -> HomeScreen(
                store, labels, settings,
                onAdd = { upsert(it) },
                onOpen = { screen = if (it.configured) Screen.Print(it.id) else Screen.Edit(it.id) },
                onEdit = { screen = Screen.Edit(it.id) },
                onDelete = { remove(it) },
                onSettings = { screen = Screen.Settings },
            )
            Screen.Settings -> SettingsScreen(settings, ::updateSettings) { screen = Screen.Home }
            is Screen.Edit -> {
                val t = labels.firstOrNull { it.id == sc.id }
                if (t == null) screen = Screen.Home
                else EditScreen(t, onSave = { upsert(it); screen = Screen.Print(it.id) }, onBack = { screen = Screen.Home })
            }
            is Screen.Print -> {
                val t = labels.firstOrNull { it.id == sc.id }
                if (t == null) screen = Screen.Home
                else PrintScreen(
                    t, settings, batches,
                    onRefreshBatches = { refreshBatches() },
                    onTemplate = { upsert(it) },
                    onSettings = ::updateSettings,
                    onEditArea = { screen = Screen.Edit(t.id) },
                    onBack = { screen = Screen.Home },
                )
            }
        }
    }
}

// ───────────────────────── 공통 부품 ─────────────────────────

@Composable
internal fun Header(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("← 뒤로") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
        action?.invoke()
    }
}

@Composable
internal fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun Check(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(text)
    }
}

@Composable
internal fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** 숫자 입력. 입력 중인 글자는 그대로 두고, 숫자로 읽히면 값을 전달한다. */
@Composable
internal fun NumField(
    label: String, value: String, modifier: Modifier = Modifier,
    decimal: Boolean = false, onValue: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
internal fun rememberBitmap(path: String, maxSide: Int): Bitmap? {
    var bmp by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) { bmp = withContext(Dispatchers.IO) { Imaging.load(path, maxSide) } }
    return bmp
}

// ───────────────────────── 라벨 목록 ─────────────────────────

@Composable
internal fun HomeScreen(
    store: Store,
    labels: List<LabelTemplate>,
    settings: PrinterSettings,
    onAdd: (LabelTemplate) -> Unit,
    onOpen: (LabelTemplate) -> Unit,
    onEdit: (LabelTemplate) -> Unit,
    onDelete: (LabelTemplate) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<LabelTemplate?>(null) }
    val currentLabels by rememberUpdatedState(labels)

    fun register(src: File, name: String, driveId: String?): Boolean {
        val id = UUID.randomUUID().toString()
        val dest = File(store.imageDir, "$id.jpg")
        if (!Imaging.importPhoto(src, dest)) return false
        val bmp = Imaging.load(dest.path, 400)
        val landscape = bmp != null && bmp.width > bmp.height
        onAdd(
            LabelTemplate(
                id = id, name = name, imagePath = dest.path, driveFileId = driveId,
                rotation = if (landscape) 90 else 0,
            )
        )
        return true
    }

    val cameraFile = remember { File(context.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() } }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch {
            val done = withContext(Dispatchers.IO) { register(cameraFile, "새 라벨 ${currentLabels.size + 1}", null) }
            message = if (done) "사진을 등록했습니다. 라벨을 눌러 인쇄 영역을 설정하세요." else "사진을 읽지 못했습니다."
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val done = withContext(Dispatchers.IO) {
                val tmp = File(context.cacheDir, "camera/pick.bin").apply { parentFile?.mkdirs() }
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                register(tmp, "새 라벨 ${currentLabels.size + 1}", null)
            }
            message = if (done) "사진을 등록했습니다. 라벨을 눌러 인쇄 영역을 설정하세요." else "사진을 읽지 못했습니다."
        }
    }

    fun syncDrive() {
        busy = true
        message = "드라이브에서 가져오는 중…"
        scope.launch {
            message = withContext(Dispatchers.IO) {
                try {
                    val files = Drive.list(settings.driveFolderId, settings.driveApiKey).filter { !it.isSheet }
                    var added = 0
                    var failed = 0
                    for (f in files) {
                        if (currentLabels.any { it.driveFileId == f.id }) continue
                        try {
                            val tmp = File(context.cacheDir, "camera/drive.bin").apply { parentFile?.mkdirs() }
                            Drive.downloadPreview(f.id, tmp)
                            if (register(tmp, f.name.substringBeforeLast('.'), f.id)) added++ else failed++
                        } catch (e: Exception) {
                            failed++
                        }
                    }
                    "드라이브 파일 ${files.size}개 중 새 라벨 ${added}개를 가져왔습니다." +
                        if (failed > 0) " (${failed}개는 이미지로 읽지 못함)" else ""
                } catch (e: Exception) {
                    "드라이브를 읽지 못했습니다: ${e.message}"
                }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("라벨 인쇄", action = { TextButton(onClick = onSettings) { Text("프린터 설정") } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val uri = FileProvider.getUriForFile(context, "com.labelprint.godex.files", cameraFile)
                takePicture.launch(uri)
            }) { Text("사진 찍어 등록") }
            OutlinedButton(onClick = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("갤러리에서") }
            OutlinedButton(onClick = { syncDrive() }, enabled = !busy) { Text("드라이브에서 가져오기") }
            OutlinedButton(onClick = {
                // 무지 라벨: 사진 없이 문구 전체를 인쇄한다. 기본 50 × 30 mm, 여백 2 mm.
                val id = UUID.randomUUID().toString()
                val dest = File(store.imageDir, "$id.jpg")
                Imaging.writeBlank(dest, 50f, 30f)
                val base = LabelTemplate(
                    id = id, name = "무지 라벨 ${labels.count { it.blank } + 1}", imagePath = dest.path,
                    blank = true, widthMm = 50f, lengthMm = 30f, rotation = 0,
                    marginLeft = 2f, marginTop = 2f, marginRight = 2f, marginBottom = 2f,
                )
                onAdd(Ezpl.withMmBox(base, Ezpl.MmBox(9999f, 9999f, 0f, 0f)))
                message = "무지 라벨을 추가했습니다. 눌러서 라벨 크기와 문구를 설정하세요."
            }) { Text("무지 라벨 추가") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (message.isNotBlank()) Text(message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        if (labels.isEmpty()) {
            Text("등록된 라벨이 없습니다. 사진을 찍거나 드라이브에서 가져오세요.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(labels, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(t) }) {
                    Column(Modifier.padding(12.dp)) {
                        val bmp = rememberBitmap(t.imagePath, 600)
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), contentDescription = t.name,
                                modifier = Modifier.fillMaxWidth().height(110.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        Text(t.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Text(
                            if (t.configured) "${fmt(t.widthMm)} × ${fmt(t.lengthMm)} mm · 눌러서 인쇄"
                            else "인쇄 영역 설정 필요 · 눌러서 설정",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (t.configured) Color.Unspecified else MaterialTheme.colorScheme.error,
                        )
                        Row {
                            TextButton(onClick = { onEdit(t) }) { Text("영역 설정") }
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
            text = { Text("'${t.name}' 라벨과 설정을 이 폰에서 삭제합니다. 드라이브의 원본은 그대로입니다.") },
            confirmButton = { TextButton(onClick = { onDelete(t); confirmDelete = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
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
    onRefreshBatches: suspend () -> String,
    onTemplate: (LabelTemplate) -> Unit,
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
    val photo = rememberBitmap(t.imagePath, 1600)

    fun setOptions(n: PrintOptions) = onTemplate(t.copy(options = n))

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
            val picked = Calendar.getInstance().apply { set(y, m, d) }
            onPicked(SimpleDateFormat(o.dateFormat, Locale.US).format(picked.time))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    val lines = Ezpl.linesFor(t, batch.ifBlank { "QB0000" }, mfg.ifBlank { "01/01/2026" }, bb.ifBlank { "01/01/2030" })
    var preview by remember { mutableStateOf<Pair<Bitmap, Imaging.Rendered>?>(null) }
    LaunchedEffect(photo, lines, t.area, t.outline, t.rotation, o, settings.dpi) {
        val p = photo ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) { Imaging.preview(p, t, lines, settings.dpi) }
    }

    val n = count.toIntOrNull() ?: 0
    val fits = preview?.second?.fits ?: true
    val ready = batch.isNotBlank() && bb.isNotBlank() && n in 1..9999 && !busy && fits

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Header(t.name, onBack = onBack, action = { TextButton(onClick = onEditArea) { Text("영역 설정") } })
        preview?.let { (image, rendered) ->
            Image(
                image.asImageBitmap(), contentDescription = "인쇄 미리보기",
                modifier = Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Fit,
            )
            val box = Ezpl.mmBox(t)
            Text(
                "인쇄 영역 ${mm(box.w)} × ${mm(box.h)} mm · 글자 크기 ${mm(rendered.pt)} pt",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!rendered.fits) {
                Text(
                    "가장 작은 글씨로도 영역에 들어가지 않아 인쇄할 수 없습니다. 영역을 키우거나 머리말·줄 배치를 바꾸세요.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (o.fontPt > 0f && rendered.pt < o.fontPt - 0.3f) {
                Text(
                    "지정한 ${fmt(o.fontPt)} pt는 라벨 여백 안에 다 들어가지 않아 ${mm(rendered.pt)} pt로 줄여 인쇄합니다.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            } else if (rendered.grown) {
                Text(
                    "지정한 ${fmt(o.fontPt)} pt에 맞춰 인쇄 영역을 넓혀서 인쇄합니다. 미리보기에서 디자인과 겹치지 않는지 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text("미리보기입니다. 값을 비워 두면 예시 글자가 보입니다.", style = MaterialTheme.typography.bodySmall)

        Section("인쇄할 내용")
        OutlinedButton(onClick = { showBatches = true }, modifier = Modifier.fillMaxWidth()) {
            Text("시트 목록에서 선택" + if (batches.isNotEmpty()) " (${batches.size}건)" else "")
        }
        Text("목록에서 고르거나 아래 칸에 직접 입력하세요. 고른 뒤에도 고칠 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        Text("시트에서 가져올 Best Before 종류", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Chips(listOf(BB_MGO to "MGO용", BB_UMF to "UMF용"), o.bbKind) { kind ->
            setOptions(o.copy(bbKind = kind))
            picked?.let { applyRow(it, kind) }
        }
        if (sheetNote.isNotBlank()) Text(sheetNote, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = batch, onValueChange = { batch = it }, label = { Text("배치번호 (필수)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = bb, onValueChange = { bb = it }, label = { Text("Best Before (필수)") },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pickDate { bb = it } }) { Text("달력") }
        }
        if (!t.blank) Check("MFG Date 인쇄", o.printMfg) { setOptions(o.copy(printMfg = it)) }
        if (o.printMfg || (t.blank && t.textTemplate.contains("{mfg}", ignoreCase = true))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = mfg, onValueChange = { mfg = it }, label = { Text("MFG Date") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pickDate { mfg = it } }) { Text("달력") }
            }
        }
        Text("달력 날짜 형식", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Chips(DATE_FORMATS.map { it to it.uppercase(Locale.US) }, o.dateFormat) {
            setOptions(o.copy(dateFormat = it))
            // 이미 들어 있는 날짜도 새 형식으로 바꾼다(날짜로 읽히지 않으면 그대로 둔다).
            bb = SheetData.formatDate(bb, it)
            mfg = SheetData.formatDate(mfg, it)
        }

        if (t.blank) {
            Section("라벨 문구")
            OutlinedTextField(
                value = t.textTemplate, onValueChange = { onTemplate(t.copy(textTemplate = it)) },
                label = { Text("인쇄할 문구 (줄마다 한 줄)") }, minLines = 5, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "{BATCH}, {MFG}, {BB} 자리에 위에서 입력한 값이 들어갑니다. MFG Date를 비우면 {MFG}가 있는 줄은 인쇄하지 않습니다. " +
                    "콜론 뒤 값을 세로로 맞추려면 글꼴을 '고정폭'으로 하고 띄어쓰기로 맞추세요.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!t.blank) {
        Section("머리말")
        Check("\"Batch: \" 같이 인쇄", o.batchPrefix) { setOptions(o.copy(batchPrefix = it)) }
        Check("Best Before 머리말 같이 인쇄", o.bbPrefix) { setOptions(o.copy(bbPrefix = it)) }
        if (o.bbPrefix) {
            Chips(BB_STYLES.map { it to "$it:" }, o.bbStyle) { setOptions(o.copy(bbStyle = it)) }
        }
        Check("\"MFG Date: \" 같이 인쇄", o.mfgPrefix, enabled = o.printMfg) { setOptions(o.copy(mfgPrefix = it)) }

        Section("배치")
        Chips(listOf(false to "한 줄 (좌→우)", true to "여러 줄 (위→아래)"), o.vertical) { setOptions(o.copy(vertical = it)) }
        Chips(
            listOf(ORDER_BATCH_FIRST to "Batch → MFG → BB", ORDER_BATCH_LAST to "MFG → BB → Batch"),
            o.order,
        ) { setOptions(o.copy(order = it)) }
        }
        Section("글자")
        Chips(FONT_FAMILIES, o.fontFamily) { setOptions(o.copy(fontFamily = it)) }
        Check("굵은 글씨 (Bold)", o.bold) { setOptions(o.copy(bold = it)) }
        Check("가운데 정렬", o.centered) { setOptions(o.copy(centered = it)) }
        Text("자간 ${Math.round(o.letterSpacing * 100)}%")
        Slider(
            value = o.letterSpacing,
            onValueChange = { setOptions(o.copy(letterSpacing = Math.round(it * 100) / 100f)) },
            valueRange = -0.1f..0.3f,
        )
        NumField("글자 크기 pt (0 = 영역에 맞춤)", fmt(o.fontPt), Modifier.fillMaxWidth(), decimal = true) { s ->
            s.toFloatOrNull()?.let { if (it >= 0) setOptions(o.copy(fontPt = it)) }
        }

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
                val real = Ezpl.linesFor(t, batch, mfg, bb)
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        try {
                            val ft = Imaging.fitArea(t, real, settings.dpi)
                            val (aw, ah) = Imaging.areaDots(ft, settings.dpi)
                            val st = PrinterClient.status(settings)
                            if (!Imaging.renderText(aw, ah, real, o, settings.dpi).fits) {
                                "내용이 인쇄 영역에 들어가지 않아 인쇄하지 않았습니다."
                            } else if (st.isNotBlank() && !Ezpl.isOk(st)) {
                                "프린터 상태: ${Ezpl.statusText(st)}. 인쇄하지 않았습니다."
                            } else {
                                PrinterClient.send(settings.ip, settings.port, Imaging.buildJob(t, settings, real, n))
                                // 인쇄 중(50)·처리 중(60)에는 묻지 않고, 다 찍을 만큼 기다린 뒤 확인한다.
                                val perLabelMs = ((t.lengthMm + t.gapMm) / (settings.speed.coerceIn(2, 7) * 25.4f) * 1000).toLong()
                                Thread.sleep((perLabelMs * n + 1500).coerceAtMost(20000))
                                var after = try { PrinterClient.status(settings) } catch (e: Exception) { "" }
                                var tries = 0
                                while (Ezpl.isBusy(after) && tries < 15) {
                                    Thread.sleep(2000); tries++
                                    after = try { PrinterClient.status(settings) } catch (e: Exception) { "" }
                                }
                                if (after.isNotBlank() && !Ezpl.isOk(after)) {
                                    "${n}장을 보냈지만 프린터가 오류 상태입니다: ${Ezpl.statusText(after)}"
                                } else {
                                    "${n}장을 프린터로 보냈습니다. (프린터 상태: ${Ezpl.statusText(after)})"
                                }
                            }
                        } catch (e: Exception) {
                            "프린터에 연결하지 못했습니다 (${settings.ip}): ${e.message}"
                        }
                    }
                    busy = false
                }
            },
        ) { Text(if (n > 0) "${n}장 인쇄" else "인쇄") }
        if (!ready && !busy) {
            // 버튼이 눌리지 않는 이유를 정확히 알려 준다.
            val why = when {
                batch.isBlank() -> "배치번호를 입력하세요."
                bb.isBlank() -> "Best Before를 입력하세요."
                n !in 1..9999 -> "인쇄 매수를 1 이상으로 입력하세요."
                !fits -> "내용이 인쇄 영역에 들어가지 않습니다. '영역 설정'에서 영역을 키우거나 글자 설정을 바꾸세요."
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

/** 시트에서 읽은 배치 목록에서 한 줄을 고른다. */
@Composable
internal fun BatchPicker(
    batches: List<BatchRow>,
    kind: String,
    onRefresh: suspend () -> String,
    onPick: (BatchRow) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    fun refresh() {
        busy = true
        note = "시트를 읽는 중…"
        scope.launch {
            note = onRefresh()
            busy = false
        }
    }

    // 처음 열었는데 목록이 비어 있으면 바로 읽어 온다.
    LaunchedEffect(Unit) { if (batches.isEmpty()) refresh() }

    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) batches else batches.filter {
        q in it.batch.lowercase() || q in it.bbMgo.lowercase() || q in it.bbUmf.lowercase() || q in it.mfg.lowercase()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("배치 선택 (${kind}용 Best Before)") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("검색 (배치번호·날짜)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (shown.isEmpty() && !busy) {
                    Text(
                        if (batches.isEmpty()) "읽어 온 목록이 없습니다." else "검색 결과가 없습니다.",
                        Modifier.padding(top = 12.dp),
                    )
                }
                LazyColumn(Modifier.height(340.dp).padding(top = 8.dp)) {
                    items(shown) { row ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(row) }.padding(vertical = 10.dp)) {
                            Text(row.batch, fontWeight = FontWeight.Bold)
                            Text(
                                "${kind}용 Best Before: ${row.bb(kind).ifBlank { "없음" }}",
                                color = if (row.bb(kind).isBlank()) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                            Text(
                                "MGO ${row.bbMgo.ifBlank { "-" }} · UMF ${row.bbUmf.ifBlank { "-" }} · MFG ${row.mfg.ifBlank { "-" }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { refresh() }, enabled = !busy) { Text("새로고침") } },
        dismissButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

// ───────────────────────── 프린터 설정 ─────────────────────────

@Composable
internal fun SettingsScreen(s: PrinterSettings, onChange: (PrinterSettings) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    val current by rememberUpdatedState(s)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Header("프린터 설정", onBack = onBack)

        Section("연결 (GoDEX EZ2350i)")
        OutlinedTextField(
            value = s.ip, onValueChange = { onChange(s.copy(ip = it.trim())) },
            label = { Text("프린터 IP 주소") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        NumField("포트", s.port.toString(), Modifier.fillMaxWidth()) { v ->
            v.toIntOrNull()?.let { if (it in 1..65535) onChange(current.copy(port = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = {
                message = "확인 중…"
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        try {
                            "프린터 상태: " + Ezpl.statusText(PrinterClient.status(current))
                        } catch (e: Exception) {
                            "연결 실패: ${e.message}. 폰이 프린터와 같은 Wi-Fi에 있는지 확인하세요."
                        }
                    }
                }
            }) { Text("연결·상태 확인") }
            OutlinedButton(onClick = {
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        try {
                            PrinterClient.cancel(current); "오류 해제 명령을 보냈습니다."
                        } catch (e: Exception) {
                            "연결 실패: ${e.message}"
                        }
                    }
                }
            }) { Text("오류 해제") }
        }
        if (message.isNotBlank()) Text(message, Modifier.padding(top = 8.dp))

        Section("인쇄 품질")
        Text("농도 ${s.darkness} (0~19)")
        Slider(
            value = s.darkness.toFloat(), onValueChange = { onChange(s.copy(darkness = it.toInt())) },
            valueRange = 0f..19f, steps = 18,
        )
        Text("속도 ${s.speed} inch/s (2~7)")
        Slider(
            value = s.speed.toFloat(), onValueChange = { onChange(s.copy(speed = it.toInt())) },
            valueRange = 2f..7f, steps = 4,
        )
        Text("인쇄 방식")
        Chips(listOf(0 to "프린터 설정 그대로", 1 to "열전사 (리본 사용)", 2 to "감열 (리본 없음)"), s.printMode) {
            onChange(s.copy(printMode = it))
        }
        Text("해상도", Modifier.padding(top = 8.dp))
        Chips(listOf(203 to "203 dpi", 300 to "300 dpi (EZ2350i)"), s.dpi) { onChange(s.copy(dpi = it)) }

        Section("위치 보정")
        NumField("왼쪽 여백 (dot, 0~399)", s.leftMargin.toString(), Modifier.fillMaxWidth()) { v ->
            v.toIntOrNull()?.let { if (it in 0..399) onChange(current.copy(leftMargin = it)) }
        }
        NumField(
            "정지 위치 (비우면 프린터 값 유지)",
            if (s.stopPosition < 0) "" else s.stopPosition.toString(), Modifier.fillMaxWidth(),
        ) { v ->
            if (v.isBlank()) onChange(current.copy(stopPosition = -1))
            else v.toIntOrNull()?.let { if (it in 0..99) onChange(current.copy(stopPosition = it)) }
        }
        Check("흑백 반전 (바탕이 검게 찍힐 때만 켜기)", s.invertBitmap) { onChange(s.copy(invertBitmap = it)) }

        Section("Google Drive 라벨 폴더")
        OutlinedTextField(
            value = s.driveFolderId, onValueChange = { onChange(s.copy(driveFolderId = it.trim())) },
            label = { Text("폴더 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "'링크가 있는 모든 사용자'에게 공개된 폴더는 로그인 없이 읽습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = s.driveApiKey, onValueChange = { onChange(s.copy(driveApiKey = it.trim())) },
            label = { Text("Drive API 키 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Section("배치 목록 Google 시트")
        OutlinedTextField(
            value = s.sheetUrl, onValueChange = { onChange(s.copy(sheetUrl = it.trim())) },
            label = { Text("시트 주소 (비우면 라벨 폴더 안의 시트)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "시트도 '링크가 있는 모든 사용자'에게 공개되어 있어야 합니다. 첫 줄에 Batch, Best Before, MFG Date 머리글을 두면 " +
                "열 순서와 상관없이 읽고, 머리글이 없으면 1열=배치, 2열=Best Before, 3열=MFG Date로 읽습니다. " +
                "특정 탭을 쓰려면 그 탭을 연 상태의 주소를 붙여 넣으세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
    }
}
