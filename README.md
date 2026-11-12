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
  <a href="./RELEASE_NOTES.md">Release Notes (v2.5.1)</a> •
  <a href="./ARCHITECTURE.md">Architecture Spec</a> •
  <a href="./FUNCTION.md">Functional Spec</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-2.5.1-blue.svg" alt="Version 2.5.1" />
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2026%2B)-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/APK%20Size-~1.9%20MB%20(with%2021--Diagram%20Guide)-success.svg" alt="APK Size" />
  <img src="https://img.shields.io/badge/Dependencies-Zero%20Heavy%20Libs-orange.svg" alt="Dependencies" />
  <img src="https://img.shields.io/badge/license-Apache%202.0-lightgrey.svg" alt="License" />
</p>

<p align="center">
  🏷️ <strong>Topics & Keywords:</strong>
  <code>ebook</code> • <code>e book</code> • <code>e-book</code> • <code>e-ink</code> • <code>reader</code> • <code>viewer</code> • <code>epub</code> • <code>foldable</code>
</p>

---

## 📖 Overview

**Aether SAGA** is an ultra-lightweight, battery-efficient Android reader engineered specifically for **E-Ink devices** (Carta 1200/1300 & Kaleido 3 color e-paper) as well as modern **Android smartphones, tablets, and foldables** (**Galaxy Z Fold / Fold Ultra / Galaxy Z Flip**).

Adhering to a strict **Zero Heavy Library Policy** (no Jetpack Compose, no Glide/Coil, no Retrofit/OkHttp, no AppCompat), Aether SAGA delivers lightning-fast cold startups, live fold/unfold responsive repagination, and butter-smooth page turns.

---

## 📊 E-Ink vs. LCD/OLED Feature Bifurcation Matrix

Aether SAGA automatically detects display panel physics (reflective e-paper vs. emissive LCD/OLED) and dynamically bifurcates features and UI:

| Feature Category | E-Ink Devices (Monochrome & Kaleido 3 Color) | Smartphones · Tablets · Foldables (LCD / OLED) |
| :--- | :--- | :--- |
| **Target Hardware & Profiles** | 6"~13.3" E-Ink readers (Carta 1200/1300 monochrome & Kaleido 3 4096-color optimized) | Standard Android phones/tablets, **Galaxy Z Fold / Fold Ultra**, **Galaxy Z Flip (Main 22:9 & Flex Window Cover)** |
| **Reading Color Themes** | **Pure Paper/Ink High-Contrast Locked** (blocks software color tints that degrade reflective contrast) | **4 Eye-Care Themes Supported** (`Natural White` / `Warm Sepia` / `Night Charcoal` / `OLED True Black`) |
| **Brightness & Lighting** | **Direct Hardware Frontlight (CTM) Integration** (eliminates muddy software dim overlays) | **0–100% In-App Brightness Slider** + **Warm Amber (Blue-Light Filter)** eye-protection overlay |
| **Anti-Ghosting & Refresh** | **1–30 Page Full-Screen Refresh Cycle** setting exposed + hardware key forced refresh | Refresh menu automatically hidden for zero-ghosting 60–120Hz LCD/OLED panels |
| **Typography Enhancement** | **10-Step Edge Stroke Booster & `ALPHA_8` Buffer** (razor-sharp ink outlines on e-paper) | **Subpixel Anti-Aliasing & OpenType Variable Weight** (smooth glyph rendering on high-DPI color screens) |
| **UI Borders & Scrollbars** | **2px High-Contrast Charcoal Outlines**, dotted dividers, persistent **7dp `EinkScrollBarView`** | Soft backdrop dim (`0.35f`), card elevation shadows, rounded bottom sheets & slim scrollbars |
| **Gemini AI Assistant (Beta)** | **Automatically Hidden on E-Ink** to preserve offline immersion and ultra-low battery consumption | **Dedicated Support**: 3-genre auto-detection (Literature, Tech Tutor, History), **in-app 1:1 multi-turn chat popup**, and Settings On/Off toggle |
| **Smart TTS Read-Aloud** | **Bluetooth/Headset Safety Gate** (prevents accidental speaker noise) + 3-step offline pitch (`0.8x/1.0x/1.2x`) | **Immediate Speaker/Earphone Playback** + **Voice Studio** (multi-speaker dialogue/narrator split & persona presets) |
| **SNS Card Sharing Channels** | **Gallery Save, KakaoTalk, Instagram, Threads, X, Facebook** (hides cellular SMS on Wi-Fi-only readers) | **Gallery Save, KakaoTalk, Instagram (1:1 Feed / 9:16 Story), Threads, X, Facebook, and SMS** |
| **Sync Notification UI** | Flicker-free **Instant High-Contrast Modal Dialog** for remote reading position jumps | Smooth top-down **Floating Snackbar Banner** (8-second auto-dismiss with 1-click jump action) |

