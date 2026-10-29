<p align="center">
  <img src="docs/banner.png" alt="SpeedCam" width="100%">
</p>

<p align="center">
  <b>English</b> · <a href="README.md"><b>العربية</b></a>
</p>

<p align="center">
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="On-device AI" src="https://img.shields.io/badge/AI-on--device-00BFA5">
</p>

# SpeedCam

**An Android app that turns a phone into a traffic-counting station.** It detects vehicles with on-device AI, classifies them (car, microbus, tuk-tuk, truck, bus, motorcycle), measures each vehicle's speed, reads its license plate, and produces reports ready for traffic engineers. Everything runs on the phone: no server and no radar hardware.

> The app interface is in Arabic.

## At a glance

| | |
|---|---|
| **Measurement** | Speed of each vehicle between two lines, A and B, that you place on the image |
| **Classification** | 6 vehicle types, including microbuses and tuk-tuks |
| **Plates** | Reads Egyptian plates and logs them with each vehicle's speed |
| **Reports** | Statistics, hourly/daily traffic volume, CSV export that opens in Excel |
| **Evidence** | A snapshot for every vehicle above the speed limit you set |
| **Sources** | Phone camera or a network IP camera |

## Screens

<p align="center">
  <img src="docs/screens.png" alt="App screens" width="100%">
</p>

> The image above is an **illustration** of the five tabs (Camera, Vehicles, Plates, Statistics, Settings) with sample numbers. It is not a set of real screenshots.

## Features

### Measurement and detection
- **Three operating modes**, switchable at any time:
  - **Speed accuracy:** the main mode for precise speed measurement.
  - **Count all vehicles:** counts any vehicle crossing any line, without focusing on speed.
  - **Fast:** watches motion at the two lines at roughly 30 FPS and uses the model only to classify.
- **6 vehicle classes** using EfficientDet-Lite0, with microbus and tuk-tuk separated by vehicle length (adjustable thresholds).
- **Network IP camera** support (MJPEG over HTTP) as an alternative to the phone camera.

### Accuracy
- **Speed calibration** from real samples (linear regression), with an error margin shown.
- **Automatic GPS calibration:** a second phone inside a reference vehicle logs its speed, and the app matches it to its own measurement at the camera site.
- **Perspective transform (homography)** from four road points, plus lens distortion correction.
- **Robust statistics:** Theil-Sen regression, Kalman filter, and outlier rejection (3 × MAD).

### Plates and evidence
- **Egyptian plate reading** with Arabic Tesseract OCR, merging multiple reads of the same vehicle by voting (Levenshtein distance).
- **Evidence snapshots** for violators: a full frame with the vehicle box, lines A/B, and a bar showing speed, limit and time. Stored in the app's private storage, not in the photo gallery.
- **Vehicles tab:** every vehicle listed, with those over the limit in red, plus the confirmed-violator rate.

### Traffic engineering and alerts
- **Traffic flow** in vehicles and passenger-car equivalents (PCE/h), peak 15 minutes, and peak hour.
- **Headways** and **level of service** (LOS A to F).
- **Per-lane statistics** (requires the perspective transform).
- **Alerts:** wrong-way driving, stopped vehicle, congestion, and instant high-speed. All configurable or switchable off.

### Field reliability
- **Background operation** through a camera foreground service, so measuring continues with the screen off.
- **Thermal and battery guard** with warnings and automatic stop.
- **Night controls:** locked frame rate and manual exposure (shutter speed and ISO).
- **Record cap** (20,000 by default): the oldest records are archived to a CSV file before deletion.

## How speed is measured

<p align="center">
  <img src="docs/how-it-works.png" alt="Speed measurement between lines A and B" width="100%">
</p>

The app records the moment a vehicle crosses each line, then divides the known distance between the lines by the time between the crossings. Final accuracy depends on **how precisely the distance is measured**, **calibration**, and **where the phone is mounted**, so test it first against a vehicle with a known speedometer reading.

## Processing pipeline

<p align="center">
  <img src="docs/pipeline.png" alt="Processing pipeline" width="100%">
</p>

## Getting started

### 1) Install the APK
1. Open the repository's **[Releases](../../releases)** page and download the latest `SpeedCam.apk`.
2. Allow installs from unknown sources on your phone, then install the file.
3. If the phone refuses an update over an older build, uninstall the old one first.

### 2) Build from source
**Requirements:** Android Studio (Koala or newer), JDK 17, and a physical phone running Android 10 or newer (`minSdk 29`).

