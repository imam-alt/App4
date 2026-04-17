# App4

App4 is an Android punch challenge app.

## What it does
- Uses the phone accelerometer or linear acceleration sensor.
- Detects one punch motion after the user presses **Arm Punch**.
- Estimates phone movement momentum from the sensor data.
- Saves local punch records on the device.
- Shares a challenge message through Android Sharesheet so the user can choose WhatsApp.

## Important note
The app estimates the momentum of the **phone movement**, not the true physical momentum of the fist itself.

## APK build on GitHub
This repository includes a GitHub Actions workflow at:
- `.github/workflows/build-apk.yml`

Every push to `main` can build a debug APK and upload it as a workflow artifact named:
- `app4-debug-apk`

## Open in Android Studio
Open the project folder and let Android Studio sync Gradle.
