# Aether SAGA – Architecture Overview

> **Version:** `Ver. 1.0` (Official Release • 2026-10-28)  
> *"Aether SAGA, journeys through endless stories"*  
> **Release Notes:** [RELEASE_NOTES.md](./RELEASE_NOTES.md)

## Layers (Clean Architecture + MVVM)

```
app/            UI (Activities, Compose-free Views), ViewModels, DI wiring (manual, no Hilt)
 └ presentation/  ReaderViewModel, LibraryViewModel, overlays (frontlight gesture, touch zones, 3D page curl)
domain/         Pure Kotlin: UseCases, models, repository interfaces, DisplayStrategy contracts
data/           Room DB, SAF/File scanner, WebDAV client, settings (SharedPreferences/proto-less)
engine/         Kotlin facade over JNI: PagingEngine, BitmapPool
native/ (C++)   libaether.so: container readers (EPUB/ZIP, TXT, MOBI), layout, FreeType glyph raster, MuPDF (PDF)
platform/       DisplayProfileManager, KeyEventDispatcher, FrontlightController, PowerManager hooks
```

Dependency rule: `app → domain ← data`, `engine`/`platform` implement domain interfaces. Domain has no Android imports.

## Display Strategy (Strategy Pattern)
`DisplayProfileManager.detect()` runs once at launch (and on config/display change) and picks one of
`MonoEInkStrategy`, `ColorEInkStrategy`, `LcdOledStrategy`. Detection signals:
system properties (vendor/board, e.g. `ro.product.manufacturer`, `ro.product.model`), `Display.supportedModes` refresh rates,
density/size (300 PPI + 6" => E-Ink, Crema Luna X: 1448x1072 Carta 1200), and a user override in settings. The strategy owns: page-transition style,
refresh policy, palette/post-processing, layout (single vs. dual column), allowed animations.

## Device Bifurcation Architecture (E-Ink vs. LCD/OLED)
H/W 특성에 맞춘 이원화 UX 설계를 통해 극단적인 반응성과 시인성을 보장합니다.

1. **페이지 전환 정책 (Instant Page Presentation & Zero-Animation Policy)**:
   - `전체 기기 공통`: 시각적 잔상/플리커 방지 및 특허 리스크(애플 3D Page Curl 등) 100% 원천 배제를 위해 3D 책장 넘김, 슬라이드, 스크롤 등 일체의 전환 애니메이션을 코드베이스에서 완전 삭제하고 순수 즉시 전환(`PageTransition.INSTANT`)으로 일원화.
2. **TTS 오디오 라우팅 (Speech Engine Routing)**:
   - `E-Ink`: 조용한 독서 공간에서의 돌발 소음 방지를 위해 블루투스 오디오 또는 유선 이어폰 연결 필수.
   - `LCD/OLED`: 기기 내장 스피커를 통한 다이렉트 낭독 허용.
3. **6인치 최적화 모달 다이얼로그 (Unified Modal Geometry, `showWithOptimalWidth`)**:
   - 설정, 조판, 화면/회전, 보조도구 대화상자 모두 균일한 규격(세로 기준 좌우 12% 여백, 상하 20% 여백, 2dp 고대비 테두리 박스) 안에서 렌더링되어 6인치 E-Ink 화면에서 일체감 있는 조작 환경 제공.
4. **Whispersync 다기기 독서 위치 동기화**:
   - `E-Ink`: 화면 깜빡임/불필요한 전체 리프레시 방지를 위해 제로 애니메이션 고대비 인스턴트 대화상자 (`showWithOptimalWidth`) 사용.
   - `LCD/OLED`: 상단 플로팅 알림 스낵바 카드 (부드러운 Slide-in/out 애니메이션, 8초 자동 숨김, 원클릭 이어읽기 점프 버튼).
5. **인라인 각주 팝업 (Inline Footnote Resolver)**:
   - `E-Ink`: 2dp 고대비 아웃라인, 인스턴트 노애니메이션 카드, 직관적인 닫기 '✕' 버튼.
   - `LCD/OLED`: 반투명 딤(Dim) 백드롭, 16dp 둥근 모서리 바텀시트, 드래그 제스처 핸들 바.
6. **타이포그래피 엔진 (Typography Pipeline)**:
   - `E-Ink`: Carta 1300/1200 흑백 패널의 시인성을 위해 외곽선 스트로크 부스터(`StrokeJoin.ROUND`, 0~10단계)와 순수 흑백 렌더링.
   - `공통/LCD`: 가변 폰트 OpenType `'wght'` (100~900) 정밀 웨이트 제어.
7. **상·하단 메뉴바 시각적 이원화 (Overlay Bar Theming)**:
   - `E-Ink`: 본문 텍스트와 메뉴 영역의 경계를 직관적으로 인지할 수 있도록 은은한 웜그레이 배경(`0xFFF0EEE9`)과 1dp 소프트 점선(Dashed Border, `dashWidth 4dp, dashGap 3dp`) 적용.
   - `LCD/OLED`: 기존의 단색 솔리드 테두리와 페이퍼 배경 룩앤필을 원본 그대로 유지.
8. **하드웨어 인식 기능 은닉 & 독서 테마 이원화 (Hardware Feature Masking & Reading Themes)**:
   - `LCD/OLED`:
     - 4대 독서 테마 탑재: 기본 화이트(`WHITE`), 눈 보호 웜톤/블루라이트 차단 세피아(`SEPIA`), 야간 차콜 다크(`DARK`), OLED 트루 블랙(`OLED_BLACK`).
     - E-Ink 전용인 화면 잔상 제거(Full Refresh) 설정 UI를 완전히 은닉.
   - `E-Ink`:
     - Carta 1300/1200 등 반사형 전자잉크 패널 특성에 맞게 소프트웨어 컬러 필터 및 세피아 틴트를 완전 차단하고 순수 종이/잉크 대비 보존.
     - 화면 잔상 제거(Full Refresh) 주기 설정(1~30페이지) 지원.
   - `바이오닉 리딩 영구 삭제`:
     - 프랑스 등록특허(FR3052587) 및 글로벌 상표(Bionic Reading®) 분쟁을 원천 방지하기 위해 바이오닉 리딩 기능을 전 기기 및 코드베이스에서 영구 삭제.
9. **디스플레이 모드 단순화 및 체크 표시 (`✓`) UI**:
   - 혼란을 야기하는 비호환 개별 기기 해상도 프리셋 목록을 걷어내고, `자동 감지(권장)`, `흑백 E-Ink`, `컬러 E-Ink`, `LCD/OLED` 4대 모드로 단순화.
   - 현재 활성화된 모드 및 테마 앞에 `✓` 체크마크를 표시하여 직관적인 설정 상태 확인 가능.
10. **기본 폰트 순수 유지 & 법적 무결성 타이포그래피 (Pure System Typography)**:
   - 서드파티 번들 폰트를 일체 배제하고 도서 내장 글꼴, 시스템 명조(System Serif), 시스템 고딕(System Sans) 중심 순수 폰트 환경 구축 (사용자 정의 외부 폰트 추가 지원).
11. **직관적인 인라인 리더 인터랙션 (Inline Reader Interactions)**:
   - 본문 하이라이트 클릭 시 끝점 우측 상단 앵커링을 통한 `[ ✎ 메모 ] | [ ✕ 삭제 ]` 복합 팝오버 표시 및 즉각적 영속화.
   - 북마크 등록 페이지 상단 우측 전용 벡터 리본(`BookmarkRibbonView`) 표시 및 원클릭 토글 해제.
12. **전자잉크 vs LCD/OLED 화면 조명 최적화 및 이원화 (E-Ink System Brightness & LCD Eye-Care)**:
   - `E-Ink`: Carta 흑백 명암비 저하를 막기 위해 소프트웨어 디밍 오버레이를 영구 비활성화하고, 단말기 전용 하드웨어 LED 프론트라이트와 연동되는 시스템 조명 모드로 고정하며 시스템 조명 설정 바로가기 인텐트 제공.
   - `LCD/OLED`: 0~100% 인앱 밝기 슬라이더, 앰버 색온도 필터, 4대 시력 보호 독서 테마 제공.
13. **독서 페이스 기반 완독 예상 시간 계산기 (Time-to-Finish Estimator)**:
   - 페이지 체류 시간(5~180초 유효 구간)을 지수 평활 가중치(`0.7 * 이전 + 0.3 * 현재`)로 실시간 추정하여 하단 푸터 및 스크러버에 남은 완독 시간 안내.
14. **핸즈프리 자동 책넘김 (Hands-Free Auto Page Turn)**:
   - 10~120초 범위 5초 단위 스테퍼 간격으로 자동 페이지 진행, 수동 조작 시 타이머 자동 리셋 및 완독 시 안전 종료.
15. **일일 독서 통계 트래커 (Daily Reading Stats Tracker)**:
   - 외부 서버 전송이 전무한 100% 로컬 프라이버시 보장 구조로 오늘 독서 시간(분)과 페이지 수를 측정하여 서재 헤더에 표출.
16. **주간/월간/연간 독서 통계 대시보드 (Multi-Period Stats Dashboard)**:
   - 외부 차트 라이브러리(MPAndroidChart)를 배제한 순수 Canvas 조판 엔진(`StatsLineChartView`) 탑재.
   - E-Ink 고대비 3px 실선/도트 마커 및 점선 그리드 vs LCD 은은한 영역 그라데이션 분기 렌더링.
   - Room DB `daily_reading_stats` 1일 1행 초경량 영속화 (연간 누적 수십 KB 미만).
17. **월별/연간 완독 도서 기록 영구 보존 (`CompletedBookEntity`)**:
   - Room DB `completed_books` 테이블을 통해 월별/연간 완독 권수와 완독 도서 제목/저자 목록을 관리.
   - 도서 원본 파일을 삭제하더라도 완독 독서 이력은 영구 보존되어 대시보드에서 언제든 조회 가능.
18. **하이라이트 영구 보관 & 도서 삭제 시 Yes/No 모달 (Highlight Persistence & Metadata Decoupling)**:
   - `HighlightEntity`에 `bookTitle`, `bookAuthor`를 영속화하여 책 파일 경로 종속성 제거 (DB Migration v3→v4).
   - 책장에서 도서 제거 시 하이라이트가 존재하면 `[하이라이트 보관 및 책 제거] (Yes)` vs `[하이라이트도 함께 삭제] (No)` 다이얼로그를 통해 데이터 손실을 원천 방지.
19. **하이라이트 ZIP 압축 패키징 및 이메일 전송 (Highlight ZIP Email Export)**:
   - `java.util.zip.ZipOutputStream` 기반 UTF-8 텍스트(.txt) 및 마크다운(.md) 통합 ZIP 파일 생성.
   - 안드로이드 표준 `ShareFileProvider` 및 `Intent.ACTION_SEND` (`application/zip`)를 통해 기기 내 이메일 앱으로 다이렉트 전송.
20. **PDF 잔상 3중 방지 & 3x3 터치(<850ms) vs 하이라이트(>=850ms) 시간 분리**:
   - 페이지 전환 시 `selectionRects` 오버레이 좌표 초기화, 스테이징 비트맵 버퍼 `eraseColor` 소거, E-Ink 하드웨어 리프레시(`FULL_INVERT`/`FULL_GC16`) 3중 연동.
   - 850ms 미만 터치는 3x3 터치존 구역 명령으로, 850ms 이상 길게 누름은 하이라이트 드래그 모드로 완벽 분리.
21. **SNS 독서 카드 공유, 카카오톡, 문자 메시지(SMS) 및 갤러리 이미지 저장 (Zero-SDK & Scoped Storage)**:
   - **독서 카드 이미지 갤러리 저장 (전 기기 공통)**: E-Ink 및 LCD/OLED 모든 기기에서 1080×1080 순수 Canvas 감성 독서 카드(인용구/메모/도서명/저자)를 기기 사진 보관함(`Pictures/AetherSAGA`)에 Scoped Storage(`MediaStore.Images.Media`)를 통해 무권한(No-Permission)으로 안전하게 직접 저장.
   - **카카오톡(KakaoTalk) 지원**: 공식 옐로우/브라운 브랜드 아이콘, 카카오톡 앱으로 독서 카드 다이렉트 전송 및 미설치 시 스토어 라우팅.
   - **문자 메시지(SMS/MMS) 연동**: `FEATURE_TELEPHONY` 감지 기반으로 통신 기능이 있는 기기에서 기본 문자 앱으로 카드 및 본문 전송 (문자 기능이 없는 E-Ink 기기에서는 원천 은닉).
   - **글로벌 SNS 지원**: Instagram, Threads, X, Facebook 공식 브랜드 아이콘 및 마켓 라우팅 (E-Ink 및 LCD/OLED 공통 지원).
   - **정밀한 하드웨어 이원화**: 카카오톡 및 문자 메시지는 LCD/OLED 및 셀룰러 기기 전용으로 동작하며, E-Ink 기기에서는 원천 은닉하여 최적화.
22. **주간 독서 도서 목록 및 진도율 서식 (Weekly Read Books & Progress Badge)**:
   - 주간 통계 탭에서도 최근 7일 내 읽은 도서 및 해당 기간 내 완독한 도서를 최신 열람순으로 집계.
   - 읽던 중인 도서는 `(읽고 있는 페이지 번호) / (전체 페이지 번호)` 포맷(PDF/CBZ 실제 페이지, EPUB/TXT 표준 조판 환산)으로 정확히 표출하며 완독 시 `완독` 배지 및 체크마크 표출.
23. **팝업 대화상자 2단 헤더 구조, 은은한 배경 딤(0.35f) 및 상시 스크롤바 (Universal Modal Architecture)**:
   - 모든 팝업 창에 은은한 배경 딤(0.35f)과 카드 드롭 섀도우(elevation)를 일괄 적용하여 시각적 안정성 확보.
   - 서브 메뉴 팝업 시 `[◀ 뒤로]`와 `[✕]`를 상단 1열에, 메뉴 제목을 하단 2열로 분리하여 조작 버튼과 제목 간 시각적 혼선 원천 방지.
   - 서재 메인 GridView 및 모든 스크롤 컨테이너에 상시 고대비 수직 스크롤바(`enableVisibleScrollbar`) 탑재로 탐색 위치 인지력 극대화.
   - 독서 보조도구 내 실효성 낮은 '실험적 기능' 토글 영구 삭제.
24. **초경량 이중 언어(한국어/영어) 및 시스템 로케일 자동 감지 아키텍처 (Bilingual Architecture)**:
   - `L10n.kt` 및 Android `Configuration` 기반 순수 인라인 다국어 처리로 외부 i18n 라이브러리 없이 0KB 메모리 오버헤드 달성.
   - 최초 실행 시 시스템 로케일 추종 원칙: 시스템 언어가 한국어(`ko`)인 경우 한국어, 그 외 모든 언어는 영어(`en`)로 자동 확정.
   - 설정 메뉴 내 오직 한국어(Korean)와 English(영어) 2대 언어 전용 옵션을 제공하며, 변경 즉시 영속화되어 다음 실행에서도 동일하게 유지.
   - `attachBaseContext` 및 `createConfigurationContext`를 통해 서재, 뷰어 툴바, 목차 및 통계 리포트 전반에 실시간 반영.
   - **전면적 영문화 (Full English Localization)**: 서재 책장, 검색, 일괄 삭제 모달, 태블릿 안내, 이메일 본문, 통계 대시보드(지표, 날짜 `MMM d, yyyy`, 완독도서 섹션), 북마크 토스트까지 잔여 한글 없이 완전 영문화.
25. **독서 노트 공유 SNS 확장 (Email & Social Media Note Sharing)**:
   - 기존의 이메일 공유 창(`showEmailShareDialog`) 및 도서 내보내기 메뉴에서 Instagram, Threads, X, Facebook 독서 카드 생성 모달로 즉시 연결하는 다이렉트 브릿지 버튼 제공.
26. **화면 표시 디테일 최적화 (Micro-Typography & Scrubber 2-Line UI)**:
   - 독서 진행 창(`scrubberLabel`)을 2줄로 분리하여 1행(페이지/진도율)과 2행(완독 예상 시간)의 시인성 극대화.
   - e-INK 기기 목차(TOC)에서 도서 정보 아래 가로 구분선은 양단 18dp 여백의 **직선**으로, 챕터들 사이 구분선은 `DashPathEffect` 양단 18dp 여백의 **옅은 점선**으로 이원화하여 가독성 극대화 (LCD/OLED는 실선 유지).
   - 독서 화면 최하단 좌우 상태바(챕터명, 진행률) 폰트 크기를 1PT 확대.
27. **E-Ink 고대비 설정 UI 및 옅은 점선 구분선 아키텍처 (High-Contrast Carta UI & Dotted Dividers)**:
   - Carta 1300/1200 16단계 패널에 최적화된 메뉴 카드(`ReaderTheme.EINK_CARD_BG`, 2px 차콜 외곽선 `#666666`) 및 내부 버튼(화이트 필 바탕, 2px 외곽선) 규격화.
   - 글자 스타일, 화면/조명, 독서 보조도구, 메인 환경설정 카드 내부의 모든 행 구분선에 `DottedDividerView`(`0xFF888888`, 1dp 스트로크, 3dp 대시/3dp 갭)를 적용하여 시각적 답답함을 해소하고 부드러운 계층감 완성.
   - `ReaderTheme.kt` 내 디자인 토큰 및 헬퍼 함수(`einkBtnBg`, `einkBtnBorder`, `einkStrokePx`)를 통한 100% UI 일관성 확보.
   - `PagingEngineImpl` 4구간 LRU 캐시(`layoutCache`) 및 비트맵 즉시 회수(`bitmap.recycle()`) 파이프라인으로 0ms 챕터 전환 및 1GB RAM 저사양 기기 메모리 안정성 달성.

## Memory & Performance Budget (2 GB device, ~600–800 MB free)
| Item | Budget |
|---|---|
| Page bitmap pool | 3 × (1448×1072) ALPHA_8 / RGB_565 ≈ 1.5 MB (mono) / 3.1 MB (color) each |
| Native layout chunk | 1 chapter at a time, streamed; arena allocator, freed on chapter swap |
| Cover cache | grayscale WebP on external SD, LruCache ≤ 8 MB in RAM |
| APK | < 2.3 MB (Debug unminified) / ~530 KB (Release R8 full mode), no Glide/Retrofit/Hilt |

Mono mode renders to `ALPHA_8` (8-bit gray) — a 4× saving vs ARGB_8888 and exactly what Carta 1200/1300 needs.

## Chunk Paging Pipeline
```
File ─► ContainerReader (zip central dir, lazy entry open)
     ─► ChapterStream (SAX/pull parse, never full DOM)
     ─► LayoutEngine (builds lines/pages into PageIndex for ONE chapter)
     ─► Rasterizer (FreeType + boldness booster) ─► BitmapPool slot
     ─► DisplayStrategy.present() (instant swap / 3D curl / refresh)
```
- `PageIndex` is stored as compact offsets (chapter, byte offset, run index) and persisted per (book, layout hash) in Room so reopening is instant.
- Prefetch: after showing page N, a single worker thread renders N+1 then N-1 into the pool; navigation beyond the window cancels stale jobs.
- Layout hash = font, size, spacing, margins, boldness, viewport → invalidates indexes.

## Threading
Main (input/present) · `layout` single thread (chapter parse/pagination) · `render` single thread (raster into pool) · `io` bounded pool (indexing, SD scanning, WebDAV). No coroutines Flow in hot path; coroutines only at the ViewModel boundary.

## Gradle footprint policy
Only: `androidx.core`, `androidx.appcompat`, `androidx.room` (runtime+ksp), `androidx.lifecycle-viewmodel`, `kotlinx-coroutines-android`. Native: FreeType, MuPDF (built via CMake, stripped, `-Os -flto`, `-ffunction-sections`).
