package kr.seesun.feature.admin

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.pressable
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.voice.recognition.OnsetFrame
import kr.seesun.core.voice.recognition.OnsetTuning
import kr.seesun.core.voice.recognition.VadMode

/** 음성 감지 시험 화면(관리자 모드). 화면이 앞에 있을 때만 마이크를 연다. */
@Composable
fun VoiceLabRoute(onBack: () -> Unit, viewModel: VoiceLabViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMic(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LifecycleResumeEffect(granted) {
        granted = hasMic(context)
        if (granted) viewModel.start()
        onPauseOrDispose { viewModel.stop() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    VoiceLabScreen(
        state = state,
        micGranted = granted,
        onAskMic = { ask.launch(Manifest.permission.RECORD_AUDIO) },
        onBack = onBack,
        actions = LabActions(
            margin = viewModel::setMargin,
            onsetMs = viewModel::setOnsetMs,
            mode = viewModel::setMode,
            calibrationMs = viewModel::setCalibrationMs,
            defaults = viewModel::resetDefaults,
            recalibrate = viewModel::recalibrate,
            test = viewModel::runRecognitionTest,
            clear = viewModel::clearCounters,
            export = { copy(context, viewModel.exportText()) }
        )
    )
}

private fun hasMic(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("시선 음성 감지 조정값", text))
    Toast.makeText(context, "조정값을 복사했어요. 메시지에 붙여 넣어 보내 주세요.", Toast.LENGTH_LONG).show()
}

/** 화면 동작 묶음. */
class LabActions(
    val margin: (Int) -> Unit,
    val onsetMs: (Int) -> Unit,
    val mode: (VadMode) -> Unit,
    val calibrationMs: (Int) -> Unit,
    val defaults: () -> Unit,
    val recalibrate: () -> Unit,
    val test: () -> Unit,
    val clear: () -> Unit,
    val export: () -> Unit
)

@Composable
fun VoiceLabScreen(state: LabState, micGranted: Boolean, onAskMic: () -> Unit, onBack: () -> Unit, actions: LabActions) {
    ScreenScaffold(title = "음성 감지 시험", onBack = onBack, dense = true) {
        if (!micGranted) {
            BodyText("마이크 권한이 있어야 시험할 수 있어요.", emphasis = true)
            SeeSunButton(text = "마이크 허용하기", onClick = onAskMic)
            return@ScreenScaffold
        }
        // 늘 같은 자리·같은 길이로 둔다(값을 처음 바꿀 때 줄이 생겨 아래 단추가 밀리면 잘못 누른다).
        BodyText(
            if (state.tuned) "조정값 사용 중 — 실제 말하면 듣기도 이 값으로 들어요." else "기본값 사용 중 — 바꾸면 바로 저장되고 실제 듣기에도 쓰여요.",
            emphasis = state.tuned
        )
        if (state.micError) BodyText("마이크를 열지 못했어요. 다른 앱이 쓰는 중인지 확인해 주세요.", emphasis = true)
        DetectedBanner(state)
        Readouts(state.frame)
        LevelGraph(state.history, Modifier.fillMaxWidth().height(GRAPH_HEIGHT))
        // 화면 낭독기에는 상태가 바뀔 때만, 2초에 한 번까지(숫자는 각 칸을 짚으면 읽는다).
        LiveStatusText(state.announcement, emphasis = false)
        TestPanel(state, actions.test)
        Counters(state.counters, actions.clear)
        Controls(state.tuning, actions)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeeSunButton(text = "기본값으로", onClick = actions.defaults, kind = ButtonKind.OUTLINE, modifier = Modifier.weight(1f))
            SeeSunButton(text = "값 내보내기", onClick = actions.export, kind = ButtonKind.OUTLINE, modifier = Modifier.weight(1f))
        }
        BodyText(
            "값 내보내기는 지금 값과 기록을 글로 복사해요. 가장 잘 되는 값을 메시지로 보내 주시면 앱 기본값을 고칠게요."
        )
    }
}

@Composable
private fun DetectedBanner(state: LabState) {
    val colors = SeeSunTheme.colors
    val f = state.frame
    val (text, on) = when {
        state.phase == LabPhase.TEST_RECOGNIZING -> "감지됨 · 인식 중" to true
        state.detected -> "감지됨" to true
        state.phase == LabPhase.TEST_WAITING -> "말해 보세요" to false
        f == null || f.calibrating -> "소음 재는 중" to false
        else -> "기다리는 중" to false
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .background(if (on) colors.primary else colors.background, RoundedCornerShape(12.dp))
            .border(3.dp, if (on) colors.border else colors.onBackground, RoundedCornerShape(12.dp))
            // 낭독은 아래 상태 줄이 맡는다(같은 말을 두 번 읽지 않게).
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = if (on) colors.onPrimary else colors.onBackground)
    }
}

