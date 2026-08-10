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

    private fun databasesDirectory(): String {
        val library = NSSearchPathForDirectoriesInDomains(
            NSLibraryDirectory, NSUserDomainMask, true,
        ).first() as String
        val dir = "$library/Databases"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
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
        val stored = NSUserDefaults.standardUserDefaults.stringForKey(VERSION_KEY)
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
        NSUserDefaults.standardUserDefaults.setObject(versionTag(), VERSION_KEY)
    }

    private companion object {
        const val VERSION_KEY = "almanac.content_db_version"
    }
}
