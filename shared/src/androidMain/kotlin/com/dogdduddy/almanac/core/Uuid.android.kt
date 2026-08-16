package com.dogdduddy.almanac.core

import java.util.UUID

internal actual fun randomUuid(): String = UUID.randomUUID().toString()
