# BioZAndroid

BioZAndroid is a Jetpack Compose BLE client for the MAX32655 BioZ wearable (DATS firmware). The app scans for BLE peripherals, connects to the BioZ device, subscribes to the notifiable/writable characteristic, and streams BioZ data with live charting.

## Features
- Modern Android BLE (no legacy API) with automatic discovery of the writable/notifiable characteristic.
- MVVM with `BleViewModel` and coroutine Flows for scan results, connection state, logs, and BioZ packets.
- Auto-send `start@YYYYMMDD@HHMMSS` on connect and `stop` when leaving the connected screen or on disconnect.
- Live Q/I plotting using MPAndroidChart embedded in Compose.
- Reconnect logic if the peripheral drops the link and custom command entry for manual messages.

## Running on a real device
1. Use Android Studio (Giraffe or newer) with the Android Gradle Plugin 8.2.2.
2. Connect a physical Android device (BLE + Android 8.0+/API 26+) with developer mode enabled.
3. Open this folder in Android Studio, let Gradle sync, and select the `app` configuration.
4. Install the APK to a real device (emulators lack BLE support).
5. On first launch, grant Bluetooth (and location on Android 11 and lower) permissions when prompted.
6. Tap **Scan** to discover your BioZ wearable, then tap the device row to connect. The app begins streaming and plotting once notifications arrive.

## Notes
- The app expects notification payloads formatted as `timestamp,Q,I,Frequency` (UTF-8). Extra fields are ignored gracefully.
- The connection will try to re-establish automatically if the peripheral disconnects unexpectedly.
- The custom command box can be used for ad-hoc BLE writes; writes target the same characteristic used for notifications.
