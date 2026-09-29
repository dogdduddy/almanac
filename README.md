# Almanac — Weathered Words

Today's weather picks a passage from literature, written by someone who watched the same sky, sometimes more than a century ago.

**[App Store](https://apps.apple.com/app/almanac-weathered-words/id6801982375)** ·
**[Google Play](https://play.google.com/store/apps/details?id=com.dogdduddy.almanac)** ·
**[Demo video](https://www.youtube.com/watch?v=r41gIrDUOJo)** ·
**[Devpost](https://devpost.com/software/almanac-o9ycvr)**

![Almanac: the reading page on a rainy day](https://d112y698adiu2z.cloudfront.net/photos/production/software_photos/005/435/023/datas/original.png)

> **104 years ago** — someone watched this rain.
>
> *The Enchanted April, 1922 · Elizabeth von Arnim*

## What it does

- Reads the weather where you are from MET Norway and sorts it into one of **8 skies** (clear, cloudy, fog, drizzle, rain, snow, thunder, wind) and **3 times of day**: 24 buckets.
- Shows one of **428 passages** from 32 public-domain books by 27 authors, written between 1847 and 1925, with the book, the author, the year and how many years ago it was written.
- The words arrive like the weather. Rain falls, snow drifts, fog comes into focus, wind blows the words in, thunder flashes the page. On phones, haptics play on the same timeline.
- Home-screen widgets on Android (Glance) and iOS (WidgetKit) show the same page as the app.
- Swipe back through the pages you have read.
- A free starter shelf (86 passages) covers all 24 buckets. One non-consumable purchase through RevenueCat opens the other 342. No account, no ads, and your location is rounded to about 1.1 km before it is stored or sent.

## One Kotlin core, five surfaces

The five surfaces are the Android app, iOS app, desktop app, Android widget, and iOS widget.

![Android, desktop and iPhone landing on the same passage](https://d112y698adiu2z.cloudfront.net/photos/production/software_photos/005/435/042/datas/original.png)

| Module | Role |
|---|---|
| [`shared`](shared) | Kotlin Multiplatform core for Android, iOS and the JVM: the MET Norway client, the sky and time-of-day rules, the page selector, the SQLDelight databases (`content.db`, `user.db`), entitlement sync and the startup sequence. Also built as the `AlmanacKit` framework for the iOS widget. |
| [`composeApp`](composeApp) | The Compose Multiplatform UI used by the Android, iOS and desktop apps, including the weather animations and haptics, plus RevenueCat billing (`mobileMain`). Built as the `ComposeApp` framework for iOS. |
| [`androidApp`](androidApp) | The Android app and its Jetpack Glance widget. |
| [`iosApp`](iosApp) | A thin SwiftUI shell and the WidgetKit widget, generated with XcodeGen. |
| [`desktopApp`](desktopApp) | Compose Desktop for macOS, Windows and Linux. No store, no purchases. |
| [`content`](content) | `content.db`, the bundled passages. Each app gets a copy at build time. |
| [`scripts`](scripts) | Content baking, and the tools that recorded and edited the demo video. |

About 70% of the app's source lives in `commonMain`. The whole Swift side, app shell and widget included, is under 200 lines.

## How a page is chosen

The app and the widget run in separate processes, so page selection uses no randomness at display time. A deterministic seed comes from four inputs. The selected passage also depends on the available content and local reading history:

```
seed = FNV-1a 64 (date | time of day | weather | installId)
page = pool[seed mod |pool|]
```

The pool starts with the bucket's passages from owned packs, excludes recently shown passages while keeping at least one candidate, and is narrowed to the ones read the fewest times. The same inputs and local state produce the same selection on every platform. The hash is pinned with golden vectors, and the shared logic tests run on the JVM, in Android host tests, and on the iOS simulator.

Code: [`PageSelector.kt`](shared/src/commonMain/kotlin/com/dogdduddy/almanac/core/PageSelector.kt), [`WeatherGroup.kt`](shared/src/commonMain/kotlin/com/dogdduddy/almanac/core/WeatherGroup.kt), [`AppLoader.kt`](shared/src/commonMain/kotlin/com/dogdduddy/almanac/AppLoader.kt).

## Motion and haptics

Each passage is laid out word by word, and one clock per page drives the headline, every word, the byline and the lightning flash. The scatter comes from a hash of each word's position, so all platforms animate identically. Haptics (Core Haptics on iOS, one `VibrationEffect` waveform on Android) are scheduled from the same timeline and fire only when something lands or flashes.

Code: [`WeatherEntrance.kt`](composeApp/src/commonMain/kotlin/com/dogdduddy/almanac/WeatherEntrance.kt), [`Haptics.kt`](composeApp/src/commonMain/kotlin/com/dogdduddy/almanac/Haptics.kt).

## Purchases with RevenueCat

A one-time purchase fits a curated collection of literature: readers unlock the collection once and can return to it without a recurring subscription.

- One non-consumable product, `com.dogdduddy.almanac.core2026`, grants the entitlement `core-2026`. That is also the content pack ID, so an active entitlement *is* an owned pack.
- RevenueCat is the source of truth and the local database is only a cache. Owned passages stay readable offline, and a refunded pack is revoked on the next sync.
- Entitlements are re-read when the app returns from the store, for example after redeeming an offer code. "Restore a previous purchase" forces a sync.
- RevenueCat lives in `composeApp`, not `shared`, so the widget extension never links a purchases SDK.

Code: [`RevenueCatBilling.kt`](composeApp/src/mobileMain/kotlin/com/dogdduddy/almanac/billing/RevenueCatBilling.kt), [`EntitlementSync.kt`](shared/src/commonMain/kotlin/com/dogdduddy/almanac/billing/EntitlementSync.kt).

## Build and run

Requirements:

- JDK 21. Gradle finds an installed JDK 21 through [`gradle/gradle-daemon-jvm.properties`](gradle/gradle-daemon-jvm.properties).
- Android SDK with compile SDK 36.
- For iOS: an Apple Silicon Mac with Xcode 26 and [XcodeGen](https://github.com/yonaskolb/XcodeGen). The simulator target is ARM64; Intel Mac simulators are not supported.

RevenueCat keys are optional. Without them the app runs with purchases turned off; release builds refuse to build without real keys. To enable purchases, add your public SDK keys to `local.properties`:

```properties
almanac.revenuecat.android=goog_...
almanac.revenuecat.ios=appl_...
```

Then:

```bash
./gradlew :androidApp:installDebug   # Android device or emulator
./gradlew :desktopApp:run            # desktop
./gradlew :shared:allTests           # JVM, Android host, and iOS simulator tests
```

Running the full test suite, including the iOS simulator tests, requires an Apple Silicon Mac with Xcode and an installed iOS simulator runtime. Android host tests run on the JVM and do not require an Android device or emulator.

For iOS, generate the Xcode project and run the **Almanac** scheme on a simulator. For a real device, set your own development team.

```bash
cd iosApp && xcodegen generate && open Almanac.xcodeproj
```

## Design notes

The reasoning behind the main decisions is written up in [`docs/`](docs), in Korean:

- [Determinism contract](docs/decisions/determinism-contract.md): why iOS and Android pick the same page
- [Weather entrance](docs/decisions/weather-entrance.md) and [weather haptics](docs/decisions/weather-haptics.md)
- [RevenueCat integration](docs/decisions/revenuecat-integration.md)
- [Desktop target](docs/decisions/desktop-target.md)
- [Custom fonts in a Glance widget](docs/spikes/glance-custom-font.md)

## Credits

- Passages: public-domain books from [Project Gutenberg](https://www.gutenberg.org/).
- Weather data from [MET Norway](https://api.met.no/) (api.met.no), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- Typeface: [Crimson Text](https://fonts.google.com/specimen/Crimson+Text), SIL Open Font License 1.1 ([license](docs/licenses/CrimsonText-OFL.txt)).
- [Privacy policy](https://dogdduddy.github.io/almanac-privacy/)
- Built for [RevenueCat Shipaton 2026](https://revenuecat-shipaton-2026.devpost.com/).

## License

No open-source license has been chosen yet, so all rights to the code are reserved. The passages are in the public domain, and the typeface and weather data are used under the licenses above.
