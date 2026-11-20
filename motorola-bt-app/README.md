# MotorolaLink — Motorola Bluetooth Accessory App (Android)

Kotlin Android app that discovers, pairs, and connects to a Motorola Bluetooth
accessory (headset, speaker, earpiece) using the standard Android Bluetooth APIs
(HFP / A2DP), including a Bluetooth SCO voice channel for push-to-talk style voice use.

## Features
- Runtime permissions for Android 12+ (BLUETOOTH_CONNECT/SCAN) and legacy (<31)
- Scan & discover nearby devices, list paired devices, request pairing (createBond)
- Connect through the BluetoothHeadset profile proxy
- Open/close the SCO voice channel (phone-style mic + earpiece routing)
- Broadcast receiver for connect/disconnect/pairing/SCO state changes

## Build
Open the project in Android Studio (Hedgehog or newer, with SDK 34 installed) and run:

```
./gradlew :app:assembleDebug
```

`minSdk` is 26 (Android 8.0); targetSdk 34 (Android 14).

## Notes
- Motorola does not publish a Bluetooth SDK, so vendor-proprietary behavior
  (custom button remapping, proprietary codecs) is not available to third-party apps.
- `BLUETOOTH_PRIVILEGED` is declared but only honored for system apps; normal
  pairing happens through the system pairing dialog (`createBond()`).
- You will want the `RECORD_AUDIO` permission when opening the SCO voice channel.

## Structure
```
app/src/main/java/com/motorolalink/MainActivity.kt        UI + permission flows
app/src/main/java/com/motorolalink/BluetoothController.kt Bluetooth stack wrapper
app/src/main/res/layout/activity_main.xml                 Single-screen layout
```
