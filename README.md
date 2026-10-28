# OxygenOS & ColorOS Live Alerts Media Bridge

An open-source background bridge that extends **OxygenOS & ColorOS Dynamic Island / Fluid Cloud Live Alerts** to any media player (such as **YouTube Music, Poweramp, BitChord, LastWave**, etc.) without requiring root access.

---

## 💡 How It Works

Out of the box, OxygenOS / ColorOS restricts the dynamic pill capsule (Live Alerts) to a hardcoded whitelist (primarily Spotify and Apple Music).

This application bypasses that restriction **without root** by acting as an intelligent media relay:
1. The bridge is compiled under an officially whitelisted package identity (`com.apple.android.music` or `com.spotify.music`).
2. It listens to system-wide media playback using standard Android `NotificationListenerService` and `MediaSessionManager` APIs.
3. When any unsupported music player (like YouTube Music or Poweramp) starts playing, the bridge mirrors its title, artist, album art, duration, and playback state into its own MediaSession.
4. OxygenOS detects an active session from an officially whitelisted app, triggering the native Live Alert capsule!
5. When you tap Next, Previous, Play, or Pause in the Live Alert capsule, the bridge dispatches the transport controls directly to your real music player.

---

## 📦 Downloads & Flavors

Under the [Releases](https://github.com/madhuraj0/live-alerts-bridge/releases) tab, two builds are available:
* **`LiveAlertBridge-appleMusic-v1.0.0.apk`** (Package: `com.apple.android.music`):  
  👉 **Recommended if you do not use Apple Music.** (You can still have official Spotify installed simultaneously).
* **`LiveAlertBridge-spotify-v1.0.0.apk`** (Package: `com.spotify.music`):  
  👉 **Recommended if you do not use Spotify.** (You can still have official Apple Music installed simultaneously).

---

## 🚀 Setup Instructions

1. Download and install the APK flavor of your choice.
2. Open **Live Alerts Bridge**.
3. Tap **Grant** next to *Notification Access Permission* and enable toggle for Live Alerts Bridge.
4. Tap **Unrestrict** next to *Unrestricted Battery Background* so OxygenOS does not kill the bridge service.
5. In your target music player (e.g. **Poweramp**):
   * Ensure standard Android notifications are enabled (**Settings > Look and Feel > Notifications > Android Media Player**).
6. In **OxygenOS Settings**:
   * Verify **Settings > Notifications & Status Bar > Live Alerts** is toggled **ON**.
7. Tap **Test Live Alert Capsule** in the app to immediately see the capsule appear in your status bar / punch hole notch!

---

## 🔒 Security & Privacy

* **100% Offline & Private:** No network requests, no analytics, no user data collection.
* **Banking Apps Safe:** Does not require root or bootloader modifications, preserving SafetyNet and Play Integrity.
* **Open Source:** Clean Kotlin codebase using Android Jetpack libraries.
