package com.dogdduddy.almanac

import androidx.compose.runtime.Composable

/** 데스크톱에는 진동기가 없다. 화면의 등장만 남는다. */
@Composable
actual fun rememberPlatformHaptics(): Haptics = NoHaptics
