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
/** local.properties. 서명 정보와 키를 여기서만 읽는다 — 저장소에 들어가지 않는다. */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dogdduddy.almanac"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dogdduddy.almanac"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
    }

    /**
     * 릴리스 서명.
     *
     * 키스토어 경로와 비밀번호는 **local.properties 에서 읽는다** — 저장소에 넣지 않는다.
     * 설정이 없으면 signingConfig 를 붙이지 않으므로 서명 없는 번들이 나오고,
     * Play Console 업로드 단계에서 거부된다. 그때 아래 안내대로 키스토어를 만들면 된다.
     *
     * 키스토어 생성(한 번만, 직접 실행):
     *   keytool -genkeypair -v -keystore ~/almanac-upload.jks \
     *     -alias almanac -keyalg RSA -keysize 2048 -validity 10000
     *
     * **키스토어 파일과 비밀번호는 잃어버리면 복구가 안 된다.** 백업할 것.
     * (Play 앱 서명을 쓰면 업로드 키는 재발급 가능하지만, 그것도 절차가 필요하다)
     */
    val keystoreFile = localProps.getProperty("almanac.keystore.file")
    if (!keystoreFile.isNullOrBlank() && file(keystoreFile).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = localProps.getProperty("almanac.keystore.password")
                keyAlias = localProps.getProperty("almanac.keystore.alias")
                keyPassword = localProps.getProperty("almanac.keystore.keyPassword")
                    ?: localProps.getProperty("almanac.keystore.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    val testKey = (rootProject.findProperty("almanac.revenuecat.test") as String?)
        ?: local.getProperty("almanac.revenuecat.test").orEmpty()

    // 실제로 앱에 실릴 키. shared 의 선택 규칙(실키 → test → 없음)과 같아야 한다.
    val bootstrap = (rootProject.findProperty("almanac.billing.bootstrap") as String?)
        ?.toBoolean() ?: false

    // bootstrap 이면 composeApp 이 키를 아예 안 넣으므로 실릴 키도 없다.
    val effectiveKey = if (bootstrap) "" else androidKey.ifBlank { testKey }

    doLast {
        // Test Store 키는 **어떤 경우에도** 릴리스에 실리면 안 된다.
        // SDK 가 프로덕션에서 크래시하고 심사에서 반려된다. 우회 플래그를 두지 않는다.
        check(!effectiveKey.startsWith("test_")) {
            "릴리스 빌드가 Test Store 키를 쓰려 한다.\n" +
                "SDK 가 프로덕션에서 크래시하고 App Review 에서 반려된다.\n" +
                "local.properties 에 almanac.revenuecat.android=goog_... 를 넣을 것."
        }

        // 키가 아예 없으면 앱은 PreviewBilling 으로 떨어진다 — 크래시하지는 않지만
        // 결제가 동작하지 않는다. 스토어에 앱을 처음 올려 패키지명을 등록하는 단계에서는
        // 이게 정상 경로이므로, 의도를 명시하면 통과시킨다.
        check(effectiveKey.isNotBlank() || bootstrap) {
            "릴리스 빌드에 RevenueCat 키가 없다. 결제가 동작하지 않는 빌드가 나온다.\n" +
                "키가 아직 없어 스토어 등록용으로 올리는 것이라면:\n" +
                "  ./gradlew :androidApp:bundleRelease -Palmanac.billing.bootstrap=true"
        }
    }
}

/**
 * 에셋에 content.db 를 넣는다.
 *
 * 손으로 복사해 두면 깨끗한 체크아웃에서 앱은 빌드되지만 첫 실행에서 DB 를 못 찾는다.
 * 빌드가 직접 채우게 해서 그 경로를 없앤다.
 */
tasks.named("preBuild").configure { dependsOn(rootProject.tasks.named("syncContentToAndroid")) }

// 릴리스는 콘텐츠 없이 나가면 안 된다 — 첫 실행에서 문장을 못 찾는 앱이 스토어로 간다.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(checkReleaseBillingKey, rootProject.tasks.named("verifyContentDb")) }

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