1. Open the folder in Android Studio and wait for Gradle Sync.
2. Make sure a detection model is in `app/src/main/assets/`:
   - **Stock:** `efficientdet_lite0.tflite` (included in the project).
   - **Custom (more accurate):** `vehicles_custom.tflite` after training, following [`TRAINING.md`](TRAINING.md). When present, the app uses it automatically.
3. Run on a **physical phone** (preferable to an emulator, since the app relies on the camera).

> **Plate reading** needs the Arabic Tesseract language file (`ara.traineddata`). If you place it in `app/src/main/assets/` it is copied from there offline; otherwise it is downloaded once from the internet on first use.

### 3) First run and calibration
1. **Mount the phone** so vehicles cross in front of the camera sideways.
2. **Mark two points on the ground** and measure the distance between them in meters.
3. From the **Calibration** button, align the two yellow lines with the marks and enter the distance.
4. **Test with a vehicle at a known speed** from its speedometer, and adjust the calibration factor if needed.
5. Set the **speed limit** in Settings and start measuring.

For higher accuracy, enable the **perspective transform** with four road points, or use **GPS calibration**.

## Vehicle classification
- The stock model separates cars, trucks, buses and motorcycles.
- **Microbuses and tuk-tuks** are told apart by estimating vehicle length in meters from the calibration. This is less accurate than a custom model and is affected by how far the lane is from the calibration marks.
- Default thresholds: under **3.4 m** is a tuk-tuk, from **5.0 m** a microbus, and **8 m** a bus. All adjustable in Settings.
- For the best accuracy, train a custom model using the steps in [`TRAINING.md`](TRAINING.md).

## Outputs
| File | Contents |
|---|---|
| `traffic_stats_*.csv` | All readings: date and time, type, speed with confidence and error margin, direction, lane, and plate (letters, digits and read confidence) |
| `traffic_volume_hourly_*.csv` / `traffic_volume_daily_*.csv` | Traffic volume: one row per hour or day, one column per vehicle type plus the total |
| `speedcam_backup_*.csv` / `speedcam_archive_*.csv` | Backup, and archive of older records |

CSV files open directly in Excel and can be shared from the app or saved to the Downloads folder.

## Permissions
| Permission | Why |
|---|---|
| `CAMERA` | Capture the image and detect vehicles |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CAMERA` | Keep measuring with the screen off |
| `POST_NOTIFICATIONS` | Background-run notification and alerts |
| `WAKE_LOCK` | Keep the device awake while measuring |
| `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` | GPS calibration and camera-site location only |
| `INTERNET` | Network camera, and a one-time download of the plate language file |

## Performance
- Initial measurements on a **Realme 12X** with EfficientDet-Lite0: about **8 FPS** on the GPU and about **5 FPS** on the CPU. Results vary by device, and the **Fast mode** can raise how often motion is observed at the lines.
- With a network camera, a frame's timestamp is when it **arrives at the phone**, not when it was captured, so speed accuracy is lower than with the phone camera.

## Project structure
```text
SpeedCam/
├── app/src/main/java/com/example/speedcam/
│   ├── MainActivity.kt        UI and the five tabs
│   ├── SpeedEngine.kt         Engine: detect, track, speed, record
│   ├── VehicleDetector.kt     Vehicle detection (MediaPipe)
│   ├── SpeedTracker.kt        Tracking and speed calculation
│   ├── Homography.kt          Perspective transform
│   ├── RobustStats.kt         Theil-Sen, Kalman, Levenshtein
│   ├── PlateOcr.kt / PlateReader.kt / PlateLocator.kt   Plate reading
│   ├── GpsCalib.kt            GPS calibration
│   ├── StreamSource.kt        Network camera (MJPEG)
│   ├── TrafficMetrics.kt      Flow, PCE, LOS, headways
│   ├── Evidence.kt            Evidence snapshots
│   ├── RecordingService.kt    Background operation
│   ├── Database.kt            Room database
│   └── CsvExporter.kt         Export
├── app/src/main/assets/       Detection model
├── TRAINING.md                Custom model training
└── train_model.py             Training script
```

## Tech stack
Kotlin · Jetpack Compose (Material 3) · CameraX 1.3 · MediaPipe Tasks Vision · EfficientDet-Lite0 · Tesseract4Android · Room · Gradle 8.7

## Responsible use and privacy
The app records vehicles, their plates, and snapshots of them. Before using it in a public place, **check local laws and regulations** on recording and plate data, and obtain any required permits. Data and snapshots are stored on the device itself; internet access is limited to the one-time plate-language download and to the network camera stream if you use one. Results are **statistical estimates** and should not be used alone as official evidence without verifying calibration against certified equipment.

## Author
[@Mostafa171295](https://github.com/Mostafa171295)
