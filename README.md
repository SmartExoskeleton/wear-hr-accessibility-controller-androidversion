# Wear HR Accessibility Controller

A production-focused Wear OS + Android companion system that captures on-watch heart-rate telemetry and streams it to a paired phone in near real time.

## Scope
- Continuous heart-rate acquisition on Wear OS (`TYPE_HEART_RATE`)
- Foreground-service backed background operation on watch
- Real-time cross-device delivery via Google Play Services Wearable Message API
- Persistent phone-side state and live dashboard rendering with Jetpack Compose

## System Design
- **Watch app (`wear`)**
  - Runtime permission handling for `android.permission.health.READ_HEART_RATE` with `BODY_SENSORS` fallback
  - Sensor selection strategy preferring wake-up heart-rate sensors
  - Foreground service (`HeartRateForegroundService`) to continue streaming when UI is backgrounded
  - Message path: `"/hr"`
- **Phone app (`app`)**
  - `WearableListenerService` ingestion (`HrReceiverService`)
  - Shared persistence and reactive UI state (`HrStore`, `StateFlow` + `SharedPreferences`)
  - Live Compose dashboard (`MainActivity`)

## Security and Platform Compliance
- Explicit runtime permission gating before sensor registration
- Foreground service with health service type for long-running sensor operations
- Manifest-declared attribution (`heart_rate_stream`) and matching attribution contexts for AppOps hygiene on recent Android builds

## Build
```bash
./gradlew :app:assembleDebug :wear:assembleDebug
```

## Runtime Validation Checklist
1. Launch watch app and grant sensor permission.
2. Confirm watch status transitions to live heart-rate updates.
3. Put watch app in background and verify continued transmission.
4. Open phone app and verify BPM updates in real time.
5. Reopen watch app and verify stream continuity.

## Notes
- The watch module currently uses the same `applicationId` as phone (`com.example.heartratemonitor`) for this integration setup.
- Developed for modern API levels (`compileSdk 36`, `targetSdk 36`).
