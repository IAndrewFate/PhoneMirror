# PhoneMirror

PhoneMirror is a lightweight, low-latency screen and audio mirroring solution from an Android phone to an Android TV over a local Wi-Fi network. It functions similarly to Miracast or Apple AirPlay, operating entirely offline via direct peer-to-peer TCP streaming without any cloud servers, WebRTC, RTSP, ExoPlayer, or FFmpeg dependencies.

## Architecture

```
+-----------------------------------------------------------------------------+
|                          PHONE (Sender / API 26+)                           |
|                                                                             |
|  +--------------------+         +---------------------------------------+   |
|  |  MediaProjection   |         | AudioPlaybackCapture (Android 10+)   |   |
|  +---------+----------+         +-------------------+-------------------+   |
|            | Surface input                          | PCM chunks (20ms)     |
|            v                                        v                       |
|  +--------------------+         +---------------------------------------+   |
|  | MediaCodec (H.264) |         |  MediaCodec (Opus / AAC fallback)     |   |
|  +---------+----------+         +-------------------+-------------------+   |
|            | VideoFrame (Annex-B)                   | AudioFrame            |
|            +--------------------+-------------------+                       |
|                                 |                                           |
|                                 v                                           |
|                   +---------------------------+                             |
|                   | Framing & Overflow Policy |                             |
|                   +-------------+-------------+                             |
+---------------------------------|-------------------------------------------+
                                  | TCP direct stream (:47700)
                                  v
+-----------------------------------------------------------------------------+
|                        ANDROID TV (Receiver / API 26+)                      |
|                                                                             |
|                   +---------------------------+                             |
|                   |   MirrorServer & Parser   |                             |
|                   +-------------+-------------+                             |
|                                 |                                           |
|            +--------------------+-------------------+                       |
|            | VideoFrame                             | AudioFrame            |
|            v                                        v                       |
|  +--------------------+         +---------------------------------------+   |
|  | MediaCodec Decoder |         |          MediaCodec Decoder           |   |
|  +---------+----------+         +-------------------+-------------------+   |
|            | Surface                                | PCM (48 kHz stereo)   |
|            v                                        v                       |
|  +--------------------+         +---------------------------------------+   |
|  |    SurfaceView     |         |     AudioTrack (via AvSyncEngine)     |   |
|  +--------------------+         +---------------------------------------+   |
+-----------------------------------------------------------------------------+
```

## Build

### Prerequisites
- JDK 17
- Android SDK with Platform 35 and Build-Tools 35.0.0
- Android NDK (optional, pure Kotlin/Java build)

### Build Commands
Run from repository root:

```bash
# Debug builds (all modules, tests, lint, debug APKs)
.\gradlew.bat assembleDebug

# Run unit and integration tests
.\gradlew.bat test

# Release builds (R8 minification, resource shrinking, signing)
.\gradlew.bat assembleRelease
```

Release APK outputs:
- `sender/build/outputs/apk/release/sender-release.apk`
- `receiver/build/outputs/apk/release/receiver-release.apk`

When `keystore.properties` is absent, release builds automatically fall back to debug signing with a build warning.

## Install

Install both APKs via Android Debug Bridge (`adb`):

```bash
# Install phone sender app
adb -s <phone-device-id> install -r sender/build/outputs/apk/release/sender-release.apk

# Install Android TV receiver app
adb connect <tv-ip-address>
adb -s <tv-ip-address>:5555 install -r receiver/build/outputs/apk/release/receiver-release.apk
```

Ensure "Install from Unknown Sources" is enabled in Android TV developer options.

## Usage

1. **Launch TV App**: Open PhoneMirror on your Android TV. The screen displays the TV's IP address, port (`47700`), and a 4-digit PIN code.
2. **Launch Phone App**: Open PhoneMirror on your Android phone connected to the same Wi-Fi network.
3. **Discover or Enter IP**: The phone app automatically discovers the TV via Network Service Discovery (mDNS/NSD). If discovery is restricted by your router, enter the TV IP address and port `47700` manually.
4. **Pair with PIN**: Enter the 4-digit PIN shown on the TV screen. Once paired, the TV remembers the phone so future connections connect immediately.
5. **Start Mirroring**: Tap **Start Mirroring** and approve the system screen capture prompt.
6. **Quick Settings**: Toggle mirroring anytime using the Android Quick Settings tile.

## Settings

