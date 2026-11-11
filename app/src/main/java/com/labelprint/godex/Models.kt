package com.labelprint.godex

/** 0..1 범위의 정규화된 사각형. */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t

    fun normalized(): NRect {
        val nl = minOf(l, r).coerceIn(0f, 1f)
        val nr = maxOf(l, r).coerceIn(0f, 1f)
        val nt = minOf(t, b).coerceIn(0f, 1f)
        val nb = maxOf(t, b).coerceIn(0f, 1f)
        return NRect(nl, nt, nr, nb)
    }

    companion object {
        val FULL = NRect(0f, 0f, 1f, 1f)
    }
}

/** Best Before 머리말 표기. */
val BB_STYLES = listOf("Best Before", "B.B", "BB")

/** MFG Date 머리말 표기. */
val MFG_STYLES = listOf("MFG Date", "MFG")

/** 줄 순서. */
const val ORDER_BATCH_FIRST = 0 // Batch, MFG, BB
const val ORDER_BATCH_LAST = 1  // MFG, BB, Batch

data class PrintOptions(
    val batchPrefix: Boolean = true,
    val bbPrefix: Boolean = true,
    val bbStyle: String = "Best Before",
    val printMfg: Boolean = false,
    val mfgPrefix: Boolean = true,
    val mfgStyle: String = "MFG Date",
    val vertical: Boolean = true,
    val order: Int = ORDER_BATCH_FIRST,
    val bold: Boolean = true,
    val centered: Boolean = false,
    /** 0이면 영역에 맞춰 자동. */
    val fontPt: Float = 0f,
    val dateFormat: String = "dd/MM/yyyy",
    /** sans, condensed, barlow, serif, mono */
    val fontFamily: String = "sans",
    /** 자간(em 단위). 0이 기본, 음수면 좁게. */
    val letterSpacing: Float = 0f,
    /** 시트에서 가져올 Best Before 종류: MGO 또는 UMF. */
    val bbKind: String = BB_MGO,
)

val FONT_FAMILIES = listOf(
    "sans" to "기본 고딕",
    "condensed" to "좁은 고딕",
    "barlow" to "Barlow Semi Condensed",
    "serif" to "명조(Serif)",
    "mono" to "고정폭",
)

val DATE_FORMATS = listOf("dd-MM-yyyy", "dd/MM/yyyy", "dd.MM.yyyy", "yyyy-MM-dd")

val DEFAULT_TEXT_TEMPLATE = """
NZQueenBee Multiflora Honey
BATCH : {BATCH}
MFG: {MFG}
BB: {BB}
RMP: NZQBEE1/01
Store Below 20 degrees
Product of New Zealand
""".trim()

/** 라벨 하나에 둘 수 있는 인쇄 영역 수. */
const val MAX_AREAS = 4

val DEFAULT_AREA_TEXT = "{BATCH}\n{BB}"

/**
 * 인쇄 영역 하나. 위치와 크기는 라벨을 바로 놓고 본 방향에서 왼쪽 위 모서리 기준 mm.
 * text의 {BATCH}, {MFG}, {BB} 자리에 입력값이 들어간다.
 */
data class PrintArea(
    val xMm: Float = 5f,
    val yMm: Float = 5f,
    val wMm: Float = 30f,
    val hMm: Float = 10f,
    val text: String = DEFAULT_AREA_TEXT,
    /** 0이면 영역에 맞춰 자동. */
    val fontPt: Float = 0f,
    val bold: Boolean = true,
    val centered: Boolean = false,
)

data class LabelTemplate(
    val id: String,
    val name: String,
    val imagePath: String,
    val driveFileId: String? = null,
    /** 프린터 헤드 방향 폭(mm). */
    val widthMm: Float = 47f,
    /** 용지 진행 방향 길이(mm). */
    val lengthMm: Float = 143f,
    val gapMm: Float = 3f,
    /** 사진을 시계 방향으로 이만큼 돌리면 프린터에서 나오는 방향(먼저 나오는 쪽이 위)이 된다. */
    val rotation: Int = 0,
    /** 사진 안에서 라벨이 차지하는 범위. */
    val outline: NRect = NRect.FULL,
    /** 사진 안에서 인쇄할 영역. */
    val area: NRect = NRect(0.6f, 0.6f, 0.95f, 0.95f),
    val configured: Boolean = false,
    /** 라벨 가장자리에서 인쇄를 피할 여백(mm). 디자인을 바로 놓고 본 방향 기준. */
    val marginLeft: Float = 0f,
    val marginTop: Float = 0f,
    val marginRight: Float = 0f,
    val marginBottom: Float = 0f,
    /** 인쇄 영역 위치를 오른쪽/아래쪽 가장자리에서 잴지 여부. */
    val anchorRight: Boolean = true,
    val anchorBottom: Boolean = true,
    /** 무지 라벨: 사진 없이 문구 전체를 인쇄한다. */
    val blank: Boolean = false,
    /** 무지 라벨 문구. {BATCH}, {MFG}, {BB} 자리에 입력값이 들어간다. */
    val textTemplate: String = DEFAULT_TEXT_TEMPLATE,
    val options: PrintOptions = PrintOptions(),
    /** 직접 입력한 인쇄 영역들. 인쇄는 이 값만 쓴다(사진·outline·area는 예전 저장값을 옮길 때만 읽는다). */
    val areas: List<PrintArea> = emptyList(),
)

data class PrinterSettings(
    val ip: String = "192.168.68.55",
    val port: Int = 9100,
    val dpi: Int = 300,
    /** ^H 0~19 */
    val darkness: Int = 19,
    /** ^S 2~7 inch/sec */
    val speed: Int = 2,
    /** 0: 프린터 설정 그대로 / 1: 열전사(리본, ^AT) / 2: 감열(^AD) */
    val printMode: Int = 0,
    /** ^R 왼쪽 여백(dot) */
    val leftMargin: Int = 0,
    /** ^E 정지 위치. 음수면 보내지 않음(프린터 설정 유지). */
    val stopPosition: Int = -1,
    val invertBitmap: Boolean = false,
    val driveFolderId: String = "16E_GTyJwjxE41ZNT6naiXq0V116jb3pp",
    val driveApiKey: String = "",
    /** 배치 목록 구글 시트 주소. 비우면 라벨 폴더 안의 시트를 찾는다. */
    val sheetUrl: String = "https://docs.google.com/spreadsheets/d/1YwYZh777PkWvqwosdyUIPG9Q2doRH5zzhkTaurj-hmo/edit?gid=0#gid=0",
)

/** 인쇄 기록 한 건. time은 보낸 시각(ms). */
data class PrintLog(
    val time: Long,
    val labelId: String,
    val labelName: String,
    val batch: String,
    val mfg: String,
    val bb: String,
    val count: Int,
    /** 보낸 뒤 프린터 상태 등. */
    val note: String = "",
)
