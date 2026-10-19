package kr.seesun.core.network

import java.io.File
import java.lang.reflect.Method
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kr.seesun.core.network.model.AllergenDto
import kr.seesun.core.network.model.AmountDto
import kr.seesun.core.network.model.AuthTokenResponse
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.network.model.ConsentListResponse
import kr.seesun.core.network.model.ConsentStatusDto
import kr.seesun.core.network.model.ConsentUpdateRequest
import kr.seesun.core.network.model.DayScoreDto
import kr.seesun.core.network.model.DietEvaluateRequest
import kr.seesun.core.network.model.DietEvaluateResponse
import kr.seesun.core.network.model.DietScoreDto
import kr.seesun.core.network.model.DietWarningDto
import kr.seesun.core.network.model.EvaluatedItemDto
import kr.seesun.core.network.model.ExerciseSessionBulkResponse
import kr.seesun.core.network.model.ExerciseSessionBulkResultDto
import kr.seesun.core.network.model.FirebaseTokenExchangeRequest
import kr.seesun.core.network.model.FoodAllergenDto
import kr.seesun.core.network.model.FoodDetailDto
import kr.seesun.core.network.model.FoodSearchResponse
import kr.seesun.core.network.model.FoodSummaryDto
import kr.seesun.core.network.model.HealthProfileDto
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.LogoutRequest
import kr.seesun.core.network.model.MeDto
import kr.seesun.core.network.model.MePatchRequest
import kr.seesun.core.network.model.MealBulkRequest
import kr.seesun.core.network.model.MealBulkResponse
import kr.seesun.core.network.model.MealBulkResultDto
import kr.seesun.core.network.model.MealDto
import kr.seesun.core.network.model.MealItemDto
import kr.seesun.core.network.model.MealItemInputDto
import kr.seesun.core.network.model.NutritionDto
import kr.seesun.core.network.model.OnboardingStatusDto
import kr.seesun.core.network.model.PartialDeleteResponseDto
import kr.seesun.core.network.model.RecommendationCardDto
import kr.seesun.core.network.model.RecommendationResponseDto
import kr.seesun.core.network.model.ServingDto
import kr.seesun.core.network.model.SugarIndexDto
import kr.seesun.core.network.model.TargetKcalDetailDto
import kr.seesun.core.network.model.TokenRefreshRequest
import kr.seesun.core.network.model.TokenRefreshResponse
import kr.seesun.core.network.model.UserSettingsDto
import kr.seesun.core.network.model.UserSettingsPatchDto
import kr.seesun.core.network.model.WeeklyExerciseDto
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * 앱 API 정의가 기계 판독 명세(docs/api/openapi.yaml, 계약상 우선)와 어긋나지 않는지 검사한다.
 *
 * 1. Retrofit 인터페이스([AuthApi], [SeeSunApi])의 모든 메서드·경로가 명세에 있다.
 * 2. DTO의 직렬화 필드 이름이 대응 스키마 속성에 있고, 값 종류(문자열·정수·실수·참거짓·목록·객체)가 맞다.
 * 3. 요청 DTO는 스키마의 필수 속성을 모두 가진다.
 *
 * 명세가 바뀌어 이 테스트가 깨지면 DTO를 고치거나, 의도한 차이라면 아래 허용 목록에 이유와 함께 적는다.
 */
class OpenApiContractTest {

    private val spec: Map<String, Any?> by lazy { loadSpec() }

