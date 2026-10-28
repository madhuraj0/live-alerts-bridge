# OxygenOS & ColorOS Live Alerts Media Bridge

An open-source, ultra battery-friendly background bridge that brings **OxygenOS & ColorOS Dynamic Island (Fluid Cloud Live Alerts)** to **all media players** without root.

---

## ⚡ Battery Friendly Architecture

* **0% Idle CPU:** Built on Android's native event-driven `NotificationListenerService` and `MediaSessionManager.OnActiveSessionsChangedListener`.
* **Zero Polling & Zero Wake Locks:** The app does not run background loops, timers, or keep the CPU awake. It only executes for a fraction of a millisecond when an active player posts a track change or play/pause event.
* **Smart Lifecycle:** Automatically terminates foreground notification and sleeps when playback stops.

---

## 🎵 Universal Media Support & Direct Navigation

* **Works with Any Player Out of the Box:** Poweramp, YouTube Music (official & ReVanced), BitChord, LastWave, VLC, browsers, podcast players, etc. No need to register package names manually.
* **Direct App Launch on Tap:** Tapping the Dynamic Island capsule or the expanded island card immediately opens the specific player playing the media (Poweramp, YouTube Music, etc.) instead of the bridge app.
* **Dynamic Player Icon Badging:** Live Alerts receives the target music player's icon dynamically.
* **App Picker Blacklist:** Built-in searchable app picker to easily exclude any app with a single switch.

---

## 🎨 Consistent System Theming & Minimal MD3

* **100% System Theme Consistent:** Full DayNight support (Light & Dark modes) plus Material You dynamic theming that adapts automatically to OxygenOS's wallpaper palette.
* **Clean, Large MD3 UI:** Expressive rounded 28dp cards, large typography, live album art, and zero unnecessary text or clutter.

---

## 📦 Downloads (v1.3.0)

Grab the latest release from the [Releases](https://github.com/madhuraj0/live-alerts-bridge/releases/tag/v1.3.0) page:

* **`LiveAlertsBridge-v1.3.0-appleMusic.apk`** (Package: `com.apple.android.music`)  
  👉 **Recommended if you do not use Apple Music.** (Compatible alongside official Spotify).
* **`LiveAlertsBridge-v1.3.0-spotify.apk`** (Package: `com.spotify.music`)  
  👉 **Recommended if you do not use Spotify.** (Compatible alongside official Apple Music).

---

## 🚀 Quick Setup

1. Install your preferred APK build.
2. Tap **Grant** for Notification Access.
3. Tap **Unrestrict** to prevent aggressive OEM background killing.
4. *(Poweramp users)*: In Poweramp Settings > Look and Feel > Notifications, ensure **Android Media Player** is selected.
5. Tap **Test Capsule** to preview the Live Alert dynamic pill!