@Composable
private fun Readouts(frame: OnsetFrame?) {
    val calibrating = frame == null || frame.calibrating
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Readout("지금 크기", db(frame?.levelDb), Modifier.weight(1f))
            Readout("바닥 소음", if (calibrating) "재는 중" else db(frame.noiseFloorDb), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Readout("기준선", if (calibrating) "—" else db(frame.thresholdDb), Modifier.weight(1f), accent = THRESHOLD_COLOR)
            Readout(
                "WebRTC",
                if (frame?.vadSpeech ==
                    true
                ) {
                    "말"
                } else {
                    "말 아님"
                },
                Modifier.weight(1f),
                accent = SPEECH_COLOR.takeIf { frame?.vadSpeech == true }
            )
        }
    }
}

@Composable
private fun Readout(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val colors = SeeSunTheme.colors
    Column(
        modifier = modifier
            .border(2.dp, accent ?: colors.onBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            // 짚으면 「지금 크기 -42 데시벨」처럼 읽는다. liveRegion이 아니라 바뀔 때마다 읽지는 않는다.
            .semantics(mergeDescendants = true) { contentDescription = "$label ${value.replace("dB", "데시벨")}" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = SeeSunTheme.typography.caption, color = colors.onBackground)
        Text(
            value,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            color = accent ?: colors.emphasis,
            textAlign = TextAlign.Center
        )
    }
}

private fun db(v: Float?): String = if (v == null || v.isNaN()) "—" else "${v.roundToInt()} dB"

/**
 * 최근 10초 그래프: 노랑 = 소리 크기(100ms마다 가장 큰 값), 흰 점선 = 바닥 소음, 빨강 = 기준선(바닥 + 여유),
 * 아래 초록 띠 = WebRTC가 말이라 한 때, 흰 세로선 = 말 시작 감지. 범위 -90 ~ 0 dBFS.
 */
@Composable
private fun LevelGraph(points: List<LevelPoint>, modifier: Modifier = Modifier) {
    val colors = SeeSunTheme.colors
    Canvas(
        modifier
            .background(GRAPH_BG, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "최근 10초 소리 크기 그래프. 노랑은 소리 크기, 빨강은 기준선, 흰 점선은 바닥 소음." }
    ) {
        val w = size.width
        val h = size.height
        val step = w / (LevelHistory.POINTS - 1)
        fun y(db: Float) = h * (db.coerceIn(MIN_DB, 0f) / MIN_DB)
        val offset = LevelHistory.POINTS - points.size
        val level = Path()
        val floor = Path()
        val threshold = Path()
        var started = false
        points.forEachIndexed { i, p ->
            val x = (offset + i) * step
            if (p.speech) {
                drawRect(
                    SPEECH_COLOR,
                    topLeft = Offset(x - step / 2, h - SPEECH_BAND),
                    size = androidx.compose.ui.geometry.Size(step, SPEECH_BAND)
                )
            }
            if (p.onset) drawLine(Color.White, Offset(x, 0f), Offset(x, h), strokeWidth = 4f)
            if (!started) {
                level.moveTo(x, y(p.levelDb))
                started = true
            } else {
                level.lineTo(x, y(p.levelDb))
            }
            if (!p.noiseFloorDb.isNaN()) {
                if (floor.isEmpty) floor.moveTo(x, y(p.noiseFloorDb)) else floor.lineTo(x, y(p.noiseFloorDb))
                if (threshold.isEmpty) threshold.moveTo(x, y(p.thresholdDb)) else threshold.lineTo(x, y(p.thresholdDb))
            }
        }
        drawPath(floor, Color.White, style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
        drawPath(threshold, THRESHOLD_COLOR, style = Stroke(width = 5f))
        drawPath(level, colors.emphasis, style = Stroke(width = 4f))
    }
}

@Composable
private fun TestPanel(state: LabState, onTest: () -> Unit) {
    val busy = state.phase == LabPhase.TEST_WAITING || state.phase == LabPhase.TEST_RECOGNIZING
    SeeSunButton(
        text = if (busy) "인식 시험 중…" else "인식까지 시험",
        onClick = onTest,
        enabled = !busy && state.phase != LabPhase.STOPPED,
        supportingText = "누르고 말해 보세요. 감지 → 음성 인식 → 들은 말과 걸린 시간"
    )
    if (state.partial.isNotBlank()) BodyText("듣는 중: ${state.partial}")
    val r = state.result ?: return
    val sec = String.format(Locale.ROOT, "%.1f", r.elapsedMs / MS_PER_SECOND)
    when {
        r.noSpeech -> BodyText("15초 동안 말소리를 감지하지 못했어요.", emphasis = true)

        r.usable -> {
            BodyText("들은 말: ${r.text}", emphasis = true)
            BodyText("걸린 시간(감지 → 결과): $sec 초")
        }

        else -> {
            BodyText("헛감지: 쓸 수 있는 말이 없었어요${r.text.takeIf { it.isNotBlank() }?.let { "(\"$it\")" }.orEmpty()}.", emphasis = true)
            BodyText("걸린 시간: $sec 초${r.error?.let { " · ${it.name}" }.orEmpty()}")
        }
    }
}

@Composable
private fun Counters(c: LabCounters, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyText(
            "감지 ${c.onsets}회 · 인식 시험 ${c.recognitionTests}회 · 헛감지 ${c.falseWakes}회",
            modifier = Modifier.weight(1f)
        )
        SeeSunButton(text = "기록 지우기", onClick = onClear, kind = ButtonKind.OUTLINE, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Controls(t: OnsetTuning, actions: LabActions) {
    SectionTitle("값 바꾸기(바로 적용·저장)")
    Stepper(
        name = "여유",
        value = "${t.marginDb} dB",
        hint = "크면 작은 소리를 덜 깨워요",
        canDown = t.marginDb > OnsetTuning.MARGIN_RANGE.first,
        canUp = t.marginDb < OnsetTuning.MARGIN_RANGE.last,
        onDown = { actions.margin(t.marginDb - OnsetTuning.MARGIN_STEP_DB) },
        onUp = { actions.margin(t.marginDb + OnsetTuning.MARGIN_STEP_DB) }
    )
    Stepper(
        name = "말 시작 길이",
        value = "${t.onsetMs} ms",
        hint = "길면 짧은 소리(박수·기침)를 더 걸러요",
        canDown = t.onsetMs > OnsetTuning.ONSET_RANGE.first,
        canUp = t.onsetMs < OnsetTuning.ONSET_RANGE.last,
        onDown = { actions.onsetMs(t.onsetMs - OnsetTuning.ONSET_STEP_MS) },
        onUp = { actions.onsetMs(t.onsetMs + OnsetTuning.ONSET_STEP_MS) }
    )
    Stepper(
        name = "소음 재기",
        value = String.format(Locale.ROOT, "%.1f 초", t.calibrationMs / MS_PER_SECOND),
        hint = "바꾸면 바닥 소음을 다시 재요",
        canDown = t.calibrationMs > OnsetTuning.CALIBRATION_RANGE.first,
        canUp = t.calibrationMs < OnsetTuning.CALIBRATION_RANGE.last,
        onDown = { actions.calibrationMs(t.calibrationMs - OnsetTuning.CALIBRATION_STEP_MS) },
        onUp = { actions.calibrationMs(t.calibrationMs + OnsetTuning.CALIBRATION_STEP_MS) }
    )
    SeeSunButton(text = "소음 다시 재기", onClick = actions.recalibrate, kind = ButtonKind.OUTLINE)
    SectionTitle("WebRTC 판정")
    VadMode.entries.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { m ->
                ListButton(
                    text = TuningExport.modeLabel(m),
                    selected = t.mode == m,
                    compactState = true,
                    onClick = { actions.mode(m) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = SeeSunTheme.typography.listItem,
        color = SeeSunTheme.colors.emphasis,
        modifier = Modifier.semantics { heading() }
    )
}

/** [−] 값 [+]. 단추 이름은 「여유 줄이기」「여유 늘리기」, 값은 단추 사이 글자로 읽는다. */
@Composable
@Suppress("LongParameterList")
private fun Stepper(name: String, value: String, hint: String, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    val colors = SeeSunTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text("$name · $hint", style = SeeSunTheme.typography.caption, color = colors.onBackground)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", "$name 줄이기", canDown, onDown)
            Text(
                value,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = colors.emphasis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { contentDescription = "$name ${value.replace("dB", "데시벨").replace("ms", "밀리초")}" }
            )
            StepButton("+", "$name 늘리기", canUp, onUp)
        }
    }
}

@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = SeeSunTheme.colors
    Box(
        modifier = Modifier
            .size(STEP_SIZE)
            .background(if (enabled) colors.primary else colors.disabled, RoundedCornerShape(12.dp))
            .border(2.dp, colors.border, RoundedCornerShape(12.dp))
            .pressable(label = label, onClick = onClick, enabled = enabled)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = colors.onPrimary, modifier = Modifier.clearAndSetSemantics { })
    }
}

private val GRAPH_HEIGHT = 180.dp
private val STEP_SIZE = 72.dp
private const val MIN_DB = -90f
private const val SPEECH_BAND = 14f
private const val MS_PER_SECOND = 1_000.0
private val GRAPH_BG = Color(0xFF120D5C)
private val THRESHOLD_COLOR = Color(0xFFFF6B6E)
private val SPEECH_COLOR = Color(0xFF5BE37D)