---

## 🌟 Highlighted Special Features

### 📱 0. Foldable (Galaxy Z Fold / Ultra / Z Flip) Live Responsive Engine & 21-Diagram Official User Guide (Ver. 2.5)
* **Galaxy Z Fold (`2176×1812`), Fold Ultra (`2184×1968`) & Galaxy Z Flip (`2640×1080` Main & `748×720` / `512×260` Cover)**: Dedicated resolution profiles automatically adjust margins (`0.85x` compact on narrow cover/Flex Window screens, `1.1x` balanced on wide screens) and enable **2-Column (Dual-Page)** spread views on unfolded tablets.
* **Seamless Fold↔Unfold Live Repagination**: Folding or unfolding your device preserves 100% of your exact character offset (`currentRawOffset`) while dynamically reflowing text, high-res inline EPUB images (up to `2800px`), and CBZ/ZIP comic viewports without restarting the activity.
* **21-Diagram Bundled Official User Guide (`AetherSAGA_User_Guide.epub`)**: Every chapter and subsection (`<h2>`) of the built-in user guide includes a custom high-resolution infographic diagram (21 diagrams total + cover) explaining every feature visually.
* **Gemini AI Reading Assistant (Beta) In-App 1:1 Multi-Turn Chat**: Discuss books right inside the reader popup with 3-genre context detection (Literature, Tech Tutor, History) and toggle the feature On/Off anytime from both **Main Settings** and **In-Reader Settings**.

### 📚 1. Direct Main-Screen Bookshelf Workflows & Unified Share (Ver. 2.1)
Access core reading assets directly from your library shelf without having to open the reader:
* **Unified Reading Notes & Progress Share (`[📢 독서 기록 공유]`)**: Consolidated single sharing entrypoint in the library toolbar. Features a persistent `[✓] 📊 Book Info & Progress` option, bi-directional `[✓] Select All` checkbox synchronization, and Instagram Story 600-character dynamic budget with zero text truncation (`...`).
* **On-Demand Self-Healing Book Relinking**: If a book file is moved into a new subfolder within your library directory, tapping it from the shelf or reading notes triggers an instantaneous on-demand BFS search that self-heals Room DB paths and bookmarks seamlessly with zero battery drain.
* **Intelligent Book Sorting & Dotted Divider**: In the share dialog, books with notes, active progress, or completed status are sorted to the top, separated from unread books by a clean dotted divider (`DottedDividerView`) and high-contrast scrollbar.
* **Smart Expandable Highlight Viewer (`[🖍 독서 기록]`)**: Browse all notes and bookmarks across all books with **[더보기 ▾] / [접기 ▴]** toggles, permanent high-contrast scrollbar with optimized card right margin (`rightMargin = dp(10)`) preventing scrollbar overlap, and instant deep-linking back to the exact chapter and offset.
* **Book Metadata Enriched ZIP & Email Export**: Export highlights with distinct book source badges (`📖 《Title》(Author)`) in the checklist, and full book metadata headers across email body and TXT/Markdown documents, complete with Notion/Obsidian multi-book grouping.
* **Universal E-Ink Popup Outlines**: Guaranteed 2px high-contrast charcoal outlines and tactile button borders across all sharing and app-selection dialogs on e-paper.

