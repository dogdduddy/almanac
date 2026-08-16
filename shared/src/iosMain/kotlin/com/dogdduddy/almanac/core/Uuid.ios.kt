package com.dogdduddy.almanac.core

import platform.Foundation.NSUUID

/** NSUUID 는 대문자로 준다. 정규화는 호출부(newInstallId)가 한다. */
internal actual fun randomUuid(): String = NSUUID().UUIDString
