// 루트 빌드: 플러그인 버전을 한곳에서 클래스패스에 올리고, 코드 스타일·정적 분석 태스크를 둔다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 내장 Kotlin이 쓸 KGP 버전을 08 문서 버전으로 고정한다(적용은 하지 않음).
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}

// ── ktlint / detekt ────────────────────────────────────────────────
// Gradle 플러그인 대신 CLI를 JavaExec로 돌린다. AGP 9 내장 Kotlin·Gradle 9와의
// 플러그인 호환 문제를 피하고, 로컬·CI에서 같은 명령으로 실행하기 위함이다.
val ktlint: Configuration = configurations.create("ktlint")
val detekt: Configuration = configurations.create("detekt")

dependencies {
    ktlint(libs.ktlint.cli) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
    detekt(libs.detekt.cli)
}

val kotlinSources = listOf("**/src/**/*.kt", "**/*.kts", "!**/build/**", "!build-logic/build/**")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "ktlint로 Kotlin 코드 스타일을 검사한다."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    args(kotlinSources)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "ktlint로 Kotlin 코드 스타일을 자동 수정한다."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootDir
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args(listOf("-F") + kotlinSources)
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "detekt로 Kotlin 정적 분석을 실행한다."
    classpath = detekt
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    workingDir = rootDir
    val reportDir = layout.buildDirectory.dir("reports/detekt").get().asFile
    doFirst { reportDir.mkdirs() }
    args(
        "--input", listOf("app", "core", "feature").joinToString(",") { file(it).path },
        "--excludes", "**/build/**",
        "--config", file("config/detekt/detekt.yml").path,
        "--build-upon-default-config",
        "--report", "html:${reportDir.resolve("detekt.html")}",
        "--report", "txt:${reportDir.resolve("detekt.txt")}"
    )
}
