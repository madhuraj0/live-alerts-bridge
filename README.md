# OxygenOS & ColorOS Live Alerts Media Bridge

An open-source, ultra battery-friendly background bridge that brings **OxygenOS & ColorOS Dynamic Island (Fluid Cloud Live Alerts)** to **all media players** without root.

---

## ⚡ Battery Friendly Architecture

* **0% Idle CPU:** Built on Android's native event-driven `NotificationListenerService` and `MediaSessionManager.OnActiveSessionsChangedListener`.
* **Zero Polling & Zero Wake Locks:** The app does not run background loops, timers, or keep the CPU awake. It only executes for a fraction of a millisecond when an active player posts a track change or play/pause event.
* **Smart Lifecycle:** Automatically terminates foreground notification and sleeps when playback stops.

---

## 🎵 Universal Media Support & Blacklisting

* **Works with Any Player Out of the Box:** Poweramp, YouTube Music (official & ReVanced), BitChord, LastWave, VLC, browsers, podcast players, etc. No need to register package names manually.
* **App Blacklist:** Want to exclude a specific app (e.g. video player or browser)? Simply add its package name to the blacklist in the app.

---

## 🎨 Minimal Material 3 Design

* Large, expressive MD3 cards and typography.
* Prominent Now Playing hero tile with album art, live status, and source player.
* Distraction-free, zero unnecessary text or clutter.

---

## 📦 Downloads (v1.1.0)

Grab the latest release from the [Releases](https://github.com/madhuraj0/live-alerts-bridge/releases/tag/v1.1.0) page:

* **`LiveAlertsBridge-v1.1.0-appleMusic.apk`** (Package: `com.apple.android.music`)  
  👉 **Recommended if you do not use Apple Music.** (Compatible alongside official Spotify).
* **`LiveAlertsBridge-v1.1.0-spotify.apk`** (Package: `com.spotify.music`)  
  👉 **Recommended if you do not use Spotify.** (Compatible alongside official Apple Music).

---

## 🚀 Quick Setup

1. Install your preferred APK build.
2. Tap **Grant** for Notification Access.
3. Tap **Unrestrict** to prevent aggressive OEM background killing.
4. *(Poweramp users)*: In Poweramp Settings > Look and Feel > Notifications, ensure **Android Media Player** is selected.
5. Tap **Test Capsule** to preview the Live Alert dynamic pill!
