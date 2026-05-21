# 💊 VisionPill: Automated Computer Vision Pill Counter & Inventory System

> A closed-loop mechatronic and software ecosystem that automates medication counting, inventory tracking, and dosage scheduling using Edge AI and Human-in-the-Loop validation.

## 🚀 Overview

Managing medication inventory manually is prone to human error, especially for patients with complex dosage schedules. This project bridges the gap between physical hardware and digital health tracking. By leveraging an **ESP32-CAM** and a native **Android application**, this system optically scans, counts, and logs medication into a persistent local database, ensuring the user's digital inventory always matches their physical reality.

## ✨ Key Features

* **Agnostic Shape Detection:** Utilizes an advanced OpenCV pipeline to detect pills of any geometric shape (circles, oblong capsules, ovals) without hardcoded dimensional constraints.
* **Human-in-the-Loop (HITL) Validation:** Features an interactive "Magic Wand" (FloodFill) and tap-to-toggle UI, allowing users to manually correct the computer vision algorithm if lighting anomalies cause false positives/negatives.
* **Hardware Integration:** Seamless HTTP communication with an ESP32-CAM module to fetch live, uncompressed raw image data from the physical counting tray.
* **Offline First & Privacy Focused:** Uses `SharedPreferences` and `Gson` to maintain persistent, offline databases for both the medical inventory and the alarm schedules.
* **OS-Level Scheduling:** Integrates directly with Android's `AlarmManager` and `BroadcastReceiver` to wake the device and emit high-priority notifications, ensuring dosage adherence.

---

## 🧠 System Architecture

### The Computer Vision Pipeline

The optical counting relies on a classic edge-detection funnel, optimized to filter out table textures and harsh lighting:

1. **Grayscale Conversion:** Reduces mathematical load.
2. **Gaussian Blur (9x9):** Eliminates fine noise.
3. **Canny Edge Detection:** Isolates physical pill boundaries.
4. **Morphological Closing:** Acts as mathematical "glue" to reconnect broken contours caused by flash reflections.
5. **Contour Extraction:** Filters shapes by area (`> 800px`) and draws exact perimeters.

### Application Data Flow

The system operates on a centralized Singleton architecture where the `GestorBotiquin` (Inventory) and `GestorAlarmas` (Scheduler) act as the global state, ensuring that when a physical pill is counted by the camera, it immediately becomes available to be scheduled in the Alarm UI.

---

## 🛠️ Tech Stack

### Software

* **Language:** Kotlin
* **IDE:** Android Studio
* **Computer Vision:** OpenCV for Android (C++ backend)
* **Data Serialization:** Google Gson
* **Hardware API:** CameraX (for local smartphone camera fallback and Torch/Flash control)

### Hardware

* **Microcontroller:** ESP32-CAM - ESP32

---

## ⚙️ Installation & Setup

### 1. ESP32-CAM Setup

1. Flash the ESP32-CAM with the standard Camera Web Server sketch via the Arduino IDE.
2. Connect the ESP32 to the same local WiFi network as your smartphone.
3. Note the assigned IP Address.

### 2. Android App Setup

1. Open the project in Android Studio.
2. Navigate to `MainActivity.kt` and update the `URL_ESP32_CAPTURA` variable with your hardware's IP:
```kotlin
private val URL_ESP32_CAPTURA = "http://YOUR.ESP32.IP.ADDRESS/captura"

```


4. Build and run on a physical Android device (Android 8.0+ recommended). *Note: The CameraX and AlarmManager features will not work correctly on emulators.*

---

## 🖼️ Graphical Abstract

>![Graphical Abstract](https://github.com/user-attachments/assets/028f0f6f-9db2-43cd-853f-829b60dc0257)

---

## 🗺️ Roadmap / Future Work

* [ ] **Color Space Filtering (HSV):** Implement color detection to classify different medications in the same tray (e.g., separating red Ibuprofen from white Paracetamol).
* [ ] **MQTT Integration:** Upgrade from HTTP requests to an MQTT broker for faster, bidirectional, low-latency communication with the hardware.
* [ ] **Export to CSV:** Allow users to export their adherence logs to share with medical professionals.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! 



https://github.com/user-attachments/assets/292b9581-0e94-46ae-aa8b-847e1178b55f



https://github.com/user-attachments/assets/0871ba42-6179-4cbd-b0a2-7f1b62766d18









