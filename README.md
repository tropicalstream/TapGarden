# TapGarden

**TapGarden is a web-app wrapper for the [RayNeo X3 Pro](https://www.rayneo.com/) AR glasses that brings [Radio Garden](https://radio.garden/) into a native, full-screen, dual-eye experience.**

It is a thin native shell around the Radio Garden web app — all of the radio content, the world map, and the audio streams are served by Radio Garden itself. TapGarden's job is to make that experience work well on AR glasses that have no touchscreen, no keyboard, and a stereoscopic (two-eye) display.

---

## What is Radio Garden?

[Radio Garden](https://radio.garden/) lets you **explore live radio by rotating a globe.** Every green dot on the map is a city, and tapping one tunes you into the stations broadcasting from that place — so you can drift from a station in Tokyo to one in Reykjavík to one in Buenos Aires just by spinning the Earth.

Radio Garden is a non-profit project originally developed (2013–2016) by the Netherlands Institute for Sound and Vision together with the Transnational Radio Knowledge Platform and several European universities. As of 2024 it spans **40,000+ live stations** worldwide, alongside its History, Stories, and Jingles sections.

Official site: **https://radio.garden/**

> TapGarden is an independent, unofficial wrapper. It is not affiliated with or endorsed by Radio Garden. All radio content and streams belong to Radio Garden and the individual broadcasters. Please support the original project at https://radio.garden/.

---

## How it's adapted for the X3 Pro

The RayNeo X3 Pro renders a stereoscopic image (one view per eye) and is driven by a touchpad rather than a touchscreen or keyboard. A normal mobile web view doesn't work there. TapGarden adapts Radio Garden for the hardware:

- **Dual-eye (binocular) projection.** The Radio Garden viewport is composited side-by-side and drawn once per eye through a custom SBS layout running on the RayNeo Mercury SDK, so the globe appears correctly in the glasses' stereoscopic display.
- **True full-screen.** The web view runs edge-to-edge with the system bars hidden and the screen kept on, so the map fills the field of view with no chrome.
- **Full app rendering.** WebGL globe, player, station balloon, zoom and panels all work (JS + DOM storage + hardware layer + desktop user-agent).
- **Location-aware globe.** The app supplies the glasses' best-known location to the web app so the globe opens centered on where you are, and "near me" browsing works.
- **On-screen keyboard for search.** Because the glasses have no physical keyboard, TapGarden provides a custom on-screen keyboard so you can type station or city names into Radio Garden's search.
- **Touchpad-friendly navigation.** Input is mapped so the glasses' touchpad can pan and spin the globe and select stations.
- **Pop-up containment.** Stray new-window pop-ups are kept from hijacking the view, so playback stays put.

The result is Radio Garden as a comfortable, immersive, full-globe experience you can fly around hands-free in AR.

---

## Building

Requirements: Android Studio / Android SDK, JDK 17. Toolchain: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, compileSdk 35, minSdk 29.

1. Clone the repo.
2. Create a `local.properties` with your SDK path, e.g. `sdk.dir=/path/to/Android/sdk`.
3. The RayNeo SDKs are included under `app/libs/` (`MercuryAndroidSDK`, `RayNeoIPCSDK`) — required to build.
4. Build a debug APK:

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

A prebuilt debug APK is attached to the GitHub release for quick sideloading.

---

## Credits

- **Radio Garden** — the radio globe, map, and all station streams: https://radio.garden/
- **RayNeo** — X3 Pro hardware and the Mercury / IPC AR SDKs.

TapGarden is a personal, non-commercial project built to enjoy Radio Garden on AR glasses.