### 🎨 2. Aesthetic SNS Quote Card & Stats Sharing
Transform your favorite reading moments into beautifully styled, high-resolution shareable image cards with a single tap:
* **Universal In-Book Highlight Tap Shortcut (`[📢 SNS 공유]`)**: Tapping any highlight in the reading view reveals an instant floating anchor (`[✎ Note] | [📢 Share] | [✕ Delete]`) for 1-click quote card generation on all devices (E-Ink & LCD/OLED).
* **Custom Visual Themes**: Export quotes in Paper Minimalist, Warm Amber, Dark Obsidian, or AMOLED Pitch Black styling.
* **Typographic Elegance & Cover Art**: Formatted with book title, author metadata, an impactful 175×248px rounded book cover artwork in the header, quotation marks, and optional user notes without text interference.
* **Smart Multi-Quote Social Dispatch (Story vs. Feed)**:
  * **Story Mode (Dynamic Proportional Layout, Modular Typography & Progress Auto-Fit)**: Automatically bundles selected highlights within a 600-character budget into a 9:16 vertical story card (1080×1920) for Instagram Stories, dynamically distributing vertical space in exact proportion to each note's length, featuring unified tag badges (`26~30px`) and proportional quotation marks (`48~80px`), hero progress typography (`36~58px`) with an infographic progress bar, and original book cover aspect ratios 100% preserved.
  * **Feed / Social Carousel**: Automatically generates individual 1:1 quote cards and dispatches multi-image carousels tailored to each platform's limit (up to 10 for Instagram/Facebook/Threads, up to 4 for X/Twitter) or saves all cards to your local gallery.
* **Rich Reading Milestone & Stats Sharing with "Currently Reading Books"**:
  * Share comprehensive infographics reflecting your real-time reading stats and live interactive trend charts/graphs.
  * **Integrated "Currently Reading Books" Card**: 1:1 square feed and 9:16 vertical story cards now cleanly embed your active book with rounded mini cover artwork (8px), book title, author, format pill badge (EPUB/PDF/CBZ/TXT), current page & recency info, an infographic progress bar with `%` badge, and companion reading books (`+ N more reading`).
  * Text and intent sharing automatically append `📖 Currently Reading: 《Title》(Author) (XX%)`.
  * Choose between **Instagram Story (9:16 vertical infographic report)** or **Instagram Feed (1:1 square card)**, or export to Twitter/X, social messengers, or local gallery with direct feed-launch and clean single-asset story support.

