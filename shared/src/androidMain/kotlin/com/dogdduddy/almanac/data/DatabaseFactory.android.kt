package com.dogdduddy.almanac.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import java.io.File

actual class DatabaseFactory(private val context: Context) {

    /**
     * 에셋의 content.db 를 데이터베이스 경로로 복사한 뒤 연다.
     *
     * 복사본이 최신인지는 **앱 버전 코드**로 판단한다. 파일 존재 여부만 보면
     * 앱을 업데이트해도 옛 문장 DB 가 계속 남는다.
     *
     * 굽는 스크립트가 `PRAGMA user_version` 을 SQLDelight 스키마 버전에 맞춰 심어두므로,
     * 드라이버는 이 DB 를 이미 최신으로 보고 create/migrate 를 건너뛴다.
     */
    actual fun createContentDriver(): SqlDriver {
        val target = context.getDatabasePath(CONTENT_DB_NAME)
        if (needsRefresh(target)) copyFromAssets(target)
        return AndroidSqliteDriver(ContentDatabase.Schema, context, CONTENT_DB_NAME)
    }

    actual fun createUserDriver(): SqlDriver =
        AndroidSqliteDriver(UserDatabase.Schema, context, USER_DB_NAME)

    private fun stampFile(): File = File(context.filesDir, "content-db-version")

    private fun needsRefresh(target: File): Boolean {
        if (!target.exists()) return true
        val stamp = stampFile()
        return !stamp.exists() || stamp.readText().trim() != currentVersionTag()
    }

    private fun currentVersionTag(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        return "$code/${info.versionName}"
    }

    private fun copyFromAssets(target: File) {
        target.parentFile?.mkdirs()
        // -wal / -shm 이 남아 있으면 새 파일과 섞여 DB 가 깨진다.
        listOf("", "-wal", "-shm").forEach { File(target.path + it).delete() }

        context.assets.open(CONTENT_DB_NAME).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        stampFile().writeText(currentVersionTag())
    }
}
