package com.dogdduddy.almanac.data

import app.cash.sqldelight.db.SqlDriver
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase

/**
 * 두 DB 를 여는 플랫폼별 진입점.
 *
 * - content.db 는 **번들 리소스**다. 앱 에셋/번들에서 꺼내 쓰기 가능한 경로로 복사한 뒤 연다.
 *   SQLite 는 에셋 스트림을 직접 열 수 없다.
 * - 복사는 앱 버전이 바뀔 때마다 다시 한다. 출시 후 매주 문장을 추가할 계획이므로
 *   업데이트마다 새 content.db 가 반영되어야 한다.
 * - user.db 는 평범한 내부 저장소 DB다. **절대 덮어쓰지 않는다** — 유저 아카이브가 들어 있다.
 */
expect class DatabaseFactory {
    fun createContentDriver(): SqlDriver
    fun createUserDriver(): SqlDriver
}

/** 앱/위젯이 쓰는 조립 지점. */
fun DatabaseFactory.openRepository(nowEpochSeconds: () -> Long): AlmanacRepository =
    AlmanacRepository(
        content = ContentDatabase(createContentDriver()),
        user = UserDatabase(createUserDriver()),
        nowEpochSeconds = nowEpochSeconds,
    )

internal const val CONTENT_DB_NAME = "content.db"
internal const val USER_DB_NAME = "user.db"
