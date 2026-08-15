package com.dogdduddy.almanac.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

// NSFileManager 의 error out-parameter(NSError**)를 null 로 넘기려면 필요하다.
@OptIn(ExperimentalForeignApi::class)
actual class DatabaseFactory {

    /**
     * 앱 번들의 content.db 를 쓰기 가능한 경로로 복사한 뒤 연다.
     *
     * 번들 리소스는 읽기 전용 위치라 SQLite 가 저널을 못 만든다.
     * 복사 여부는 **앱 빌드 버전**으로 판단한다 — 존재 여부만 보면 업데이트해도 옛 DB 가 남는다.
     */
    actual fun createContentDriver(): SqlDriver {
        val databasesDir = databasesDirectory()
        val target = "$databasesDir/$CONTENT_DB_NAME"
        if (needsRefresh(target)) copyFromBundle(target)

        return NativeSqliteDriver(
            configuration = DatabaseConfiguration(
                name = CONTENT_DB_NAME,
                version = ContentDatabase.Schema.version.toInt(),
                create = { },      // 번들 DB 를 그대로 쓴다. 스키마를 새로 만들지 않는다.
                upgrade = { _, _, _ -> },
                extendedConfig = DatabaseConfiguration.Extended(basePath = databasesDir),
            )
        )
    }

    actual fun createUserDriver(): SqlDriver =
        NativeSqliteDriver(
            schema = UserDatabase.Schema,
            name = USER_DB_NAME,
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(basePath = databasesDirectory())
                )
            },
        )

    /**
     * DB 를 둘 디렉터리.
     *
     * **App Group 컨테이너를 우선한다.** WidgetKit 익스텐션은 앱과 별도 컨테이너를
     * 쓰므로, 앱 전용 경로에 두면 위젯이 user.db 를 못 읽어 히스토리도 위치도
     * 보이지 않는다. 날씨 캐시도 두 벌이 되어 1시간 규칙이 사실상 30분이 된다.
     *
     * 엔타이틀먼트가 없으면(설정 누락 등) 앱 전용 경로로 떨어진다 — 위젯은 못 쓰지만
     * 앱은 정상 동작한다. 조용히 죽는 것보다 낫다.
     */
    private fun databasesDirectory(): String {
        val manager = NSFileManager.defaultManager
        val shared = manager
            .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP)
            ?.path

        val base = shared ?: (
            NSSearchPathForDirectoriesInDomains(
                NSLibraryDirectory, NSUserDomainMask, true,
            ).first() as String
            )

        val dir = "$base/Databases"
        manager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun versionTag(): String {
        val info = NSBundle.mainBundle.infoDictionary
        val short = info?.get("CFBundleShortVersionString") ?: "0"
        val build = info?.get("CFBundleVersion") ?: "0"
        return "$short/$build"
    }

    private fun needsRefresh(target: String): Boolean {
        if (!NSFileManager.defaultManager.fileExistsAtPath(target)) return true
        val stored = sharedDefaults().stringForKey(VERSION_KEY)
        return stored != versionTag()
    }

    private fun copyFromBundle(target: String) {
        val source = NSBundle.mainBundle.pathForResource("content", "db")
            ?: error("content.db 가 앱 번들에 없다 — Xcode 프로젝트의 리소스에 추가할 것")

        val manager = NSFileManager.defaultManager
        // -wal / -shm 이 남아 있으면 새 파일과 섞여 DB 가 깨진다.
        listOf("", "-wal", "-shm").forEach { suffix ->
            val path = target + suffix
            if (manager.fileExistsAtPath(path)) manager.removeItemAtPath(path, null)
        }
        manager.copyItemAtPath(source, target, null)
        sharedDefaults().setObject(versionTag(), VERSION_KEY)
    }

    /**
     * 버전 스탬프는 **앱과 위젯이 공유해야 한다.**
     *
     * standardUserDefaults 는 익스텐션마다 따로라, 그걸 쓰면 앱이 복사한 뒤에도
     * 위젯은 "아직 복사 안 됐다"고 판단해 매번 다시 복사한다.
     */
    private fun sharedDefaults(): NSUserDefaults =
        NSUserDefaults(suiteName = APP_GROUP) ?: NSUserDefaults.standardUserDefaults

    private companion object {
        const val VERSION_KEY = "almanac.content_db_version"

        /**
         * 앱과 위젯이 공유하는 컨테이너.
         * Xcode 의 두 타깃 엔타이틀먼트에 **똑같이** 적혀 있어야 한다.
         */
        const val APP_GROUP = "group.com.dogdduddy.almanac"
    }
}
