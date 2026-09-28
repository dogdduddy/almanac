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
        versionCode = 6
        versionName = "0.4.0"
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG 로 촬영용 메뉴를 가른다. AGP 8 부터 기본이 꺼져 있어 명시한다.
        buildConfig = true
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
        debug {
            buildConfigField("boolean", "DEMO_TOOLS", "true")
        }

        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEMO_TOOLS", "false")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }

        /**
         * **촬영 전용 빌드.** release 의 최적화를 그대로 쓰되 촬영 메뉴를 넣는다.
         *
         * 이게 없으면 데모 영상의 디자인 장면을 가장 느린 빌드로 찍게 된다.
         * 역방향 넘김과 날씨별 등장 애니메이션은 Release 성능이 있어야 제 모습이 나오는데,
         * 날씨·시드를 고정하려면 촬영 메뉴가 필요하다 — debug 로만 가르면 둘을 동시에 못 얻는다.
         *
         *   ./gradlew :androidApp:assembleDemo
         *
         * **스토어에는 이 빌드를 올리지 않는다.** versionName 에 `-demo` 가 붙어
         * Play Console 업로드 화면에서 바로 보이고, 릴리스 게이트
         * (checkReleaseBillingKey, verifyContentDb)는 assembleRelease/bundleRelease 에만
         * 걸려 있으므로 이 빌드는 그것들을 타지 않는다. 결제도 필요 없다 —
         * 구매 장면은 테스트 트랙에서 설치한 스토어 빌드로 찍는다.
         */
        create("demo") {
            initWith(getByName("release"))
            buildConfigField("boolean", "DEMO_TOOLS", "true")
            versionNameSuffix = "-demo"
            // 서명이 없으면 설치가 안 된다. 릴리스 키가 없는 머신에서는 디버그 키로 떨어진다.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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

    val bootstrap = (rootProject.findProperty("almanac.billing.bootstrap") as String?)
        ?.toBoolean() ?: false

    doLast {
        // bootstrap 이면 composeApp 이 키를 아예 안 넣는다. 결제 없는 빌드가 정상 결과다.
        if (bootstrap) return@doLast

        // Test Store 키는 **어떤 경우에도** 릴리스에 실리면 안 된다.
        // SDK 가 프로덕션에서 크래시하고 심사에서 반려된다. 우회 플래그를 두지 않는다.
        check(!androidKey.startsWith("test_")) {
            "릴리스 빌드가 Test Store 키를 쓰려 한다.\n" +
                "SDK 가 프로덕션에서 크래시하고 App Review 에서 반려된다.\n" +
                "local.properties 에 almanac.revenuecat.android=goog_... 를 넣을 것."
        }

        // **형식까지 본다.** 비었는지만 보면 자리표시자(`goog_TODO`)를 통과시키는데,
        // 런타임(selectBillingKey)은 그걸 못 쓸 키로 보고 Test Store 로 떨어진다.
        // 게이트는 통과했는데 앱은 test 키로 도는, 이 게이트가 막으려던 바로 그 상태다.
        // 규칙의 정본은 shared 의 BillingKeySelection.kt 다.
        check(androidKey.startsWith("goog_") && androidKey.length - "goog_".length >= 20) {
            if (androidKey.isBlank()) {
                "릴리스 빌드에 RevenueCat 키가 없다. 결제가 동작하지 않는 빌드가 나온다.\n" +
                    "키가 아직 없어 스토어 등록용으로 올리는 것이라면:\n" +
                    "  ./gradlew :androidApp:bundleRelease -Palmanac.billing.bootstrap=true"
            } else {
                "almanac.revenuecat.android 가 RevenueCat 키 형식이 아니다.\n" +
                    "goog_ 로 시작하고 뒤가 20자 이상이어야 한다. 자리표시자가 남아 있는지 확인할 것.\n" +
                    "이대로 두면 런타임이 Test Store 키로 떨어져 프로덕션에서 크래시한다."
            }
        }

        // testKey 는 위 검사로 이미 배제됐다. 참조를 남겨 의도를 분명히 한다.
        check(androidKey != testKey) { "android 키와 test 키가 같다." }
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

/**
 * Compose 폰트가 실제로 에셋에 실렸는지 검사한다.
 *
 * 이 검사가 있는 이유: AGP 9 의 KMP 라이브러리 플러그인에서 CMP 리소스가 APK 에서
 * 통째로 빠지는데 **빌드는 초록으로 통과했다** (CMP-9547). 앱은 멀쩡히 실행되고
 * Font(Res.font.crimson_text) 만 조용히 기본 폰트로 폴백해서, iOS 는 세리프이고
 * 안드로이드는 산세리프인 채로 출시될 뻔했다. 타이포그래피가 이 앱의 전부인데
 * 그것이 컴파일 에러도 런타임 에러도 없이 사라진다.
 *
 * composeApp 의 experimentalProperties 한 줄이 그 스위치라, 누가 지우면 같은 일이
 * 다시 조용히 일어난다. 그래서 파일이 실렸는지를 직접 본다.
 *
 * 경로는 CMP 런타임 규약이다 — 생성된 Res.kt 가
 * `readResourceBytes("composeResources/<packageOfResClass>/" + path)` 로 찾는다.
 */
val composeFontAssetPath =
    "composeResources/com.dogdduddy.almanac.resources/font/crimson_text.ttf"

listOf("Debug", "Demo", "Release").forEach { variant ->
    val mergedAssets = layout.buildDirectory
        .dir("intermediates/assets/${variant.replaceFirstChar { it.lowercase() }}/merge${variant}Assets")
    val expected = composeFontAssetPath

    val verify = tasks.register("verifyComposeFont$variant") {
        group = "verification"
        description = "$variant 에셋에 Compose 폰트가 실렸는지 검사한다"

        doLast {
            val root = mergedAssets.get().asFile
            check(root.isDirectory) {
                "에셋 병합 결과를 찾을 수 없다: ${root.path}\n" +
                    "AGP 가 중간 산출물 경로를 바꿨을 수 있다. 이 검사의 경로를 갱신할 것."
            }
            check(File(root, expected).isFile) {
                "Compose 폰트가 에셋에 없다: $expected\n" +
                    "앱이 Crimson Text 대신 시스템 기본 폰트로 그려진다 — 에러 없이.\n" +
                    "composeApp/build.gradle.kts 의\n" +
                    "  experimentalProperties[\"android.experimental.kmp.enableAndroidResources\"] = true\n" +
                    "가 지워졌는지 확인할 것. 배경은 CMP-9547."
            }
        }
    }

    tasks.matching { it.name == "merge${variant}Assets" }.configureEach { finalizedBy(verify) }
}

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
