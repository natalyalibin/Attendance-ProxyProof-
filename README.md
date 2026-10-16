# Present — local BLE attendance

Android app, package `com.example.attendence`, minimum Android 7 (API 24). No backend, Firebase, network account, or internet permission.

## Try it with two physical Android phones

1. Build with `./gradlew :app:assembleDebug`, or run from Android Studio. Install `app/build/outputs/apk/debug/app-debug.apk` on **both** phones. Existing profiles and records are preserved by an update.
2. Set up the admin phone as **Office station** with a name, admin ID, shared office code (for example `KOCHI-HQ`), and a six-digit local PIN.
3. Set up the employee phone as **Employee** with a name, unique employee ID, the **same office code**, and a separate PIN.
4. On the admin phone, grant Nearby devices permissions and tap **Start station**. Allow notifications to see the running station and its Stop action. You can switch apps or lock the screen after the station starts. This phone must support BLE advertising and a GATT server.
5. On the employee phone, tap **Scan & check in**, grant Nearby devices and precise Location permissions, and enable Bluetooth and Location services. Stay near the station until **Received by admin** appears.
6. The admin's team list updates immediately. The employee's next action becomes **Scan & check out**. Both devices retain records after restart.
7. Review all received records and export **team attendance as CSV** from the admin phone. Employees can also export their own confirmed history. Admin collection continues while exporting, switching apps, locking the app, or locking the screen. Stop it explicitly in the app or the station notification.

Use one admin station per office code. Multiple separate stations do not synchronize their databases.

## Reliable local delivery

- Admin advertises a connectable, office-specific BLE service and receives attendance via a GATT write characteristic. Names and attendance are transferred over the connection, not in advertisements.
- Employee scans for up to 15 seconds, then connects and transfers for up to 30 seconds. Versioned messages are split into writes of at most 20 bytes to support the default BLE MTU.
- After finding the station, the employee stores a **pending record before sending**. The admin acknowledges the final packet only after saving the complete validated record locally.
- If the connection fails or a receipt is lost, **Retry pending attendance** sends the same event ID, original action, and original employee timestamp. The admin deduplicates by ID and rejects conflicting contents. A new attendance action is blocked until that pending receipt is confirmed.
- Cancelling or leaving the employee app may happen after the admin saved the record. The UI preserves the pending record and does not incorrectly claim that no attendance was taken.
- Incomplete, malformed, oversized, or wrong-office records are not saved. Receive buffers are per peer and bounded; disconnected or expired transactions are discarded.
- Old records from the initial scan-only app remain visible as **Local only**. They are not retrospectively sent or marked as received by the admin.

## Local storage and prototype boundaries

- One self-configured identity and role per installation. Employee IDs must be unique within the office; team status groups records by employee ID.
- Salted PBKDF2 PIN verification, app-private local storage, cloud backup and attendance device-transfer backups disabled. PIN recovery is not implemented. Clearing app data deletes local records and setup.
- The admin stores employee name, ID, check-in/out action, employee timestamp, office, reported signal strength, event ID, and a separate admin receipt timestamp. CSV includes both timestamps and delivery status. The team status reflects the latest received event for each employee; it is not a live people-presence count.
- This is a trusted-device prototype. Self-entered employee IDs and copied office signals are not authenticated. BLE transfer currently does not require bonding/encryption, so use test identities until authenticated, encrypted enrollment is added. Phone clocks are not trusted payroll timestamps, and BLE visibility is not a fixed-distance or anti-fraud proof.
- The admin receiver runs in a `connectedDevice` foreground service, independent of the Activity. Starting it requires an unlocked admin session and Bluetooth permissions. A low-importance notification shows station status and a Stop action, without employee identities. Notification permission is requested separately on Android 13+; denial does not block collection, but hides the notification drawer entry (Android still provides active-app controls).
- The service survives Activity destruction, task dismissal, app lock, and screen-off. Android can restart it after ordinary process reclamation (`START_STICKY`); restart is not guaranteed. Force-stop, a phone reboot, or Bluetooth being turned off requires manually starting the station again. No boot receiver or battery-optimization exemption is requested. Device/vendor power management can still interrupt Bluetooth; verify extended screen-off behavior on the intended admin phone.
- The employee must keep the transfer screen open until the receipt arrives. Leaving it cancels the connection and preserves any pending record. PIN unlock remains separate from the station lifetime. No screen-on or permanent CPU wake lock is held.
- Location permission is requested for employee scanning because office attendance derives physical presence. See [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions). GATT writes are acknowledged through [BluetoothGattServer.sendResponse](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer).