    @Suppress("UNCHECKED_CAST")
    private fun loadSpec(): Map<String, Any?> {
        val path = System.getProperty("seesun.openapi") ?: "../../../docs/api/openapi.yaml"
        val file = File(path)
        assertTrue(file.isFile, "openapi.yaml을 찾을 수 없음: ${file.absolutePath}")
        return Load(LoadSettings.builder().build()).loadFromString(file.readText()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private val schemas: Map<String, Map<String, Any?>> by lazy {
        ((spec["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>)
    }

    /** 명세의 "METHOD /경로/{}" 집합(경로 변수 이름은 무시). */
    @Suppress("UNCHECKED_CAST")
    private val specOperations: Set<String> by lazy {
        (spec["paths"] as Map<String, Map<String, Any?>>).flatMap { (path, ops) ->
            ops.keys.filter { it in HTTP_METHODS }.map { "${it.uppercase()} ${normalize(path)}" }
        }.toSet()
    }

    @Test
    fun `Retrofit의 모든 메서드와 경로가 openapi 명세에 있다`() {
        val endpoints = (AuthApi::class.java.declaredMethods + SeeSunApi::class.java.declaredMethods)
            .mapNotNull { m -> endpointOf(m)?.let { m.name to it } }
        assertTrue(endpoints.size >= MIN_ENDPOINTS, "Retrofit 메서드를 읽지 못함: $endpoints")
        val missing = endpoints.filter { (_, op) -> op !in specOperations && op !in OUTSIDE_SPEC }
        if (missing.isNotEmpty()) {
            fail<Unit>("명세에 없는 API: " + missing.joinToString { (name, op) -> "$name → $op" })
        }
    }

    @Test
    fun `토큰 없이 부르는 AuthApi는 명세상 인증 없이 부를 수 있는 API만 담는다`() {
        val needsAuth = AuthApi::class.java.declaredMethods.mapNotNull { m -> endpointOf(m)?.let { m.name to it } }
            .filter { (_, op) -> op !in OUTSIDE_SPEC && op !in anonymousOperations }
        if (needsAuth.isNotEmpty()) {
            fail<Unit>("인증이 필요한데 토큰 없이 보내는 API: " + needsAuth.joinToString { (name, op) -> "$name → $op" })
        }
    }

    /** 보안 요구가 없거나 빈 요구({})를 허용하는 연산. */
    @Suppress("UNCHECKED_CAST")
    private val anonymousOperations: Set<String> by lazy {
        (spec["paths"] as Map<String, Map<String, Any?>>).flatMap { (path, ops) ->
            ops.filterKeys { it in HTTP_METHODS }.filter { (_, op) ->
                val security = (op as Map<String, Any?>)["security"] as? List<Map<String, Any?>>
                security == null || security.any { it.isEmpty() }
            }.keys.map { "${it.uppercase()} ${normalize(path)}" }
        }.toSet()
    }

    @Test
    fun `DTO 필드 이름과 값 종류가 대응 스키마와 맞다`() {
        val problems = DTO_SCHEMAS.flatMap { (serializer, schemaNames) -> checkDto(serializer.descriptor, schemaNames) }
        if (problems.isNotEmpty()) fail<Unit>("계약과 다른 DTO 필드:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `요청 DTO에는 TalkBack·장애 정보를 보내는 필드가 없다(법적 검토 D-01)`() {
        val forbidden = Regex("talk|screen_?reader|disab|impair|blind|vision|a11y|accessib", RegexOption.IGNORE_CASE)
        val sent =
            REQUEST_DTOS.map { it.first.descriptor } + UserSettingsPatchDto.serializer().descriptor + MePatchRequest.serializer().descriptor
        val problems = sent.flatMap { d -> d.elementNames.filter { forbidden.containsMatchIn(it) }.map { "${d.serialName}.$it" } }
        if (problems.isNotEmpty()) fail<Unit>("서버로 보내면 안 되는 필드: $problems")
    }

    @Test
    fun `요청 DTO는 스키마의 필수 속성을 모두 가진다`() {
        val problems = REQUEST_DTOS.flatMap { (serializer, schemaName) ->
            val required = (schemas.getValue(schemaName)["required"] as? List<*>).orEmpty().map { it.toString() }
            val names = serializer.descriptor.elementNames.toSet()
            required.filter { it !in names }.map { "${serializer.descriptor.serialName}: 필수 '$it' 없음($schemaName)" }
        }
        if (problems.isNotEmpty()) fail<Unit>(problems.joinToString("\n"))
    }

    private fun endpointOf(m: Method): String? {
        val (verb, path) = m.getAnnotation(GET::class.java)?.let { "GET" to it.value }
            ?: m.getAnnotation(POST::class.java)?.let { "POST" to it.value }
            ?: m.getAnnotation(PUT::class.java)?.let { "PUT" to it.value }
            ?: m.getAnnotation(PATCH::class.java)?.let { "PATCH" to it.value }
            ?: m.getAnnotation(DELETE::class.java)?.let { "DELETE" to it.value }
            ?: m.getAnnotation(HTTP::class.java)?.let { it.method.uppercase() to it.path }
            ?: return null
        val full = if (path.startsWith("/")) path else API_BASE + path
        return "$verb ${normalize(full)}"
    }

    private fun normalize(path: String): String = path.substringBefore('?').replace(Regex("""\{[^}]+}"""), "{}")

    @Suppress("UNCHECKED_CAST")
    private fun checkDto(descriptor: SerialDescriptor, schemaNames: List<String>): List<String> {
        val props = schemaNames.flatMap { name ->
            ((schemas[name] ?: return listOf("${descriptor.serialName}: 스키마 $name 없음"))["properties"] as? Map<String, Map<String, Any?>>)
                .orEmpty().entries
        }.associate { it.key to it.value }
        val dto = descriptor.serialName.substringAfterLast('.')
        return (0 until descriptor.elementsCount).mapNotNull { i ->
            val field = descriptor.getElementName(i)
            val prop = props[field]
            when {
                "$dto.$field" in EXTRA_FIELDS -> null
                prop == null -> "$dto.$field: ${schemaNames.joinToString("/")}에 없는 필드"
                else -> typeProblem(dto, field, descriptor.getElementDescriptor(i), prop)
            }
        }
    }

    private fun typeProblem(dto: String, field: String, element: SerialDescriptor, prop: Map<String, Any?>): String? {
        val expected = schemaKind(prop) ?: return null
        if ("$dto.$field" in DECIMAL_STRING_FIELDS && expected == "string") return null
        val actual = when (val kind = element.kind) {
            PrimitiveKind.STRING -> "string"

            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> "integer"

            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> "number"

            PrimitiveKind.BOOLEAN -> "boolean"

            StructureKind.LIST -> "array"

            StructureKind.CLASS, StructureKind.OBJECT, StructureKind.MAP -> "object"

            is PolymorphicKind, SerialKind.CONTEXTUAL -> return null

            // JsonElement: 무엇이든 받는다
            else -> kind.toString()
        }
        if (element.serialName.startsWith("kotlinx.serialization.json.")) return null
        return if (actual == expected) null else "$dto.$field: 명세 $expected, 앱 $actual"
    }

    /** 스키마 속성의 값 종류. 열거형 참조는 문자열, 판단할 수 없으면 null(검사 생략). */
    @Suppress("UNCHECKED_CAST")
    private fun schemaKind(prop: Map<String, Any?>): String? {
        (prop["type"] as? String)?.let { return it }
        val ref = (prop["\$ref"] as? String) ?: ((prop["allOf"] as? List<Map<String, Any?>>)?.firstOrNull()?.get("\$ref") as? String)
        if (ref != null) {
            val target = schemas[ref.substringAfterLast('/')] ?: return null
            return if (target["enum"] != null) "string" else (target["type"] as? String ?: "object")
        }
        val options = (prop["oneOf"] ?: prop["anyOf"]) as? List<Map<String, Any?>> ?: return null
        return options.mapNotNull { schemaKind(it) }.firstOrNull()
    }

    private companion object {
        const val API_BASE = "/api/v1/"
        const val MIN_ENDPOINTS = 20
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")

        /** 명세(OpenAPI) 밖이지만 계약 문서에 있는 경로. */
        val OUTSIDE_SPEC = setOf(
            // phase2-api.md §12: 서버 루트 /healthz/는 미들웨어가 직접 응답해 drf-spectacular 명세에 잡히지 않는다.
            "GET /healthz/"
        )

        /** 명세에 없지만 서버가 실제로 덧붙이는 필드(낭독 문구). */
        val EXTRA_FIELDS = setOf(
            "MeDto.tts_message", // PATCH /me/ 응답
            "HealthProfileDto.tts_message" // PUT /me/health/ 응답
        )

        /** DRF DecimalField: 명세는 문자열, 앱은 숫자로 읽고 쓴다(kotlinx가 따옴표 숫자를 읽음, 서버는 숫자도 받음). */
        val DECIMAL_STRING_FIELDS = setOf(
            "AmountDto.ratio",
            "AmountDto.grams",
            "MealItemDto.amount_ratio",
            "MealItemDto.grams"
        )

        val DTO_SCHEMAS: List<Pair<KSerializer<*>, List<String>>> = listOf(
            FirebaseTokenExchangeRequest.serializer() to listOf("FirebaseExchangeRequest"),
            AuthTokenResponse.serializer() to listOf("FirebaseExchangeResponse"),
            OnboardingStatusDto.serializer() to listOf("Onboarding"),
            TokenRefreshRequest.serializer() to listOf("TokenRefreshRequest"),
            TokenRefreshResponse.serializer() to listOf("TokenRefresh"),
            LogoutRequest.serializer() to listOf("LogoutRequest"),
            MeDto.serializer() to listOf("Me"),
            TargetKcalDetailDto.serializer() to listOf("TargetKcalDetail"),
            UserSettingsDto.serializer() to listOf("AppSettings"),
            UserSettingsPatchDto.serializer() to listOf("AppSettingsRequest"),
            MePatchRequest.serializer() to listOf("PatchedMeUpdateRequest"),
            ConsentDocumentDto.serializer() to listOf("ConsentDocument"),
            ConsentStatusDto.serializer() to listOf("ConsentStatusItem"),
            ConsentListResponse.serializer() to listOf("ConsentStatus"),
            ConsentDecisionDto.serializer() to listOf("ConsentDecisionRequest"),
            ConsentUpdateRequest.serializer() to listOf("ConsentDecisionListRequest"),
            AllergenDto.serializer() to listOf("Allergen"),
            HealthProfileDto.serializer() to listOf("HealthProfile"),
            HealthProfileRequest.serializer() to listOf("HealthProfileRequest"),
            ServingDto.serializer() to listOf("Serving"),
            NutritionDto.serializer() to listOf("Nutrition", "NutritionInputRequest"),
            FoodAllergenDto.serializer() to listOf("FoodAllergen"),
            SugarIndexDto.serializer() to listOf("SugarIndex"),
            FoodSummaryDto.serializer() to listOf("FoodSummary"),
            FoodDetailDto.serializer() to listOf("FoodDetail"),
            FoodSearchResponse.serializer() to listOf("FoodSearchResult"),
            AmountDto.serializer() to listOf("AmountRequest"),
            MealItemInputDto.serializer() to listOf("MealItemInputRequest"),
            DietEvaluateRequest.serializer() to listOf("EvaluateRequestRequest"),
            EvaluatedItemDto.serializer() to listOf("EvaluatedItem"),
            DietWarningDto.serializer() to listOf("Warning"),
            DietEvaluateResponse.serializer() to listOf("EvaluateResponse"),
            MealBulkRequest.serializer() to listOf("MealBulkRequestRequest"),
            MealBulkResultDto.serializer() to listOf("BulkResult"),
            DayScoreDto.serializer() to listOf("DayScore"),
            MealItemDto.serializer() to listOf("MealItem"),
            MealDto.serializer() to listOf("Meal"),
            MealBulkResponse.serializer() to listOf("MealBulkResponse"),
            DietScoreDto.serializer() to listOf("DailyReport"),
            RecommendationResponseDto.serializer() to listOf("RecommendationResponse"),
            RecommendationCardDto.serializer() to listOf("RecommendationCard"),
            ExerciseSessionBulkResultDto.serializer() to listOf("SessionBulkResult"),
            ExerciseSessionBulkResponse.serializer() to listOf("SessionBulkResponse"),
            WeeklyExerciseDto.serializer() to listOf("WeeklyExercise"),
            PartialDeleteResponseDto.serializer() to listOf("PartialDeleteResponse")
            // MealInputDto·ExerciseSessionInputDto: 명세에서 항목이 자유 객체(additionalProperties)라 대응 스키마가 없다.
            // DeleteMeRequest: 명세가 DELETE 본문을 싣지 않는다(서버는 {"confirmation": true}를 요구).
        )

        val REQUEST_DTOS: List<Pair<KSerializer<*>, String>> = listOf(
            FirebaseTokenExchangeRequest.serializer() to "FirebaseExchangeRequest",
            TokenRefreshRequest.serializer() to "TokenRefreshRequest",
            LogoutRequest.serializer() to "LogoutRequest",
            MePatchRequest.serializer() to "PatchedMeUpdateRequest",
            ConsentDecisionDto.serializer() to "ConsentDecisionRequest",
            ConsentUpdateRequest.serializer() to "ConsentDecisionListRequest",
            HealthProfileRequest.serializer() to "HealthProfileRequest",
            DietEvaluateRequest.serializer() to "EvaluateRequestRequest",
            MealBulkRequest.serializer() to "MealBulkRequestRequest"
        )
    }
}