### Phone Sender Settings
| Setting | Range / Options | Default | Description |
|---|---|---|---|
| Bitrate | 2 – 16 Mbps | 8 Mbps | Video encoding bitrate. Adaptive rate control steps down on network congestion. |
| Resolution Cap | 1080p, 720p | 1080p | Maximum video bounding box. Proportions preserved, aligned to 16 px. |
| Frame Rate | 30 fps, 60 fps | 60 fps | Target encoding frame rate. |
| Audio Capture | On / Off | On | Internal audio capture (requires Android 10+). |
| Dim Screen | On / Off | Off | Lowers phone screen brightness while mirroring to save battery. |

### TV Receiver Controls
| Control | Action | Description |
|---|---|---|
| Regenerate PIN | Button | Generates a new random 4-digit pairing PIN. |
| Clear Paired Devices | Button | Clears trusted device tokens, requiring PIN re-entry on next connection. |
| Toggle Server | Button | Starts or stops the listening TCP server. |

## Known limitations

- **DRM Protected Content (`FLAG_SECURE`)**: Streaming apps protected with DRM (such as Netflix, Kinopoisk, or banking apps) render as a black screen by Android OS security design.
- **Audio Capture Version Requirement**: Internal audio capture (`AudioPlaybackCapture`) requires Android 10 (API 29) or higher on the phone. Phones on Android 8.0–9.0 stream video only.
- **Per-App Audio Policy**: Third-party apps that specify `ALLOW_CAPTURE_BY_NONE` in their audio attributes opt out of capture and will be silent by OS policy.
- **Local Network Only**: Both devices must be on the same local network subnet. 5 GHz Wi-Fi is strongly recommended for 100–150 ms latency.
- **Router AP Isolation**: Access Point (Client) isolation must be disabled in router settings to allow peer-to-peer TCP communication.
- **Single Client**: The receiver accepts one active streaming session at a time.
- **mDNS / NSD Flakiness**: Certain TV box firmware or router models block multicast DNS packets. Use manual IP entry if NSD fails.

## Troubleshooting

- **TV Not Found in Discovery**:
  - Verify both devices are connected to the same Wi-Fi network and subnet.
  - Disable "AP Isolation" / "Client Isolation" in router settings.
  - Enter the TV IP address and port `47700` manually in the phone app.
- **Choppy Playback or Stuttering**:
  - Wi-Fi 2.4 GHz bands suffer heavy airtime contention. Switch both devices to 5 GHz Wi-Fi.
  - In phone settings, reduce bitrate to 4–6 Mbps or switch resolution cap to 720p.
- **Picture Slows Down or Lags Over Time**:
  - Weak Wi-Fi signal causes TCP backpressure. PhoneMirror automatically steps down bitrate (a notification appears). Move closer to the Wi-Fi router.
- **No Sound from TV**:
  - Verify the phone runs Android 10 (API 29) or later.
  - Check that the Audio toggle is enabled in Phone settings.
  - Some apps explicitly prohibit audio capture (`ALLOW_CAPTURE_BY_NONE`); test with standard YouTube or media player apps.
- **Black Screen on Streaming Apps**:
  - Content protected by DRM cannot be captured via Android MediaProjection (`FLAG_SECURE`).
- **TV Screensaver Interrupts Stream**:
  - Although the app requests `FLAG_KEEP_SCREEN_ON`, some custom TV vendor skins enforce screensavers / Daydream after 15–30 minutes. Disable or extend the screensaver timeout in Android TV system settings.
- **Gradle Build Failures on Windows**:
  - When the project resides in a cloud-synced folder (such as Yandex.Disk), background sync locks build artifacts. Exclude `.gradle` and `build` directories from sync, or temporarily pause file synchronization during builds.

## Кратко на русском

PhoneMirror — решение для прямой трансляции экрана и звука со смартфона на телевизор с Android TV через домашнюю сеть Wi-Fi без облачных сервисов, WebRTC, RTSP и внешних тяжелых библиотек.

- **Стек**: чистый Kotlin, нативные кодеки Android (MediaCodec H.264 + Opus/AAC), передача по TCP через порт `47700`.
- **Задержка**: 100–150 мс при использовании 5 ГГц Wi-Fi.
- **Сборка**: JDK 17, Android SDK 35 (`.\gradlew.bat assembleRelease`).
- **Установка**: `adb install -r` для APK отправителя (`sender`) и приёмника (`receiver`).
- **Использование**: запустить приложение на ТВ, запустить на смартфоне, подключиться (автопоиск или ввод IP), ввести PIN с экрана ТВ, подтвердить системный запрос захвата экрана.
- **Ограничения**: DRM-контент отображается чёрным экраном (`FLAG_SECURE`), захват звука требует Android 10+, на роутере должна быть выключена изоляция клиентов (AP Isolation).

---

All rights reserved.
