# Aether SAGA - journeys through endless stories

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Aether SAGA Logo" width="120" height="120" />
</p>

<p align="center">
  <strong>Ultra-lightweight, high-performance e-book & comic reader engineered for E-Ink devices and Android.</strong><br>
  <em>"Aether SAGA, journeys through endless stories"</em>
</p>

<p align="center">
  <a href="./README_ko.md">🇰🇷 한국어 설명서 (Korean)</a> •
  <a href="./RELEASE_NOTES.md">Release Notes (v1.0)</a> •
  <a href="./ARCHITECTURE.md">Architecture Spec</a> •
  <a href="./FUNCTION.md">Functional Spec</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.0.0-blue.svg" alt="Version 1.0.0" />
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2026%2B)-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/APK%20Size-~650%20KB-success.svg" alt="APK Size" />
  <img src="https://img.shields.io/badge/Dependencies-Zero%20Heavy%20Libs-orange.svg" alt="Dependencies" />
  <img src="https://img.shields.io/badge/license-Apache%202.0-lightgrey.svg" alt="License" />
</p>

---

## 📖 Overview

**Aether SAGA** is an ultra-lightweight, battery-efficient Android e-book and comic viewer crafted specifically for **E-Ink devices** (e.g., Crema Luna X 6.0", Onyx Boox series) as well as standard **Android tablets and smartphones**.

Unlike conventional e-book readers bloated with heavy third-party dependencies, Aether SAGA adopts a strict **Zero Heavy Library Policy**. Built exclusively using **pure Android Framework Views**, lightweight **Kotlin Coroutines**, and **Room DB (KSP)**, the resulting release binary is **under ~650 KB**, achieving near-instant cold startup, minimal memory footprint, and zero UI stuttering.

---

## ✨ Key Features & Architectural Philosophy

### 1. 🪶 Ultra-Lightweight & Minimal Dependencies (Zero Heavy Lib Policy)
* **No Bloatware**: Zero Jetpack Compose, AppCompat, Hilt/Dagger, Retrofit/OkHttp, or Glide/Coil.
* **Instant Cold Start**: Cold boot in milliseconds with minimal CPU spikes on low-spec E-Ink hardware.
* **Compact Footprint**: Standalone release APK is approximately **650 KB**, minimizing flash storage consumption.

### 2. ⚡ Bifurcation Architecture: Tailored for E-Ink & Mobile Displays
Aether SAGA dynamically optimizes rendering paths based on screen hardware:

* **E-Ink Mode (Carta 1300/1200 & Kaleido 3)**:
  * Eliminates software color filters and battery-draining animations to protect raw E-Ink contrast.
  * Replaces flat borders with specialized **dotted dividers** (`DashPathEffect([3dp, 3dp])`) to prevent e-paper ghosting artifacts.
  * Full-screen flash refresh interval configurable from **1 to 30 pages** to wipe residual e-ink particles.
  * Font boldness reinforcement (**0–10 levels**) and direct frontlight brightness/color-temperature controls.
* **LCD / OLED Mode**:
  * **7 Immersive Reading Themes**: Pure White, Warm Amber, Paper Cream, Mint Breeze, Lavender Fog, Deep Charcoal, and AMOLED Pitch Black.
  * Eye-care night filters with adjustable opacity and warm color temperatures.
* **4 Core Display Presets (with real-time `✓` checkmark status)**:
  1. `Auto Detect (Recommended)`
  2. `Pure E-Ink (Carta 1300/1200)`
  3. `Color E-Ink (Kaleido 3)`
  4. `Standard LCD / OLED (Color)`

### 3. 📚 Multi-Format Support
* **EPUB (v2 & v3)**: Clean XML/XHTML streaming parser, CSS style injection, hierarchical Table of Contents (TOC), and embedded image extraction.
* **Plain Text (.txt)**: Chunked window streaming for massive 100MB+ text files without out-of-memory errors. Automatic charset detection (UTF-8, EUC-KR, CP949).
* **PDF Documents**: Native hardware-accelerated PDF rendering engine, dual-page landscape mode, pinch-to-zoom, and thumbnail navigation.
* **Comics & Manga (.cbz, .zip)**: Fast non-blocking image decoding, aspect ratio fitting, and right-to-left (RTL) reading orientation support.

### 4. 🚀 High-Performance Paging Engine
* **Column-based Mathematical Text Layout**: Accurate page breaking calculated using native `StaticLayout`.
* **4-Chapter LRU Cache**: Pre-renders and caches adjacent chapters for instant, lag-free page turns.
* **Zero Memory Leaks**: Immediate bitmap deallocation (`recycle()`) and lifecycle-bound coroutine cancellation.

### 5. 🎧 Audio & Productivity Tools
* **Dual-Engine TTS (Text-to-Speech)**: Hands-free background reading with auto-scrolling synchronization and Bluetooth earphone/headset media button controls.
* **Custom Bionic Reading**: In-house reading velocity enhancement that bolds initial syllables without external third-party intellectual property constraints.
* **Reading Analytics**: Tracks cumulative reading time, calculated Words Per Minute (WPM), reading habit heatmaps, and progress meters.
* **SNS Quote Card Generator**: Export selected book highlights and typography into beautifully styled image cards.
* **Physical Hardware Key Binding**: Use device volume buttons or Bluetooth turners for seamless page flipping.

---

## 🛠️ Technology Stack

| Layer | Technology | Details |
| :--- | :--- | :--- |
| **Language** | Kotlin 1.9+ | Modern idioms, high-performance Coroutines & StateFlow |
| **Core Framework** | Android Native SDK (API 26+) | Pure Android Framework Views, `ViewBinding` |
| **Database** | Android Jetpack Room (KSP) | Fast local SQLite persistence with type-safe DAO |
| **Concurrency** | Kotlin Coroutines | Structured concurrency, custom Dispatchers |
| **Typography** | Android `StaticLayout` & `Paint` | Precise sub-pixel glyph positioning and line break math |
| **Build System** | Gradle 8.x + AGP | R8/ProGuard code shrinking, zero multi-dex overhead |

---

## 📁 Repository Structure

```text
Android e-book viewer/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/aethersaga/reader/
│   │   │   │   ├── data/             # Room DB, Entity, DAO, Repository
│   │   │   │   ├── engine/           # EPUB, TXT, PDF, CBZ parsers & PagingEngine
│   │   │   │   ├── service/          # Dual TTS Service & MediaSession controls
│   │   │   │   ├── ui/               # ReaderActivity, LibraryActivity, Custom Views
│   │   │   │   └── utils/            # E-Ink helpers, Bionic Reading, Encoding
│   │   │   └── res/                  # Layouts, vector drawables, themes, strings
│   │   └── test/                     # 50 Unit & Architecture tests (100% pass)
│   └── build.gradle.kts              # Minimal zero-heavy-lib configuration
├── dist/                             # Pre-built release APK (Ver. 1.0)
├── ARCHITECTURE.md                   # Complete architectural specification
├── FUNCTION.md                       # Comprehensive feature breakdown
├── RELEASE_NOTES.md                  # Release version history
├── README.md                         # English README (this file)
└── README_ko.md                      # Korean README (한국어 문서)
```

---

## 📦 Building from Source

### Prerequisites
* **Android Studio**: Ladybug / Hedgehog or newer
* **JDK**: OpenJDK 17 or higher
* **Android SDK**: API Level 34 (Target), API Level 26 (Minimum)

### Build Commands
Clone the repository and build using Gradle:

```bash
# Clone the repository
git clone https://github.com/jayjung1230-dot/AetherSAGA.git
cd AetherSAGA

# Run all 50 unit tests
./gradlew test

# Build Release APK (~650 KB)
./gradlew assembleRelease
```

The compiled release APK will be located at:
`app/build/outputs/apk/release/app-release-unsigned.apk` (or in the `dist/` folder).

---

## 📄 License

This project is licensed under the terms of the **Apache License 2.0**.  
See the [LICENSE](./LICENSE) file for full details.

---

<p align="center">
  Crafted with care for passionate readers, e-ink lovers, and minimalism advocates.<br>
  <strong>Aether SAGA © 2026 jayjung1230-dot</strong>
</p>