## Structure

- `MainActivity.kt`: setup, PIN unlock, station/team dashboard, employee pending/confirmed attendance, history, CSV export, permissions.
- `AttendanceStationService.kt`: background admin station lifetime, connected-device foreground notification, Stop action, Bluetooth-off cleanup, and dashboard state updates.
- `BleAttendance.kt`: GATT server/client, scan, connectable advertising, sequential writes, deadlines, cancellation. Admin transport is service-owned; employee transport is Activity-owned.
- `AttendanceProtocol.kt`: versioned bounded wire format and message assembly.
- `AttendanceStore.kt`: local profile/PIN/attendance persistence, pending delivery, durable receipt, and duplicate detection. Firebase can later replace the persistence boundary; trusted enrollment requires additional design.

## Validation

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
With an Android phone connected, run `./gradlew :app:connectedDebugAndroidTest`. Repository tests use isolated preference files. The service smoke test requires a fresh test install with Bluetooth enabled: it skips if a profile already exists or advertising is unsupported, otherwise creates and removes a temporary admin profile, checks screen-off survival and the notification Stop action, and wakes the screen afterward. Android connected tests may install/uninstall test apps; use a dedicated test phone/install.

Automated tests cover attendance alternation, durations, office UUIDs, Unicode and minimum-MTU messages, partial/restarted/oversized/invalid messages, persistent pending retries after a lost receipt, deduplication, conflicting IDs, office/role validation, and old-record compatibility. A service smoke test also covers duplicate starts, Activity destruction, short screen-off survival, and notification Stop. These do not replace a two-phone radio test or extended idle testing.

Before a pilot, test real employee-to-admin delivery on two phones; disconnect after sending and retry; restart both apps; verify no duplicate team rows; test check-out, multiple employees, mismatched office codes, denied permissions, Bluetooth off, unsupported advertising, timeout, cancel, and CSV export. Also test delivery after the admin screen has been locked for several minutes, notification denial, task dismissal, Stop, Bluetooth-off, and reopening the dashboard. Two-phone delivery has now been verified with a Pixel 9 admin and Motorola edge 2024 employee; see the device test notes below. Longer idle and other vendor/device combinations still need validation.

Android foreground-service reference: [connected device service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).

## Two-phone verification — 15 October 2026

Tested the installed app on a Pixel 9 (admin) and Motorola edge 2024 (employee), preserving both profiles and the original pending event.

- Reproduced the reported failure: discovery and connection succeeded; the first characteristic write was queued by the Motorola but did not reach the app's GATT callback on the Pixel. Pixel Bluetooth stack logs also reported an unknown server context. The employee correctly retained the pending record.
- Restarted Bluetooth on the Pixel, then started the station again. The same pending event was successfully received and acknowledged, without changing its original timestamp or ID.
- Verified check-out with the admin display off and another check-in while the admin was still locked. Both phones stored matching confirmed event IDs.
- Stopped and restarted the station without resetting Bluetooth, then verified another check-out and check-in. The employee was left checked in and the admin station running. Test events are retained in history.
- Added BLE stage/error diagnostics under `AttendanceBLE`; diagnostic statements do not include employee names, PINs, or event payloads. Unit tests and lint passed.

If the same connection-with-no-receipt failure recurs, stop the station, toggle Bluetooth off/on on the admin phone, start the station, and retry the employee's **pending** record. This is a recovery for the observed Bluetooth stack state, not proof that every Android/vendor stack issue is permanently eliminated. No app data reset is needed. Do not uninstall or clear data to recover a pending event.
