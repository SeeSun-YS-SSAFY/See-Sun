plugins {
    alias(libs.plugins.seesun.android.feature)
}

android {
    namespace = "kr.seesun.feature.onboarding"
}

dependencies {
    androidTestImplementation(project(":core:uitest"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:auth"))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