### 🔊 3. Smart TTS Read Aloud System with Live Sentence Highlighting (Ver. 2.1)
Transform books into seamless audiobooks with real-time visual tracking:
* **Floating TTS Bar Row 2 Quick Controls**: One-click `[ 📑 Current Chapter Only: ON/OFF ]` bedtime toggle and direct `[ 🎙️ Voice/Pitch Settings ]` button right on the floating bar.
* **Voice Studio Speaker Sample Previews (`🔊`)**: Offline audio previews for all voices, pitches (0.8x/1.0x/1.2x), and reading personas (Novel, News, Bedtime) without leaving the studio.
* **Universal Sleep Screen Cover On/Off Toggle**: Clear, high-visibility `[ OFF ○ ] ↔ [ ON ● ]` toggle switch accessible across all devices (defaults to OFF).
* **Library Continue Reading 100% Full-Width Track ProgressBar**: Dual-layer progress bar with a full-width light background track (with 1px crisp border on E-ink) showing 0% to 100% progress clearly across both E-ink and LCD while keeping `%` progress text intact. Upgraded the straight line to a true rounded progress bar with background track and clip progress fill while keeping `%` progress text intact.
Transform books into seamless audiobooks with real-time visual tracking:
* **Floating TTS Control Panel**: Floating non-intrusive control bar with Play/Pause (`▶`/`❚❚`), Stop (`⏹`), Sentence Back/Forward (`◀◀`/`▶▶`), and 6-level one-touch speed presets (**0.5x ~ 2.0x**).
* **Live Sentence Highlighting**: Real-time visual tracking on screen—soft warm amber highlight on LCD/OLED, crisp underline and 10% grayscale on E-Ink Carta.
* **Tap-to-Speak Sentence Jump**: Tap any sentence in the book while the TTS bar is up to immediately jump and read aloud from that exact point.
* **Zero Word-Severing Across Page Boundaries**: Segmented sentence pipeline delivers whole utterances to the TTS engine without cutting words mid-sentence, automatically turning pages smoothly.
* **Airtight Screen-On & Zero Screen Timeout**: Completely prevents device screen timeout, screensavers, and internal idle dimming via triple-protection pipeline (Window FLAG_KEEP_SCREEN_ON + View.keepScreenOn + PowerManager WakeLock) during playback.

### ⚡ 4. Bifurcation Display Architecture (Hardware-Aware)
A dual-rendering pipeline that adapts precisely to your display hardware:
* **E-Ink Mode (E-Ink 고해상도 & Kaleido 3)**:
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

### 🎴 6. Highlight Flashcards & Export Tools
* **Flashcard Spaced Repetition**: Turn highlights and notes into study flashcards with interactive flip animations.
* **ZIP & Email Export**: Export quotes and bookmarks into standard Markdown and TXT archives with full book metadata headers.

### 🏛️ 7. Feature Modularization & Extreme Speed/Stability Optimizations (Ver. 2.5.1)
* **Clean Feature Packages (`com.aether.reader.features.*`)**: Isolated modules for `features.stats` (StatsCardRenderer), `features.share` (QuoteCardRenderer, HighlightsZipExporter, DirectEmailSender), `features.sync` (WebDavSyncManager), and `features.tts` (TtsEngineController) with `typealias` backward compatibility.
* **0ms UI Thread Tab Switching**: Pre-computes page estimates on `Dispatchers.IO` and caches them in a `ConcurrentHashMap`, completely eliminating synchronous disk I/O on UI thread during stats tab switching.
* **6-Chapter Bounded LRU Cache**: Constrains `layoutCache` with `removeEldestEntry { size > 6 }` preventing OOM in multi-hour reading sessions.
* **Thread-Safe Bitmap Pools**: Guarded bitmap allocation, recycling, and swap routines preventing native crashes under rapid page flipping.
* **Single-Pass RGB_565 CoverCache**: Cuts bitmap allocation overhead in half by drawing scaled covers in a single canvas pass.
* **Composite SQLite Indexing**: Non-destructive indices (`index_books_lastRead`, `index_books_title`, `index_highlights_bookPath_chapter`) accelerate shelf sorting and highlight retrieval.
* **Scrollbar Gutter Standardization (10dp)**: Standardizes a clean 10dp right gutter across all scrollable modal dialogs (reading notes share, zip export, stats dashboard, Gemini AI, email share), completely eliminating scrollbar overlap with inner cards and input boxes.

---

## 📚 Core Reading Features (At a Glance)

| Feature | Capabilities |
| :--- | :--- |
| **Supported Formats** | **EPUB (v2/v3)** with TOC & CSS • **TXT** (streaming for 100MB+ files) • **PDF** (hardware-accelerated, dual-page mode) • **CBZ/ZIP** (manga right-to-left) |
| **Paging Engine** | Mathematical line breaking via native `StaticLayout` • **6-Chapter bounded LRU cache** for instant page turns • Zero memory leaks with aggressive bitmap recycling |
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
