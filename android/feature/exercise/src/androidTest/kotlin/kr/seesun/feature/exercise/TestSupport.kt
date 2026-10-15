package kr.seesun.feature.exercise

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.io.FileOutputStream
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.seesun.core.data.di.DataConfig
import kr.seesun.core.data.di.ExerciseSyncModule
import kr.seesun.core.data.model.GuideSection
import kr.seesun.core.data.sync.ExerciseSyncScheduler
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.NetworkConfig
import kr.seesun.core.uitest.assertFitsOneScreen
import kr.seesun.core.uitest.useSystemFont
import kr.seesun.feature.exercise.player.ExercisePlayerModule
import kr.seesun.feature.exercise.player.GuidePlayer
import kr.seesun.feature.exercise.player.GuidePlayerFactory
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.session.SessionKeeper
import kr.seesun.feature.exercise.session.SessionKeeperModule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** 기기 테스트를 Hilt 테스트 앱으로 돌린다. */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}

@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()

/** 앱 모듈이 주던 설정: 서버는 닿지 않는 주소(→ 기기 내장 운동 정보 사용). */
@Module
@InstallIn(SingletonComponent::class)
object TestConfigModule {
    @Provides
    @Singleton
    fun networkConfig() = NetworkConfig(baseUrl = "http://127.0.0.1:9/api/v1/")

    @Provides
    @Singleton
    fun dataConfig() = DataConfig(useFakeRepositories = false)
}

/** 소리를 내지 않는 재생기: [play]하면 재생 중, [finishNow]로 안내 끝을 흉내 낸다. */
class FakeGuidePlayer : GuidePlayer {
    private val _state = MutableStateFlow(GuideState())
    override val state: StateFlow<GuideState> = _state

    override fun load(sections: List<GuideSection>, startSection: Int) {
        _state.value = GuideState(sections = sections, sectionIndex = startSection)
    }

    override fun play() = _state.update { it.copy(status = GuideStatus.PLAYING) }

    override fun pause() = _state.update { it.copy(status = GuideStatus.PAUSED) }

    override fun replaySection() = play()

    override fun nextSection() = _state.update {
        if (it.sectionIndex >=
            it.sections.lastIndex
        ) {
            it.copy(status = GuideStatus.FINISHED)
        } else {
            it.copy(sectionIndex = it.sectionIndex + 1)
        }
    }

    override fun previousSection() = _state.update { it.copy(sectionIndex = (it.sectionIndex - 1).coerceAtLeast(0)) }

    val seeks = mutableListOf<Pair<Int, Int>>()

    override fun seekTo(section: Int, sentence: Int) {
        seeks += section to sentence
        _state.update { it.copy(sectionIndex = section, sentenceIndex = sentence, status = GuideStatus.PLAYING) }
    }

    override fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }

    override fun stop() {
        _state.value = GuideState(speed = _state.value.speed)
    }

    override fun release() = Unit

    fun finishNow() = _state.update { it.copy(status = GuideStatus.FINISHED) }

    companion object {
        val created = mutableListOf<FakeGuidePlayer>()
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ExercisePlayerModule::class])
object FakePlayerModule {
    @Provides
    fun factory(): GuidePlayerFactory = GuidePlayerFactory { FakeGuidePlayer().also { FakeGuidePlayer.created += it } }
}

/** 전송 예약을 세기만 한다(WorkManager 없이). */
object RecordingScheduler : ExerciseSyncScheduler {
    var count = 0

    override fun scheduleExerciseSync() {
        count++
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ExerciseSyncModule::class])
object FakeSyncModule {
    @Provides
    fun scheduler(): ExerciseSyncScheduler = RecordingScheduler
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SessionKeeperModule::class])
object FakeKeeperModule {
    @Provides
    fun keeper(): SessionKeeper = object : SessionKeeper {
        override fun start(title: String) = Unit

        override fun stop() = Unit
    }
}

/** 시선 테마 + 글꼴 배율로 그린다. */
fun ComposeContentTestRule.setThemed(fontScale: Float = 1f, content: @Composable () -> Unit) {
    setContent {
        if (useSystemFont(fontScale)) {
            SeeSunTheme { content() }
        } else {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                SeeSunTheme { content() }
            }
        }
    }
}

/** 모든 글자가 잘리지 않고 화면 안에 있는지(200% 글꼴, 04 §4). */
fun SemanticsNodeInteractionsProvider.assertNoTextOverflow() {
    val rootWidth = onRoot().fetchSemanticsNode().size.width
    onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true
    ).fetchSemanticsNodes().forEach { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
        results.forEach { layout ->
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
            assertFalse(
                "글자 잘림: $text",
                layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines || widest > layout.size.width + 1f
            )
        }
        assertTrue("화면 밖: $text", node.boundsInRoot.right <= rootWidth + 1f)
    }
}

/** 아이콘 이름(리거처)이나 영어 식별자를 읽는 노드가 없는지(1차 A1·A2). */
fun SemanticsNodeInteractionsProvider.assertNoLigatureNames() {
    val banned = Regex("play_arrow|pause|replay|skip_next|skip_previous|volume_up|star_border|ic_|arrow_back", RegexOption.IGNORE_CASE)
    onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
        useUnmergedTree = true
    ).fetchSemanticsNodes().forEach { n ->
        n.config[SemanticsProperties.ContentDescription].forEach { assertFalse("아이콘 이름 낭독: $it", banned.containsMatchIn(it)) }
    }
}

/**
 * 단계별 화면 사진(소유자 확인용). 기기의 앱 외부 파일 폴더 e2e-shots/ 에 PNG로 남긴다 —
 * `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`로 돌린 뒤 adb pull 로 가져간다.
 */
fun ComposeTestRule.shot(name: String, dialog: Boolean = false, allowScroll: Boolean = false) {
    waitForIdle()
    // 소유자 피드백 2026-10-13: 기본 글꼴에서 찍는 단계 사진은 모두 "스크롤 없이 한 화면" 판정을 통과해야 한다.
    if (!dialog) assertFitsOneScreen(name, allowScroll)
    val node = if (dialog) onNode(isDialog()) else onAllNodes(isRoot())[0]
    val bitmap = node.captureToImage().asAndroidBitmap()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(context.getExternalFilesDir(null), "e2e-shots").apply { mkdirs() }
    FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
