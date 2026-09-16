package com.dogdduddy.almanac.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.dogdduddy.almanac.db.user.UserDatabase
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 데스크톱(JVM) 구현.
 *
 * @param dataDir 두 DB 를 둘 디렉터리. 플랫폼 관례 경로는 조립 지점이 정한다
 *   (macOS `~/Library/Application Support/Almanac` 등)
 * @param bundledContent 실행 파일에 실린 content.db. 클래스패스 리소스에서 연다
 */
actual class DatabaseFactory(
    private val dataDir: File,
    private val bundledContent: () -> InputStream,
) {

    /**
     * 번들 content.db 를 데이터 디렉터리로 복사한 뒤 연다.
     *
     * 모바일은 앱 버전으로 갱신 여부를 판단하지만 데스크톱 실행 파일에는 그런 버전이
     * 없다. 대신 **번들 바이트의 해시**를 스탬프로 남긴다 — 콘텐츠가 바뀌면 해시가
     * 바뀌고, 그때만 다시 복사한다. 300KB 남짓이라 매 실행 해시 계산은 무시할 수준이다.
     *
     * 스키마를 넘기지 않는다. 굽는 스크립트가 `PRAGMA user_version` 을 심어두었고,
     * 번들 DB 는 읽기 전용이라 여기서 만들거나 옮길 것이 없다.
     */
    actual fun createContentDriver(): SqlDriver {
        dataDir.mkdirs()
        val target = File(dataDir, CONTENT_DB_NAME)
        refreshContent(target)
        return JdbcSqliteDriver("jdbc:sqlite:${target.absolutePath}")
    }

    /**
     * user.db 를 연다. **절대 덮어쓰지 않는다.**
     *
     * JDBC 드라이버는 Android/iOS 드라이버와 달리 스키마 생성·마이그레이션을 스스로 하지
     * 않는다. `PRAGMA user_version` 을 읽어 직접 한다 — 규칙은 두 모바일 드라이버가
     * 내부에서 하는 것과 같다 (0 이면 생성, 낮으면 마이그레이션).
     */
    actual fun createUserDriver(): SqlDriver {
        dataDir.mkdirs()
        val target = File(dataDir, USER_DB_NAME)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${target.absolutePath}")

        val schema = UserDatabase.Schema
        val current = driver.userVersion()
        when {
            current == 0L -> schema.create(driver)
            current < schema.version -> schema.migrate(driver, current, schema.version)
        }
        if (current != schema.version) driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        return driver
    }

    private fun refreshContent(target: File) {
        val bytes = bundledContent().use { it.readBytes() }
        val digest = sha256(bytes)
        val stamp = File(dataDir, "content-db-version")
        if (target.exists() && stamp.exists() && stamp.readText().trim() == digest) return

        // -wal / -shm 이 남아 있으면 새 파일과 섞여 DB 가 깨진다.
        listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
        target.writeBytes(bytes)
        stamp.writeText(digest)
    }

    private fun SqlDriver.userVersion(): Long =
        executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}
