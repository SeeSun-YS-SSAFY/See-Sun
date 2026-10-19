plugins {
    alias(libs.plugins.seesun.android.library)
}

android {
    namespace = "kr.seesun.core.assistant"
}

// 시선 비서(설계 voice_assistant_design_v1): 규칙 기반 의도 해석·처리 순서·경로표·대화 상태. 화면(Compose)은 core:ui·app이 붙인다.
// LLM·서버 호출은 없다(의뢰서 2차 p17 「음성 명령은 키워드 매칭」). 슬롯 해석기(숫자·음식·네/아니요)는 core:voice의 것을 다시 쓴다.
dependencies {
    implementation(project(":core:voice"))
}

// 말뭉치 시험(AssistantCorpusTest)이 지표를 표준 출력으로 찍는다.
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}
