plugins {
    alias(libs.plugins.seesun.android.library)
    alias(libs.plugins.seesun.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "kr.seesun.core.network"
}

dependencies {
    api(project(":core:auth"))
    api(libs.retrofit.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(platform(libs.okhttp.bom))
    api(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.snakeyaml.engine)
}

// 계약 검사 테스트(OpenApiContractTest)가 읽을 기계 판독 명세(저장소 루트 docs/api/openapi.yaml).
tasks.withType<Test>().configureEach {
    val spec = rootProject.layout.projectDirectory.file("../docs/api/openapi.yaml").asFile
    inputs.file(spec).withPropertyName("openapiSpec")
    systemProperty("seesun.openapi", spec.absolutePath)
}
