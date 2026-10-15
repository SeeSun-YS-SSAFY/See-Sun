package kr.seesun.feature.mypage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.URLEncoder
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.EntryGuidance

// ── 설정 「문의하기」(2차 의뢰서 p7: [전화 걸기] [이메일 보내기], 기기 기본 앱 연결) ──────────

/**
 * 문의 연락처. 값은 코드에 두지 않고 리소스 설정(`res/values/contact_config.xml`, app 빌드가 Gradle 속성
 * `seesun.contact.phone`·`seesun.contact.email`로 덮어씀)에서 읽는다. 비어 있으면 「연락처 준비 중」으로 안내한다.
 */
data class ContactConfig(val phone: String, val email: String) {
    val hasPhone: Boolean get() = ContactUris.dial(phone) != null
    val hasEmail: Boolean get() = ContactUris.mailto(email, "") != null
}

/** 전화·메일 앱을 여는 주소(시험용 순수 함수). 전화는 ACTION_DIAL(권한 없이 번호만 채움), 메일은 ACTION_SENDTO. */
object ContactUris {
    private val EMAIL = Regex("""^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$""")
    private const val MIN_DIGITS = 3

    /** "02-123-4567" → "tel:021234567". 숫자가 너무 적거나 비었으면 null. */
    fun dial(phone: String): String? {
        val digits = phone.filter { it.isDigit() || it == '+' }
        return if (digits.count(Char::isDigit) < MIN_DIGITS) null else "tel:$digits"
    }

    /** 받는 주소 + 제목. 주소 형식이 아니면 null. 제목은 퍼센트 인코딩(공백 = %20). */
    fun mailto(address: String, subject: String): String? {
        val a = address.trim()
        if (!EMAIL.matches(a)) return null
        if (subject.isEmpty()) return "mailto:$a"
        return "mailto:$a?subject=" + URLEncoder.encode(subject, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

@Composable
fun rememberContactConfig(): ContactConfig {
    val phone = stringResource(R.string.seesun_contact_phone)
    val email = stringResource(R.string.seesun_contact_email)
    return remember(phone, email) { ContactConfig(phone, email) }
}

/** 기기 앱을 연다. 열 앱이 없으면 false(화면이 안내). */
private fun openExternal(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

@Composable
fun ContactRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val config = rememberContactConfig()
    val subject = stringResource(R.string.my_contact_mail_subject)
    var notice by remember { mutableStateOf("") }
    val noApp = stringResource(R.string.my_contact_no_app)
    ContactScreen(
        config = config,
        notice = notice,
        onBack = onBack,
        onCall = {
            ContactUris.dial(config.phone)?.let { uri ->
                if (!openExternal(context, Intent(Intent.ACTION_DIAL, uri.toUri()))) notice = noApp
            }
        },
        onEmail = {
            ContactUris.mailto(config.email, subject)?.let { uri ->
                if (!openExternal(context, Intent(Intent.ACTION_SENDTO, uri.toUri()))) notice = noApp
            }
        }
    )
}

/**
 * [전화 걸기](전화 앱에 번호를 채워 열기 — 바로 걸지 않음) · [이메일 보내기](메일 앱에 받는 주소·제목을 채워 열기).
 * 연락처가 정해지지 않았으면 버튼 대신 「준비 중」 문구를 보이고 읽는다.
 */
@Composable
fun ContactScreen(config: ContactConfig, notice: String, onBack: () -> Unit, onCall: () -> Unit, onEmail: () -> Unit) {
    val title = stringResource(R.string.my_set_contact)
    val lines = listOfNotNull(
        if (config.hasPhone) stringResource(R.string.my_contact_phone_line, config.phone) else null,
        if (config.hasEmail) stringResource(R.string.my_contact_email_line, config.email) else null
    )
    ScreenScaffold(title = title, onBack = onBack) {
        BodyText(stringResource(R.string.my_contact_body))
        if (lines.isEmpty()) {
            BodyText(stringResource(R.string.my_contact_pending), emphasis = true)
        } else {
            lines.forEach { BodyText(it) }
        }
        LiveStatusText(notice)
        BottomActions {
            if (config.hasPhone) {
                SeeSunButton(text = stringResource(R.string.my_contact_call), onClick = onCall)
            }
            if (config.hasEmail) {
                SeeSunButton(text = stringResource(R.string.my_contact_email), kind = ButtonKind.OUTLINE, onClick = onEmail)
            }
        }
    }
    val guide = if (lines.isEmpty()) {
        stringResource(R.string.my_contact_pending)
    } else {
        lines.joinToString(" ") + " " + stringResource(R.string.my_contact_guide)
    }
    EntryGuidance("contact:${config.phone}:${config.email}", "$title. $guide")
}

// ── 설정 「물리 버튼」(2차 의뢰서 p2 A9·p7: 음성 인식 시작 켜기/끄기) ──────────

/** 「물리 버튼으로 듣기」 켜기/끄기. 기기에만 저장(로그인 전에도). */
@Composable
fun HardwareButtonRoute(onBack: () -> Unit, viewModel: VoiceSettingsViewModel = hiltViewModel()) {
    val on by viewModel.prefs.hardwareButton.collectAsStateWithLifecycle()
    val talkBack = rememberTalkBackEnabled()
    var changed by remember { mutableStateOf<Boolean?>(null) }
    HardwareButtonScreen(
        on = on,
        changed = changed,
        onBack = onBack,
        onPick = {
            viewModel.prefs.setHardwareButton(it)
            changed = it
        },
        speak = { viewModel.voice.auto(it, talkBack) },
        talkBack = talkBack
    )
}

@Composable
fun HardwareButtonScreen(
    on: Boolean,
    changed: Boolean?,
    onBack: () -> Unit,
    onPick: (Boolean) -> Unit,
    speak: (String) -> Unit = {},
    talkBack: Boolean = false
) {
    val title = stringResource(R.string.my_set_hw_button)
    val onLabel = stringResource(R.string.my_hw_on)
    val offLabel = stringResource(R.string.my_hw_off)
    ScreenScaffold(title = title, onBack = onBack) {
        BodyText(stringResource(R.string.my_hw_body), emphasis = true)
        ListButton(
            text = onLabel,
            description = stringResource(R.string.my_hw_on_desc),
            selected = on,
            descriptionVisible = true,
            onClick = { onPick(true) }
        )
        ListButton(
            text = offLabel,
            description = stringResource(R.string.my_hw_off_desc),
            selected = !on,
            descriptionVisible = true,
            onClick = { onPick(false) }
        )
        BodyText(stringResource(R.string.my_hw_note))
        val notice = changed?.let { stringResource(R.string.my_hw_changed, if (it) onLabel else offLabel) }.orEmpty()
        LiveStatusText(notice)
        if (notice.isNotEmpty()) AutoGuidance("hw-button:$changed", notice, talkBack, speak)
    }
    EntryGuidance("hw-button", stringResource(R.string.my_choice_guide, title, if (on) onLabel else offLabel, "$onLabel, $offLabel"))
}
