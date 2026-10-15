package kr.seesun.app.gallery

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ConfirmContent
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.MicButton
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.designsystem.component.NextButton
import kr.seesun.core.designsystem.component.ResultField
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.ScrollHighlightList
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.WarningAction
import kr.seesun.core.designsystem.component.WarningKind
import kr.seesun.core.designsystem.component.WarningPopup
import kr.seesun.core.feedback.Feedback
import kr.seesun.core.voice.parse.MealUtteranceParser
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.RecognitionFailureTracker
import kr.seesun.core.voice.recognition.SpeechInput

/** 개발용 컴포넌트 점검 화면: 실기기 TalkBack 수동 점검(06 문서)과 에뮬레이터 스모크 테스트에 쓴다. */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val speech: SpeechInput,
    private val parser: MealUtteranceParser,
    private val feedback: Feedback
) : ViewModel() {

    data class UiState(
        val mic: MicState = MicState.IDLE,
        val input: InputState = InputState.WAITING,
        val text: String = "",
        val summary: String = "",
        val status: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val failures = RecognitionFailureTracker()
    private var listening: Job? = null

    fun onMicClick() {
        if (listening?.isActive == true) {
            listening?.cancel()
            _state.value = _state.value.copy(mic = MicState.IDLE, input = InputState.WAITING)
            return
        }
        listening = viewModelScope.launch {
            speech.listen().collect { event ->
                val s = _state.value
                _state.value = when (event) {
                    is RecognitionEvent.Ready -> s.copy(
                        mic = MicState.LISTENING,
                        input = InputState.RECORDING,
                        status = if (event.onDevice) "" else "기기 밖 음성 인식을 쓰고 있어요."
                    )

                    is RecognitionEvent.Partial -> s.copy(text = event.text)

                    RecognitionEvent.SpeechEnded -> s.copy(mic = MicState.PROCESSING)

                    RecognitionEvent.SpeechStarted, is RecognitionEvent.Level -> s

                    is RecognitionEvent.Final -> {
                        failures.onSuccess()
                        s.copy(mic = MicState.IDLE, input = InputState.RECOGNIZED, text = event.best, summary = summarize(event.best))
                    }

                    is RecognitionEvent.Failed -> s.copy(
                        mic = MicState.IDLE,
                        input = InputState.WAITING,
                        status = if (failures.onFailure()) failures.alternativeMessage else event.error.message
                    )
                }
            }
            _state.value = _state.value.copy(mic = MicState.IDLE)
        }
    }

    fun onTextEdited(text: String) {
        _state.value = _state.value.copy(text = text, input = InputState.EDITING, summary = summarize(text))
    }

    fun onEditRequest() {
        _state.value = _state.value.copy(input = InputState.EDITING)
    }

    /** 경고 팝업이 뜰 때 진동을 내고 음성 접두어를 돌려준다. */
    fun onWarningShown(kind: WarningKind): String = feedback.signalApiCode(kind.vibrationCode).orEmpty()

    fun onSaved(): String = feedback.signal(kr.seesun.core.feedback.HapticPattern.SAVED)

    private fun summarize(text: String): String {
        val r = parser.parse(text, LocalDate.now())
        val foods = r.foods.joinToString(", ") { f ->
            val amount = f.quantity.grams?.let { "${it.toInt()}그램" } ?: "${f.quantity.ratio ?: 1.0}인분"
            "${f.name} $amount"
        }
        return listOfNotNull(
            r.date?.let { "날짜 $it" },
            r.mealType?.let { "끼니 ${it.label}" },
            foods.takeIf { it.isNotEmpty() }?.let { "음식 $it" }
        ).joinToString(". ").ifEmpty { "알아들은 항목이 없어요." }
    }
}

@Composable
fun ComponentGalleryScreen(onBack: () -> Unit, onOpenHighlightList: () -> Unit, viewModel: GalleryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onMicClick()
    }
    var warning by remember { mutableStateOf<WarningKind?>(null) }
    var saved by remember { mutableStateOf("") }
    val diseases = remember { mutableStateListOf<String>() }

    ScreenScaffold(title = "공통 컴포넌트", onBack = onBack) {
        BodyText("식사 기록 예: 어제 점심에 김치찌개 한 그릇 먹었어")
        MicButton(state = state.mic, onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.onMicClick() else permission.launch(Manifest.permission.RECORD_AUDIO)
        })
        ResultField(
            label = "식사 기록",
            value = state.text,
            state = state.input,
            onValueChange = viewModel::onTextEdited,
            onEditRequest = viewModel::onEditRequest
        )
        LiveStatusText(state.status)
        LiveStatusText(state.summary, emphasis = false)
        NextButton(onClick = { }, enabled = state.text.isNotBlank())

        BodyText("질환 선택(복수 선택, 없음은 나머지 해제)")
        listOf("당뇨", "고혈압", "없음").forEach { name ->
            ListButton(text = name, selected = name in diseases, onClick = {
                when {
                    name in diseases -> diseases.remove(name)

                    name == "없음" -> {
                        diseases.clear()
                        diseases.add(name)
                    }

                    else -> {
                        diseases.remove("없음")
                        diseases.add(name)
                    }
                }
            })
        }

        ConfirmContent(
            lines = listOf("점심", "떡볶이 한 그릇", "480킬로칼로리"),
            question = "이대로 기록할까요?",
            onConfirm = { saved = viewModel.onSaved() },
            onEdit = { saved = "수정을 눌렀어요." }
        )
        LiveStatusText(saved)

        SeeSunButton(text = "알레르기 경고 보기", kind = ButtonKind.SECONDARY, onClick = { warning = WarningKind.ALLERGY })
        SeeSunButton(text = "당류 높음 경고 보기", kind = ButtonKind.OUTLINE, onClick = { warning = WarningKind.SUGAR_HIGH })
        SeeSunButton(text = "당류 매우 높음 경고 보기", kind = ButtonKind.OUTLINE, onClick = { warning = WarningKind.SUGAR_VERY_HIGH })
        SeeSunButton(text = "스크롤 강조 목록 보기", kind = ButtonKind.OUTLINE, onClick = onOpenHighlightList)
    }

    warning?.let { kind ->
        val message = when (kind) {
            WarningKind.ALLERGY -> "떡볶이에는 밀이 포함될 가능성이 높아요."
            WarningKind.SUGAR_HIGH -> "떡볶이의 당류·탄수화물 지표가 높아요. 55점."
            WarningKind.SUGAR_VERY_HIGH -> "콜라의 당류·탄수화물 지표가 매우 높아요. 82점."
        }
        val alternative = if (kind == WarningKind.ALLERGY) "대체 식단 보기" else "추천 음식 보기"
        WarningPopup(
            kind = kind,
            message = message,
            actions = listOf(WarningAction(alternative) { warning = null }, WarningAction("확인") { warning = null }),
            onDismissRequest = { warning = null },
            onShown = { viewModel.onWarningShown(it) }
        )
    }
}

/** 지정 알레르기 19종(HLT-05) 스크롤 강조 목록 점검 화면. */
@Composable
fun HighlightListGalleryScreen(onBack: () -> Unit) {
    val allergens = remember {
        listOf(
            "알류", "우유", "메밀", "땅콩", "대두", "밀", "고등어", "게", "새우", "돼지고기",
            "복숭아", "토마토", "아황산류", "호두", "닭고기", "쇠고기", "오징어", "조개류", "잣"
        )
    }
    val selected = remember { mutableStateListOf<String>() }
    var index by remember { mutableIntStateOf(0) }
    ScreenScaffold(title = "알레르기 선택", onBack = onBack, scrollable = false) {
        ScrollHighlightList(
            items = allergens,
            itemLabel = { it },
            highlightedIndex = index,
            onHighlightedIndexChange = { index = it },
            onItemClick = { if (it in selected) selected.remove(it) else selected.add(it) },
            isSelected = { it in selected },
            modifier = Modifier.weight(1f)
        )
    }
}
