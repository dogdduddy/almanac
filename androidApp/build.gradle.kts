import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/**
 * Android 실행 모듈. **KMP 가 아니다** (AGP 9 제약).
 *
 * 여기 들어가는 것: Activity, Glance 위젯, Android 리소스(폰트/문자열/매니페스트).
 * 공유 로직과 UI 는 :composeApp / :shared 에서 가져온다.
 */
android {
    namespace = "com.dogdduddy.almanac"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dogdduddy.almanac"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    kotlin {
        jvmToolchain(21)
    }
}

/**
 * 릴리스 빌드에 Test Store 키가 실려나가는 것을 막는다.
 *
 * RevenueCat SDK 가 직접 경고한다 —
 *   "Our SDK will crash if using it in production.
 *    Apps submitted with a Test Store API key will be rejected during App Review."
 *
 * 개발 중에는 실키가 없어 test 키로 떨어지는 게 정상이고, 그 편의가 그대로
 * 릴리스로 새는 것이 위험하다. 사람이 기억하는 대신 빌드가 막는다.
 */
val checkReleaseBillingKey = tasks.register("checkReleaseBillingKey") {
    group = "verification"
    description = "릴리스 빌드에 실제 RevenueCat 키가 있는지 검사한다"

    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val androidKey = (rootProject.findProperty("almanac.revenuecat.android") as String?)
        ?: local.getProperty("almanac.revenuecat.android").orEmpty()
    val hasReal = androidKey.isNotBlank() && !androidKey.startsWith("test_")

    doLast {
        check(hasReal) {
            "릴리스 빌드에 실제 RevenueCat Android 키가 없다.\n" +
                "local.properties 에 almanac.revenuecat.android=goog_... 를 넣을 것.\n" +
                "Test Store 키로 출시하면 앱이 크래시하고 심사에서 반려된다."
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(checkReleaseBillingKey) }

dependencies {
    implementation(projects.composeApp)
    implementation(projects.shared)

    // Compose 아티팩트는 :composeApp(CMP) 를 통해 전이로 들어온다.
    // androidx Compose BOM 을 따로 물리면 CMP 와 버전이 이중 관리되므로 쓰지 않는다.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // Glance 위젯 — 네이티브. 공유 불가.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}
