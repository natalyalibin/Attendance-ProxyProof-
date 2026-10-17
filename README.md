# Attendance ProxyProof — college attendance prototype

Attendance-only Android app adapted from the original Present BLE prototype for **students and professors**. No employee portal, leave requests, approvals or cloud backend.

## Use

Open the repository root in Android Studio. Set up a student phone to check in/out, or a professor phone to collect attendance. Use the same campus code on both phones and choose a local six-digit PIN on each.

The professor starts the station; the student scans and sends attendance, keeping the transfer screen open until confirmation. The professor receives records locally and can view history or export CSV. The foreground station continues with the screen locked; restart it manually after reboot or force-stop.

Real BLE attendance needs two physical phones. One phone can exercise setup, PIN access and history screens, but cannot verify a real transfer to itself. This app does not include the separate portal’s demo attendance mode.

## Scope

Tracks campus check-in/check-out and recorded duration, not subject-wise lecture attendance or attendance percentages. Prototype roles are self-configured. Campus codes identify BLE stations and are not secure credentials or proof against proxy attendance.

The original application ID, local storage keys and BLE protocol are retained for compatibility. Existing installations keep their records and roles: employee becomes student; admin becomes professor. No data migration is needed.

## Build and tests

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Local settings, generated builds and phone records are excluded from source control. No Firebase integration yet.
