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
    data object Log : Screen
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
        if (t.imagePath.isNotBlank()) File(t.imagePath).delete()
    }

    fun updateSettings(s: PrinterSettings) {
        settings = s
        store.saveSettings(s)
    }

    var batches by remember { mutableStateOf(store.loadBatches()) }
    var logs by remember { mutableStateOf(store.loadLog()) }

    fun addLog(e: PrintLog) {
        logs = (logs + e).takeLast(5000)
        store.saveLog(logs)
    }

    suspend fun refreshBatches(): String = try {
        val rows = withContext(Dispatchers.IO) { Drive.fetchBatches(settings) }
        batches = rows
        store.saveBatches(rows)
        "시트에서 ${rows.size}건을 읽었습니다."
    } catch (e: Exception) {
        "시트를 읽지 못했습니다: ${e.message}"
    }

    // 사진은 있는데 아직 자르지 않은 라벨은 앱을 열 때 라벨만 보이게 자동으로 맞춘다.
    LaunchedEffect(Unit) {
        labels.filter { it.imagePath.isNotBlank() && it.outline == NRect.FULL }.forEach { t ->
            val found = withContext(Dispatchers.IO) { Imaging.detectLabel(t.imagePath) }
            if (found != NRect.FULL) labels.firstOrNull { it.id == t.id }?.let { upsert(it.copy(outline = found)) }
        }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val sc = screen) {
            Screen.Log -> LogScreen(
                logs, onClear = { logs = emptyList(); store.saveLog(logs) }, onBack = { screen = Screen.Home },
            )
            Screen.Home -> HomeScreen(
                store, labels, logs,
                onLog = { screen = Screen.Log },
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
                else EditScreen(t, settings, store, labels.filter { it.id != t.id && it.configured }, onSave = { upsert(it); screen = Screen.Print(it.id) }, onBack = { screen = Screen.Home })
            }
            is Screen.Print -> {
                val t = labels.firstOrNull { it.id == sc.id }
                if (t == null) screen = Screen.Home
                else PrintScreen(
                    t, settings, batches,
                    onRefreshBatches = { refreshBatches() },
                    onTemplate = { upsert(it) },
                    onPrinted = { addLog(it) },
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
    LaunchedEffect(path) {
        bmp = if (path.isBlank()) null else withContext(Dispatchers.IO) { Imaging.load(path, maxSide) }
    }
    return bmp
}

/** 라벨 사진을 저장된 범위(crop)만 잘라서 돌려준다. */
@Composable
internal fun rememberLabelPhoto(path: String, crop: NRect, maxSide: Int): Bitmap? {
    val full = rememberBitmap(path, maxSide)
    return remember(full, crop) { full?.let { Imaging.crop(it, crop) } }
}

/** 사진을 찍거나 갤러리에서 골라 앱 안에 저장한다. */
internal class PhotoPicker(val camera: () -> Unit, val gallery: () -> Unit)

/** onPhoto에는 저장된 사진 경로가 온다. 읽지 못했으면 null. */
@Composable
internal fun rememberPhotoPicker(store: Store, onPhoto: (String?) -> Unit): PhotoPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onPhoto)
    val cameraFile = remember { File(context.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() } }

    fun bring(src: File) {
        scope.launch {
            val dest = File(store.imageDir, "${UUID.randomUUID()}.jpg")
            val ok = withContext(Dispatchers.IO) { Imaging.importPhoto(src, dest) }
            done(if (ok) dest.path else null)
        }
    }

    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) bring(cameraFile)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val tmp = withContext(Dispatchers.IO) {
                File(context.cacheDir, "camera/pick.bin").apply { parentFile?.mkdirs() }.also { f ->
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }
            bring(tmp)
        }
    }
    return remember {
        PhotoPicker(
            camera = { take.launch(FileProvider.getUriForFile(context, "com.labelprint.godex.files", cameraFile)) },
            gallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
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

        Section("Google Drive 폴더 (배치 시트를 찾을 때만 사용)")
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
