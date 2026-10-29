# OxygenOS Live Alerts Bridge

> **Note:** Intended for **OxygenOS 15**, which natively only supports Spotify and Apple Music for Live Alerts (Fluid Cloud / Dynamic Island).

An ultra-efficient background bridge that enables **OxygenOS 15 Live Alerts** for third-party media players (YouTube Music, Poweramp, BitChord, VLC, etc.) without root.

---

## How It Works

OxygenOS 15 restricts media Live Alerts to two whitelisted package identities. This bridge runs under one of those identities to relay active media playback from any music player into the native system capsule:

* **Apple Music Flavor** (`com.apple.android.music`): Use this if you already use Spotify.
* **Spotify Flavor** (`com.spotify.music`): Use this if you already use Apple Music.

---

## Features

* **Native Live Alerts Integration**: Displays track metadata, album artwork, and playback controls directly in the Dynamic Island capsule and notification drawer.
* **Overlay Player**: Fullscreen Aquamorphic player featuring:
  * Progress seek bar & time elapsed/remaining.
  * Interactive volume slider with step buttons.
  * Quick-tap album art to open the active media player.
  * Configurable auto-redirect timer.
* **App Blacklist**: Exclude specific apps (navigation, social media, games) from triggering the bridge.
* **Launcher Shortcut**: Long-press the launcher icon to access "Bridge Settings" directly.
* **Battery Optimized**: Purely event-driven (`NotificationListenerService` & `MediaSessionManager`). 0% idle CPU, zero background polling, and zero wake locks.

---

## Setup

1. Download and install the APK flavor corresponding to the service you do **not** use from [Releases](https://github.com/madhuraj0/live-alerts-bridge/releases).
2. Open the app and grant **Notification Access**.
3. Set background battery usage to **Don't optimize** (Unrestricted).
4. Play music in any supported player — the Live Alert capsule appears automatically.
