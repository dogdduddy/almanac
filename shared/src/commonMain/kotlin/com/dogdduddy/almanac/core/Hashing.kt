package com.dogdduddy.almanac.core

/**
 * FNV-1a 64비트 해시.
 *
 * `String.hashCode()` 를 쓰지 않는 이유: JVM과 Kotlin/Native가 현재 같은 값을 내더라도
 * 그건 명세로 보장된 계약이 아니다. 이 앱은 **iOS와 Android가 같은 조건에서 같은 문장을
 * 내야 한다**는 것이 심사 기준과 직결되므로, 해시를 직접 소유한다.
 *
 * 입력은 항상 UTF-8로 인코딩된다 (`encodeToByteArray`).
 */
internal const val FNV64_OFFSET_BASIS: ULong = 14695981039346656037uL
internal const val FNV64_PRIME: ULong = 1099511628211uL

fun fnv1a64(input: String): ULong {
    var hash = FNV64_OFFSET_BASIS
    for (byte in input.encodeToByteArray()) {
        hash = hash xor byte.toUByte().toULong()
        hash *= FNV64_PRIME
    }
    return hash
}
