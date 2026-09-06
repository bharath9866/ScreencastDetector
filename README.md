# ScreencastDetector

Minimal native Android sample app to test screencast / PC-mirror detection in isolation (no React Native).

Target use case: validate detection signals on API 29 devices with **ASUS GlideX** before integrating back into OneApp proctoring.

## Important: GlideX on Android 10 requires Notification Access

On API 29, GlideX uses a **private virtual display** and hides MediaProjection/AppOps from third-party apps. The reliable detection path is the GlideX **"Stop mirroring"** notification (channel `GlideX_High_Notification_Channel_Id`), which requires **Notification Access** permission.

1. Open ScreencastDetector v1.3+
2. Tap **Enable Notification Access**
3. Enable access for **ScreencastDetector**
4. Return to the app

Or via adb (testing only):

```bash
adb shell cmd notification allow_listener com.example.screencastdetector/com.example.screencastdetector.CastNotificationListener
```

## Requirements

- Android Studio (Ladybug or newer recommended)
- Device or emulator with **API 29+** (tested target: Lenovo tablet API 29)
- Optional: ASUS GlideX installed for PC mirroring tests

## Open in Android Studio

1. Open `C:\D\StudioProjects\ScreencastDetector` in Android Studio.
2. Sync Gradle.
3. Run the **app** configuration on your device.

## Manual test procedure

### Baseline (no mirroring)

1. Ensure GlideX / screen mirroring is **off**.
2. Open ScreencastDetector.
3. Expect banner: **NOT DETECTED** (green).

### GlideX mirroring active

1. Start **ASUS GlideX** and mirror the device to your PC (PC Control sidebar visible on device).
2. Return to ScreencastDetector (leave mirroring running).
3. Expect within ~1 second:
   - Banner: **DETECTED** (red)
   - Reason: **GlideX mirroring notification**
   - Debug panel: `mirroringActive: true` under **Notification listener (GlideX)**

### Stop mirroring

1. Stop GlideX mirroring.
2. Return to the sample app.
3. Expect **NOT DETECTED** within ~3 seconds.

## adb cross-check (while GlideX is mirroring)

```bash
# Active MediaProjection session (system dump — not visible to third-party apps directly)
adb shell dumpsys media_projection

# Expected while mirroring:
# (com.asus.glidex, uid=...): TYPE_SCREEN_CAPTURE

# Sample app logs when detection fires
adb logcat -s ScreencastDetector:*

# GlideX AppOps (note PROJECT_MEDIA may be "ignore" on some OEM builds)
adb shell dumpsys appops | sed -n '/Package com.asus.glidex/,/^  Package /p'
```

## What this app detects

| Layer | Class | Signals |
|-------|-------|---------|
| External / virtual display | `DisplayCastDetector` | HDMI, WiFi display, presentation displays |
| System cast settings | `SystemCastDetector` | WiFi display status, Global settings keys |
| MediaRouter cast route | `MediaRouterCastDetector` | Non-default remote live-video route |
| PC mirror / screen capture | `ScreenRecordingDetector` | AppOps, MediaProjection reflection, dynamic ASUS package discovery, system UI projection, foreground services |
| Package discovery | `MirroringPackageRegistry` | Static list + installed ASUS/glide/cast packages |

Combined result is exposed via `ScreencastProbe`.

## Project structure

```
app/src/main/java/com/example/screencastdetector/
  MainActivity.kt           — debug UI with 3s auto-refresh
  ScreencastProbe.kt        — facade combining all detectors
  MirroringPackageRegistry.kt — static + dynamic mirroring package discovery
  MediaRouterCastDetector.kt — MediaRouter cast route probe
  SystemCastDetector.kt     — WiFi display / settings cast probe
  ScreenRecordingDetector.kt — AppOps, MediaProjection, OEM heuristics
  DisplayCastDetector.kt    — ported from OneApp ProctoringCastingDetector
```

## Next steps after validation

If this sample detects GlideX but OneApp still shows "None Detected", the gap is in the RN bridge / system-check timing (`ProctoringOverlayModule`, `runSystemCheck.ts`), not the native detector logic.
