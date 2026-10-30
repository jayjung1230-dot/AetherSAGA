# Aether SAGA - journeys through endless stories

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Aether SAGA Logo" width="110" height="110" />
</p>

<p align="center">
  <strong>Ultra-lightweight, high-performance e-book & comic reader engineered for E-Ink devices and Android.</strong><br>
  <em>"Aether SAGA, journeys through endless stories"</em>
</p>

<p align="center">
  <a href="./README_ko.md">🇰🇷 한국어 설명서 (Korean)</a> •
  <a href="./RELEASE_NOTES.md">Release Notes (v1.1)</a> •
  <a href="./ARCHITECTURE.md">Architecture Spec</a> •
  <a href="./FUNCTION.md">Functional Spec</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.1.0-blue.svg" alt="Version 1.1.0" />
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2026%2B)-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/APK%20Size-~640%20KB-success.svg" alt="APK Size" />
  <img src="https://img.shields.io/badge/Dependencies-Zero%20Heavy%20Libs-orange.svg" alt="Dependencies" />
  <img src="https://img.shields.io/badge/license-Apache%202.0-lightgrey.svg" alt="License" />
</p>

<p align="center">
  🏷️ <strong>Topics & Keywords:</strong>
  <code>ebook</code> • <code>e book</code> • <code>e-book</code> • <code>e-ink</code> • <code>reader</code> • <code>viewer</code> • <code>epub</code>
</p>

---

## 📖 Overview

**Aether SAGA** is an ultra-lightweight, battery-efficient Android reader engineered specifically for **E-Ink devices** (Crema Luna X 6.0", Onyx Boox series) as well as modern **Android tablets and smartphones**.

Adhering to a strict **Zero Heavy Library Policy** (no Jetpack Compose, no Glide/Coil, no Retrofit/OkHttp, no AppCompat), Aether SAGA delivers lightning-fast cold startups and butter-smooth page turns within a release APK of just **~640 KB**.

---

## 🌟 Highlighted Special Features

### 📚 1. Direct Main-Screen Bookshelf Workflows (New in v1.1)
Access core reading assets directly from your library shelf without having to open the reader:
* **One-Click Reading Card & SNS Sharing**: Produce stylized 1080×1080 quote cards and reading streak graphics directly from the main screen `[📷 독서카드/SNS]` chip.
* **Targeted Reading Notes Export**: Select any book in your library via `[📢 독서노트 공유]` to compile, package, and share its highlights as TXT, Markdown, or zipped archives via email or messenger.
* **Smart Expandable Highlight Viewer**: Browse all notes and bookmarks across all books via `[🖍 독서 기록]`. Features **[더보기 ▾] / [접기 ▴]** toggles for long quotes, a high-contrast permanent scrollbar, and instant deep-linking back to the exact chapter and character offset in the book.
* **Universal E-Ink Popup Outlines**: Guaranteed 2px high-contrast charcoal outlines and tactile button borders across all sharing and app-selection dialogs on e-paper.

### 🎨 2. Aesthetic SNS Quote Card & Stats Sharing
Transform your favorite reading moments into beautifully styled, high-resolution shareable image cards with a single tap:
* **Custom Visual Themes**: Export quotes in Paper Minimalist, Warm Amber, Dark Obsidian, or AMOLED Pitch Black styling.
* **Typographic Elegance**: Formatted with book title, author metadata, quotation marks, and optional user notes.
* **Reading Milestone Cards**: Share your reading streaks (🔥), total reading hours, weekly books completed, and habit trends directly to **Instagram Stories, Twitter/X, social messengers**, or save directly to your gallery.

### ⚡ 3. Bifurcation Display Architecture (Hardware-Aware)
A dual-rendering pipeline that adapts precisely to your display hardware:
* **E-Ink Mode (Carta 1300/1200 & Kaleido 3)**:
  * Eliminates software color filters and battery-draining animations to protect raw e-paper contrast.
  * Replaces flat UI borders with specialized **anti-ghosting dotted dividers** (`DashPathEffect([3dp, 3dp])`).
  * Configurable full-screen flash refresh (**1–30 pages**) to wipe residual e-ink particles.
  * 10-level edge sharpness stroke booster and frontlight brightness/warmth controls.
* **LCD / OLED Mode**:
  * 7 immersive eye-care themes (Pure White, Warm Amber, Paper Cream, Mint Breeze, Lavender Fog, Deep Charcoal, AMOLED Pitch Black) with adjustable night filters.

### 📊 4. Native Reading Analytics & Habit Heatmap
* Built-in, zero-dependency **pure Canvas line chart engine** (weekly, monthly, annual reading trends).
* Real-time **Words Per Minute (WPM)** reading speed calculator, estimated remaining reading time, and reading progress scrubbers.
* Lifetime reading statistics permanently retained even when book files are removed.

### 🎧 5. Dual-Engine TTS & Hands-Free Audio Controls
* Background text-to-speech engine with synchronized sentence auto-scrolling.
* Full **Bluetooth earphone & headset media key integration** (play, pause, next/previous sentence) for comfortable hands-free listening.

### 🎴 6. Highlight Flashcards & Custom Bionic Reading
* **Flashcard Spaced Repetition**: Turn highlights and notes into study flashcards with interactive flip animations.
* **Proprietary Bionic Reading**: In-house fixation enhancer that bolds initial syllables without external third-party intellectual property constraints.

---

## 📚 Core Reading Features (At a Glance)

| Feature | Capabilities |
| :--- | :--- |
| **Supported Formats** | **EPUB (v2/v3)** with TOC & CSS • **TXT** (streaming for 100MB+ files) • **PDF** (hardware-accelerated, dual-page mode) • **CBZ/ZIP** (manga right-to-left) |
| **Paging Engine** | Mathematical line breaking via native `StaticLayout` • **4-Chapter LRU cache** for instant page turns • Zero memory leaks with aggressive bitmap recycling |
| **Typography & UI** | 8pt–32pt font scaling, custom TTF/OTF font loader, margin/line-spacing tuning, 3×3 customizable touch zones, and physical volume key page flipping |
| **Annotation Tools** | Multi-color highlights, inline footnote popovers, quick bookmarks, and exportable reading journals |

---

## 🛠️ Technology Stack

* **Language & Concurrency**: Kotlin 1.9+, Coroutines & StateFlow (Structured Concurrency)
* **UI Framework**: Pure Android Native Framework Views (`ViewBinding`), zero heavy UI toolkits
* **Local Persistence**: Android Jetpack Room (KSP) with SQLite
* **Typography Engine**: Sub-pixel `StaticLayout` & hardware `Paint`
* **Distribution Package**: Uncompressed ~640 KB standalone APK, zero multi-dex overhead

---

## 📦 Building from Source

```bash
# Clone the repository
git clone https://github.com/jayjung1230-dot/AetherSAGA.git
cd AetherSAGA

# Run the 50 unit tests
./gradlew test

# Assemble Release APK (~640 KB)
./gradlew assembleRelease
```

---

## 📄 License

This project is licensed under the terms of the **Apache License 2.0**.  
See the [LICENSE](./LICENSE) file for details.

<p align="center">
  Crafted with care for passionate readers, e-ink lovers, and minimalism advocates.<br>
  <strong>Aether SAGA © 2026 jayjung1230-dot</strong>
</p>
