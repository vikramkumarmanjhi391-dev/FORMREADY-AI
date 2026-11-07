# FORMREADY AI — Complete Workflow, Camera Intelligence & Premium UI/UX Re-Architecture Plan (Revised)

Transform FORMREADY AI into a genuinely intelligent, privacy-first, on-device government application document preparation suite with task-aware camera capture, on-device portrait face/segmentation processing with honest confidence semantics, separate Sauvola signature and 4-corner document vision pipelines, a dedicated full-screen Portal Preparation workflow, and a clean Material 3 UI designed for reliable operation on a Motorola Moto G60.

---

## User Review & Confirmed Decisions (Including Correction Round Updates)

> [!IMPORTANT]
> This revised plan incorporates all 9 mandatory corrections from the architecture review while preserving zero code modifications during Plan Mode.

- **Confirmed Decision 1 — On-Device Face & Portrait Segmentation Engine**: Use bundled **Google ML Kit Face Detection** (`com.google.mlkit:face-detection`) and **Google ML Kit Selfie Segmentation** (`com.google.mlkit:segmentation-selfie`) for local image processing, governed by explicit confidence/uncertainty semantics, accurate Google ML Kit Terms of Service disclosures, and before/after APK/AAB size measurements.
- **Confirmed Decision 2 — Portal Preparation Workflow Structure**: Replace the cramped `OneClickPreparationDialog` modal with a **Full-Screen Portal Detail & Per-Document Preparation Workflow** (`Portals List → Portal Detail Screen → Per-Document Prepare & Verify Screen`).
- **Confirmed Decision 3 — Photo & Signature Studio Controls Layout**: Use a **Clean Preview + Primary Action Layout with an Expandable `Adjust` Modal Bottom Sheet** for manual overrides (dimensions, DPI, lighting, ink threshold, framing, name/date strip).

---

## 1. Baseline Inspection & Existing Feature Audit (5-Tier Classification)

> [!NOTE]
> **Baseline Verification Requirement**: Never assume a fixed test count without executing the test suite on the current branch state. Before any Phase 1 code change begins, the build gate must inspect the active commit (`git rev-parse HEAD`), run `gradle :app:testDebugUnitTest :app:assembleDebug`, and record the exact baseline test count and APK size in bytes.

Each relevant capability in the current source tree has been audited and classified as:
1. **Fully implemented and tested**
2. **Partially implemented**
3. **Implemented using a fixed heuristic rather than real detection**
4. **Not implemented**
5. **Implemented but not reliably validated**

| Feature Area | Classification | Actual Code Reality & Reusable Components |
| :--- | :--- | :--- |
| **CameraX Lifecycle & `getCameraProvider()`** | **1. Fully implemented and tested** | `UniversalCameraController` uses `suspendCancellableCoroutine`, safe lens fallback, flash hardware detection, tap-to-focus metering, and temporary file cleanup. Reuse directly. |
| **Unified Media Input & EXIF Orientation** | **1. Fully implemented and tested** | `UnifiedMediaInput` + `ImageProcessor.decodeBitmapFromUri` / `decodeBitmapWithExif` validate magic bytes, apply `inSampleSize` bounds protection, and normalize all 8 EXIF orientations. Reuse directly. |
| **Post-Save Disk Compliance, DPI & Min/Max KB Verification** | **1. Fully implemented and tested** | `ImageProcessor.processImageForRule` verifies actual saved file bytes, dimensions, JFIF APP0 + EXIF DPI, PNG `pHYs` limitations, and `minKb > maxKb` conflicts without fake JPEG COM padding, while recycling intermediate bitmaps safely. Reuse directly. |
| **Encrypted Document Locker & Room Vault** | **1. Fully implemented and tested** | AES-GCM hardware-backed encryption, custom folders, search, and Vault Notes. Keep untouched. |
| **Multi-Page PDF Creation & Geometry-Preserving Compression** | **1. Fully implemented and tested** | `PdfProcessor` preserves per-page point dimensions and aspect ratios across compression steps and cleans up intermediate candidate files. Reuse directly. |
| **Camera Preview-to-Image Coordinate Mapping** | **3. Fixed heuristic** | `CameraCaptureMode.guideCropRectNorm` crops the full sensor image using hardcoded normalized percentages (`RectF(0.14f, 0.14f, 0.86f, 0.84f)`) without accounting for `PreviewView.ScaleType.FILL_CENTER`, sensor vs. display aspect-ratio differences, 0°/90°/180°/270° rotation, or front-camera horizontal mirroring. |
| **Camera Task Understanding (`CaptureIntent`)** | **2. Partially implemented** | `UniversalCameraScreen` accepts a `CameraCaptureMode` enum, but lacks a contextual `CaptureIntent` carrying portal `DocumentRule` parameters or a minimal task selector when opened generically. |
| **Portrait Face Detection & Auto-Framing** | **4. Not implemented** | No face detector is integrated; portrait cropping uses center-aspect cropping or fixed `guideCropRectNorm`. |
| **Portrait White Background Replacement & Verification** | **3. Fixed heuristic** | `applyAutoWhiteBackgroundAndFaceEnhance()` samples an 8% top/side border colour and whitens pixels outside a hardcoded head/shoulder ellipse. It is not real segmentation, and Phase 0.1 marks unmasked outputs as `BACKGROUND_UNVERIFIED`. |
| **Signature & Thumb Content Extraction** | **3. Fixed heuristic** | `adjustSignatureLighting()` and `cropBlankMargins()` use a single global luminance threshold (`inkThreshold = 180`), which fails under uneven paper shadows where local adaptive Sauvola thresholding + connected-stroke bounds are needed. |
| **Document 4-Corner Detection & Perspective Warp** | **4. Not implemented** | `applyMagicDocFilter()` adjusts contrast/whiteness, but angled documents and ID cards have no 4-corner quadrilateral detection, confidence scoring, interactive corner handles, or homography perspective correction. |
| **Image Skew / 90° Rotation Content Detection** | **3. Fixed heuristic** | `QualityInspector.inspectImageQuality()` guesses document type solely from width/height aspect ratio and cannot detect sideways/upside-down faces or skewed document edges. |
| **Portal-Specific Preparation Workflow** | **2. Partially implemented** | `PortalStudioScreen` opens `OneClickPreparationDialog` (a cramped `AlertDialog` that only launches the gallery picker, calls `ImageProcessor.processImageForRule` directly without document-specific camera/crop/segmentation pipelines, and lacks per-document preview). |
| **Portal Data Trust & Staleness Governance** | **5. Implemented but not reliably validated** | `VerificationStatus` exists, and PAN presets were downgraded in Phase 0, but `PortalSpec` lacks an auditable official-domain allowlist, notification number/cycle fields, and a non-destructive review-due policy. |
| **Portals List & Studio UI Minimalism** | **2. Partially implemented** | `PortalCard` is overcrowded with repeated document KB chips, source text, and verification dates; `PhotoStudioScreen` and `SignatureStudioScreen` permanently display every slider, dimension input, and promotional banner. |

---

## 2. Corrected Dependency, Model Size, Licensing & Privacy Analysis

### 2.1 Official ML Kit Model Size Estimates & Empirical Build Measurement
Per Google's official ML Kit Android documentation, the indicative download/bundle size impacts for the bundled vision libraries are:
- **Bundled ML Kit Face Detection** (`com.google.mlkit:face-detection:16.1.7`): **~6.9 MB** indicative model/native size impact.
- **ML Kit Selfie Segmentation** (`com.google.mlkit:segmentation-selfie:16.0.0-beta6`): **~4.5 MB** indicative model/native size impact.
- **Combined Indicative Download-Size Impact**: **~11.4 MB** (documentation estimate, not a guaranteed final APK or AAB size).

> [!WARNING]
> **No Unverified Size Claims**: We do **not** claim per-ABI size reductions or exact final binary sizes prior to building. During Phase 2, we will record the exact byte size of `app-debug.apk` (and release AAB/APK outputs) **before** and **after** adding the dependencies using `gradle :app:assembleDebug` and report the measured delta.

### 2.2 Separate Licensing & Privacy / Telemetry Governance

| Component / Dependency | Governing License / Terms | Image Privacy & Network Telemetry Behaviour |
| :--- | :--- | :--- |
| **Google ML Kit Face Detection & Selfie Segmentation SDKs** | **Google ML Kit Terms of Service** (`https://developers.google.com/ml-kit/terms`) — **NOT** labeled as wholly Apache 2.0 | All portrait face detection and foreground segmentation inference runs locally on the device CPU/GPU; **user photos and documents are never uploaded to Google or any cloud server**. However, per Google ML Kit Terms of Service, the ML Kit SDK may contact Google servers periodically for service configuration, compatibility checks, and operational/performance metrics. |
| **AndroidX CameraX, Compose, Room, Lifecycle, Coroutines, OkHttp, Moshi** | **Apache License 2.0** | Standard open-source libraries; no external network calls except user-initiated GitHub JSON preset sync. |
| **In-House Sauvola Signature Binarization & 4-Corner Homography Engine** | **Project Source (Kotlin)** | 100% offline deterministic array math; zero external dependencies, zero network activity, 0 KB model bloat. |

- **User-Facing Privacy & Terms Disclosure**: Update the in-app **About & Privacy** dialog (`MainScreen.kt`) to state clearly:
  1. All user photos, signatures, thumb impressions, and documents are processed and stored locally on the device and are never transmitted to any external server.
  2. Portrait detection and background segmentation use on-device Google ML Kit components governed by the Google ML Kit Terms of Service, which may exchange diagnostic/usage metrics and configuration metadata with Google services.

---

## 3. Motorola Moto G60 Physical Benchmark Protocol

All processing latency and memory targets remain **unverified estimates** until measured on physical Motorola Moto G60 hardware (Snapdragon 732G, 6 GB RAM, Android 12, 108 MP rear / 32 MP front camera). We will implement an on-device diagnostic benchmark logger (`VisionBenchmarkRecorder`) that records and exports structured timing and memory telemetry for physical Moto G60 verification:

1. **Cold vs. Warm Model Initialization**:
   - Measure `FaceDetection.getClient()` and `Segmentation.getClient()` first-call initialization (`coldInitMs`) vs. subsequent invocations (`warmInitMs`).
2. **Per-Stage Vision Latency (`median` and `p95` over $N = 20$ runs)**:
   - Face detection latency (`faceDetectMs`).
   - Single-image selfie segmentation + alpha feathering latency (`segmentationMs`).
   - Sauvola integral-image signature thresholding + connected-component bounds latency (`sauvolaMs`).
   - Document 4-corner detection + 3×3 homography bilinear warp latency (`docCornerWarpMs`).
3. **Total End-to-End Preparation Time (`median` and `p95`)**:
   - Wall-clock duration from `UnifiedMediaInput` decode → vision pipeline → rule resize/strip → stepped JPEG compression → post-save disk readback verification.
4. **Peak Memory & Large-Image (108 MP / 32 MP) OOM Resilience**:
   - Record Java heap delta (`Runtime.getRuntime().totalMemory() - freeMemory()`) and native heap allocation (`Debug.getNativeHeapAllocatedSize()`) before, at peak, and after bitmap recycling when decoding full-resolution Moto G60 108 MP (`12000×9000`) and 32 MP (`6528×4896`) captures.
5. **Sustained Thermal / Sequential Stress Test**:
   - Run 15 consecutive portrait + signature + document preparations back-to-back and compare Run 1–5 median latency against Run 11–15 median latency to detect thermal throttling or memory leaks.

---

## 4. Corrected Background Verification & Portrait Framing Design

### 4.1 Probabilistic Segmentation vs. Verified Background Compliance
A segmentation mask from ML Kit Selfie Segmentation is a **probabilistic foreground-confidence map** (`FloatBuffer` in `0.0f..1.0f`), **not** infallible proof that hair, ears, shoulders, or clothing boundaries were segmented without artifacts.

We explicitly separate five distinct signals in `BackgroundVerificationReport` and `ProcessedFileResult`:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                 5-SIGNAL BACKGROUND VERIFICATION PIPELINE                    │
│                                                                              │
│  1. segmentationExecuted: Boolean                                            │
│     └─ Did ML Kit Selfie Segmenter run and return a valid confidence map?    │
│                                                                              │
│  2. subjectMaskConfidence & edgeUncertaintyRatio                             │
│     ├─ Foreground area ratio within plausible portrait bounds (12%..82%)     │
│     ├─ Mean foreground confidence >= 0.82f on core subject                   │
│     └─ Edge uncertainty ratio = pixels in (0.20f..0.80f) / subject pixels    │
│        (Must be <= 0.18f; high ratio flags halo/boundary ambiguity)          │
│                                                                              │
│  3. backgroundReplacementApplied: Boolean                                    │
│     └─ Background pixels (< 0.20f) set to #FFFFFF; transition band           │
│        (0.20f..0.80f) smoothly alpha-composited onto #FFFFFF;                │
│        core subject (>= 0.80f) untouched.                                    │
│                                                                              │
│  4. outputBackgroundPixelChecksPassed: Boolean                               │
│     └─ Post-save decoded JPEG/PNG non-subject region (mask < 0.20f)          │
│        verified on disk: nearWhiteRatio >= 0.92f and meanLuminance >= 248.   │
│                                                                              │
│  5. userPreviewAccepted: Boolean                                             │
│     └─ Did the user review the segmented output preview (or accept it        │
│        without flagging boundary issues)?                                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **When `verifiedBackgroundCompliance` Is `true`**:
  - Requires **all** of: `segmentationExecuted == true`, `maskConfidencePassed == true` (plausible foreground area, high confidence, `edgeUncertaintyRatio <= 0.18f`), `outputBackgroundPixelChecksPassed == true` on the saved file on disk, **and** `userPreviewAccepted == true`.
- **When Segmentation Confidence Is Low or Ambiguous (`edgeUncertaintyRatio > 0.18f` or implausible foreground coverage)**:
  - `verifiedBackgroundCompliance` remains `false`, status is set to `PreparationStatus.BACKGROUND_UNVERIFIED` with a specific message (`"Background segmented with boundary uncertainty — please inspect hair/shoulder edges or adjust crop"`), and the UI offers one-tap **Manual Crop Adjustment** or **Keep Original Background**.

### 4.2 Adjustable Portrait Framing Defaults (No Fake Universal Rules)
- **Portal-Specific vs. Default Framing**:
  - `DocumentRule` adds optional `minHeadHeightRatio: Float? = null`, `maxHeadHeightRatio: Float? = null`, and `topHeadroomRatio: Float? = null`. These fields are populated **only** when an official portal notification explicitly specifies head-size percentages (e.g., Passport Seva / ICAO guidelines).
  - For portals that only specify pixel dimensions and aspect ratio (e.g., SSC, UPSC, BPSC), face-based auto-crop uses an **adjustable framing default** (`PortraitFramingProfile.DEFAULT_BALANCED`: target head height ~`0.58f`, top headroom ~`0.12f`) and always lets the user tweak the crop box interactively before saving.
- **Facial Identity Preservation**:
  - The app performs **zero** facial beautification, skin smoothing, landmark warping, or generative alteration.
  - Because cropping, bilinear scaling to target pixel dimensions (e.g., `200×230 px`), and JPEG compression mathematically resample pixels, we state accurately that **genuine facial identity and features are preserved without alteration**, without claiming literal pixel-for-pixel identity after resampling/encoding.

---

## 5. Proposed Architecture & End-to-End Workflows

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        USER ENTRY POINTS & WORKFLOWS                         │
│                                                                              │
│  ┌─────────────────────┐  ┌──────────────────────┐  ┌─────────────────────┐  │
│  │   Portals Workflow  │  │  Photo / Sign Studio │  │  Universal Camera   │  │
│  │  Portals List       │  │  Clean Preview       │  │  CaptureIntent      │  │
│  │   ↓                 │  │   + Primary Action   │  │   ├─ ForPortalRule  │  │
│  │  Portal Detail      │  │   + Adjust Sheet     │  │   ├─ ForStudio      │  │
│  │   ↓                 │  │                      │  │   └─ Generic Picker │  │
│  │  Per-Doc Prepare    │  │                      │  │                     │  │
│  └──────────┬──────────┘  └──────────┬───────────┘  └──────────┬──────────┘  │
└─────────────┼────────────────────────┼─────────────────────────┼─────────────┘
              │                        │                         │
              ▼                        ▼                         ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│              UNIFIED MEDIA INPUT & CAMERA VIEWPORT MAPPER                    │
│  • Magic-Byte + Bounded Decode + EXIF 8-Orientation Normalization            │
│  • PreviewView (FILL_CENTER) ↔ Sensor (4:3 / 16:9) + Rotation (0/90/180/270) │
│    + Front-Camera Mirroring Coordinate Transformation                        │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                ON-DEVICE INTELLIGENT VISION & ROUTING ENGINE                 │
│                                                                              │
│  ┌────────────────────────┐ ┌──────────────────────┐ ┌────────────────────┐  │
│  │   Portrait Pipeline    │ │  Signature / Thumb   │ │ Document / ID Card │  │
│  │         (Phase 2)      │ │     Pipeline (3A)    │ │    Pipeline (3B)   │  │
│  │ • ML Kit Face Detector │ │ • Integral-Image     │ │ • Sobel Gradient + │  │
│  │ • Adjustable Framing   │ │   Sauvola Adaptive   │ │   4-Corner Quad    │  │
│  │   + Manual Crop UI     │ │   Binarization       │ │   Confidence Score │  │
│  │ • ML Kit Selfie Mask   │ │ • Connected-Stroke   │ │ • Interactive 4-Pt │  │
│  │   + 5-Signal Confidence│ │   Bounding Box Crop  │ │   Corner Review UI │  │
│  │   & Preview Acceptance │ │ • Blue/Black Ink     │ │ • 3×3 Homography   │  │
│  │                        │ │   Chroma Preservation│ │   Perspective Warp │  │
│  └───────────┬────────────┘ └──────────┬───────────┘ └─────────┬──────────┘  │
└──────────────┼─────────────────────────┼───────────────────────┼─────────────┘
               │                         │                       │
               ▼                         ▼                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│            DETERMINISTIC RULE ENFORCER & POST-SAVE DISK VERIFIER             │
│  • Exact / Bounded Resize • Name & Date Strip • Stepped Quality Search       │
│  • Dual JFIF APP0 + EXIF DPI • 5-Signal Background Audit • PDF Geometry      │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Portal Data Trust & Official Domain Governance

1. **Auditable `OfficialDomainAllowlist`**:
   - An `https://` URL alone is never treated as proof of an official source.
   - `PortalVerificationValidator` checks `officialUrl` hostnames against an explicit allowlist of verified government and statutory agency domains (`upsconline.nic.in`, `upsc.gov.in`, `ssc.gov.in`, `onlinebpsc.bihar.gov.in`, `bpsc.bih.nic.in`, `rrbapply.gov.in`, `indianrailways.gov.in`, `ibps.in`, `sbi.co.in`, `ctet.nic.in`, `nta.ac.in`, `jeemain.nta.nic.in`, `neet.nta.nic.in`, `serviceonline.bihar.gov.in`, `passportindia.gov.in`, `tin.tin.proteantech.in`, `pan.utiitsl.com`, `uppbpb.gov.in`, `csbc.bih.nic.in`) and validates `.gov.in` / `.nic.in` ownership.
2. **Extended Provenance Metadata on `PortalSpec`**:
   - Store `notificationNumber: String? = null`, `recruitmentCycle: String = year`, `sourceDocumentTitle: String`, `officialUrl: String`, `sourceRevisionDate: String? = null`, and `lastVerifiedDate: String`.
   - A portal can display the green `OFFICIALLY_VERIFIED` badge **only** when its domain is in `OfficialDomainAllowlist`, `sourceDocumentTitle` and `lastVerifiedDate` are non-blank, and `verificationStatus == VerificationStatus.OFFICIALLY_VERIFIED`.
3. **Non-Destructive 180-Day Internal Review Policy**:
   - The 180-day check on `lastVerifiedDate` is strictly an **internal maintenance review signal** (`isReviewDue: Boolean`), **never** a claim that government rules automatically expire after 180 days.
   - If `lastVerifiedDate` is older than 180 days, the UI displays a subtle `"Verify Latest Notification"` reminder note in Portal Detail while **preserving all actual `DocumentRule` dimensions, DPI, and KB limits intact** so the user's document preparation is never blocked or altered.

---

## 7. Ordered Implementation Phases & Exact Files to Modify

### Phase 0 (Pre-Execution Gate): Baseline Branch & Test Suite Verification
- **Tasks**:
  - Inspect git status and current commit hash (`git rev-parse HEAD`).
  - Run `gradle :app:testDebugUnitTest :app:assembleDebug` and record the exact passing test count and baseline `app-debug.apk` byte size before touching any file.

### Phase 1: Camera Coordinate Mapping, `CaptureIntent` & Portal Provenance Governance
- **New Files**:
  - `app/src/main/java/com/example/camera/CaptureIntent.kt`
  - `app/src/main/java/com/example/camera/CameraViewportMapper.kt`
  - `app/src/main/java/com/example/data/portal/OfficialDomainAllowlist.kt`
- **Modified Files**:
  - `app/src/main/java/com/example/camera/UniversalCameraScreen.kt`
  - `app/src/main/java/com/example/camera/UniversalCameraController.kt`
  - `app/src/main/java/com/example/data/model/DocumentModels.kt`
  - `app/src/main/java/com/example/data/portal/PortalDatabase.kt`
  - `app/src/main/java/com/example/ui/MainScreen.kt` (Privacy & ML Kit Terms disclosure in About dialog)
- **Completion Criteria**:
  - `CameraViewportMapper` accurately transforms normalized guide rectangles from `PreviewView` (`FILL_CENTER`) to captured sensor coordinates across front/rear cameras, horizontal front-camera mirror differences, `0°`/`90°`/`180°`/`270°` rotations, and `4:3` / `16:9` / `20:9` aspect ratios.
  - `OfficialDomainAllowlist` and `PortalSpec` provenance validation tested with unit tests.

### Phase 2: On-Device Portrait Vision (ML Kit Face Detection + Selfie Segmentation + 5-Signal Verification)
- **Dependencies Added (`gradle/libs.versions.toml`, `app/build.gradle.kts`)**:
  - `com.google.mlkit:face-detection:16.1.7`
  - `com.google.mlkit:segmentation-selfie:16.0.0-beta6`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2` (or `suspendCancellableCoroutine` Task wrapper)
- **New Files**:
  - `app/src/main/java/com/example/processing/PortraitVisionProcessor.kt`
  - `app/src/main/java/com/example/processing/VisionBenchmarkRecorder.kt`
- **Modified Files**:
  - `gradle/libs.versions.toml`
  - `app/build.gradle.kts`
  - `app/src/main/java/com/example/data/model/DocumentModels.kt`
  - `app/src/main/java/com/example/processing/ImageProcessor.kt`
  - `app/src/main/java/com/example/processing/OneClickPreparationCoordinator.kt`
- **Completion Criteria**:
  - Record exact `app-debug.apk` byte size before and after adding ML Kit dependencies.
  - `PortraitVisionProcessor` detects face bounds/pose, computes adjustable portrait crop (`PortraitFramingProfile`), runs Selfie Segmentation, computes `subjectMaskConfidence` and `edgeUncertaintyRatio`, composites onto `#FFFFFF`, and enforces the 5-signal background compliance model.

### Phase 3A: Signature & Thumb Impression Vision (Integral-Image Sauvola & Ink Bounds)
- **New Files**:
  - `app/src/main/java/com/example/processing/SignatureVisionProcessor.kt`
- **Modified Files**:
  - `app/src/main/java/com/example/processing/ImageProcessor.kt`
  - `app/src/main/java/com/example/processing/OneClickPreparationCoordinator.kt`
- **Completion Criteria**:
  - Integral-image Sauvola local adaptive thresholding removes uneven mobile shadows while preserving blue/black pen ink chroma and extracting tight connected-stroke bounding boxes with safe padding.
  - Independent fallback to gentle contrast lighting if stroke area is below minimum threshold.

### Phase 3B: Document & ID Card 4-Corner Detection, Interactive Handles & Homography Warp
- **New Files**:
  - `app/src/main/java/com/example/processing/DocumentCornerProcessor.kt`
  - `app/src/main/java/com/example/ui/components/InteractiveCornerCropOverlay.kt`
- **Modified Files**:
  - `app/src/main/java/com/example/processing/OneClickPreparationCoordinator.kt`
  - `app/src/main/java/com/example/ui/screens/PdfMasterScreen.kt`
- **Completion Criteria**:
  - Sobel edge + contour quadrilateral detector finds 4 document corners with a normalized confidence score (`0.0f..1.0f`).
  - Low-confidence detections or user tap open `InteractiveCornerCropOverlay` with draggable corner magnifiers before executing the 3×3 homography bilinear perspective warp.

### Phase 4: Full-Screen Portal Preparation Workflow (`Portals List → Portal Detail → Per-Document Prepare`)
- **New Files**:
  - `app/src/main/java/com/example/ui/screens/PortalDetailScreen.kt`
  - `app/src/main/java/com/example/ui/screens/PortalDocumentPrepareScreen.kt`
- **Modified Files**:
  - `app/src/main/java/com/example/ui/screens/PortalStudioScreen.kt`
  - `app/src/main/java/com/example/ui/components/CommonComponents.kt`
  - `app/src/main/java/com/example/ui/MainScreen.kt`
- **Completion Criteria**:
  - `PortalStudioScreen` redesigned to match `HomeDashboardScreen`'s clean hierarchy (Title, single-line compact search, non-truncated `"All"` filter chip, compact `PortalCard` with Name, Authority, truthful `StatusBadge`, and single entry action).
  - Full-screen `PortalDetailScreen` and `PortalDocumentPrepareScreen` route Photo, Signature, Thumb, and ID/PDF rules to their dedicated vision pipelines with preview acceptance and disk verification.

### Phase 5: Minimalist Photo & Signature Studio Redesign (`Adjust` Modal Bottom Sheet) & Full Regression Suite
- **New Files**:
  - `app/src/main/java/com/example/ui/components/StudioAdjustBottomSheet.kt`
- **Modified Files**:
  - `app/src/main/java/com/example/ui/screens/PhotoStudioScreen.kt`
  - `app/src/main/java/com/example/ui/screens/SignatureStudioScreen.kt`
  - `app/src/main/java/com/example/ui/components/PortalPresetSelectorBar.kt`
- **Completion Criteria**:
  - `PhotoStudioScreen` and `SignatureStudioScreen` present a single capture/upload action bar, large preview (with interactive crop/preview acceptance), compact verified output pill, primary Save action, and an `Adjust` modal bottom sheet for manual controls.
  - Repetitive promotional copy removed across all screens.

---

## 8. Comprehensive Testing Strategy (Unit, Compose UI & Moto G60 Checklist)

### 8.1 Automated Unit & Robolectric Tests (`gradle :app:testDebugUnitTest`)
1. **Camera Coordinate Mapping Suite (`CameraViewportMapperTest`)**:
   - Rear camera (`LENS_FACING_BACK`) and front camera (`LENS_FACING_FRONT` with horizontal preview mirroring vs. saved image).
   - Display/target rotations `0°`, `90°`, `180°`, and `270°`.
   - `PreviewView.ScaleType.FILL_CENTER` viewport crop across `4:3` sensor (`4000×3000`) on `20:9` display (`1080×2400`) and `16:9` display (`1080×1920`).
   - Verification against real CameraX `ViewPort` crop-rect geometry and synthetic captured sensor frames with fiducial markers at the exact guide rectangle corners.
2. **Portrait Framing & 5-Signal Background Verification Suite (`PortraitVisionProcessorTest`)**:
   - Straight, tilted, off-center, and EXIF-rotated portraits.
   - High-confidence segmentation mask + verified near-white disk pixels + `userPreviewAccepted = true` → `verifiedBackgroundCompliance = true`.
   - High-confidence segmentation mask + `userPreviewAccepted = false` → `verifiedBackgroundCompliance = false`.
   - Ambiguous hair/shoulder boundary (`edgeUncertaintyRatio > 0.18f`) or implausible foreground coverage → `verifiedBackgroundCompliance = false`, status `BACKGROUND_UNVERIFIED` with honest edge-uncertainty warning.
   - Portal rule with explicit `minHeadHeightRatio`/`maxHeadHeightRatio` vs. adjustable default profile (`DEFAULT_BALANCED`).
3. **Phase 3A Signature & Thumb Vision Suite (`SignatureVisionProcessorTest`)**:
   - Faint blue and black ink strokes under simulated linear/radial illumination shadows.
   - Connected-stroke bounding box extraction ignoring isolated 1–2 px dust specks near page edges.
   - Fallback to gentle contrast lighting when image contains no discernible ink strokes.
4. **Phase 3B Document 4-Corner & Homography Suite (`DocumentCornerProcessorTest`)**:
   - Synthetic angled ID card and A4 page quadrilaterals (`15°–30°` perspective skew) detected and rectified via 3×3 homography into target aspect ratio.
   - Low-confidence background clutter triggering manual 4-corner confirmation state instead of auto-warping random edges.
5. **Portal Governance & Compose UI Workflow Suite (`PortalWorkflowTest`)**:
   - `OfficialDomainAllowlist` rejecting non-official `https://example.com` URLs from displaying `OFFICIALLY_VERIFIED`.
   - 180-day `isReviewDue` flag surfacing a review reminder without altering or blocking `DocumentRule` processing.
   - Compose UI tests verifying `"All"` filter chip text visibility, compact `PortalCard` layout, navigation to `PortalDetailScreen` and `PortalDocumentPrepareScreen`, and `Adjust` bottom sheet opening.
6. **Phase 0.1 Regression Suite (`Phase5FeaturesTest` & existing suites)**:
   - Full preservation of all existing unit tests for `minKb > maxKb` conflicts, below-min KB failure without COM padding, JPEG JFIF+EXIF vs. PNG `pHYs` DPI validation, PDF page geometry preservation, and caller `Bitmap` lifecycle safety.

### 8.2 Manual Acceptance-Test Protocol for Physical Motorola Moto G60
> [!NOTE]
> Hardware testing is never claimed in container builds. Execute this checklist on a physical Motorola Moto G60 using the built APK:
1. Run `VisionBenchmarkRecorder` on Moto G60 with 108 MP rear and 32 MP front captures; record cold/warm init, median/p95 latencies, and peak heap usage.
2. Verify front-camera selfie capture inside the oval guide matches the saved crop orientation and horizontal handedness without unexpected mirroring.
3. Test ML Kit Selfie Segmentation against a textured indoor wall/curtain; verify edge uncertainty warnings appear when lighting is poor and preview confirmation is required before marking background verified.
4. Test Sauvola signature extraction on a paper signature half-covered by a phone shadow.
5. Test 4-corner detection and interactive corner dragging on an angled Aadhaar/PAN card.
6. Complete an end-to-end UPSC and SSC application package from the redesigned `Portals` tab and re-open all saved files from device storage to confirm dimensions, KB size, and DPI.

---

## 9. Risks, Regressions & Fallback Behaviour

| Risk | Likelihood / Impact | Concrete Mitigation & Honest Fallback |
| :--- | :--- | :--- |
| **ML Kit Native JNI Unavailability in Host JVM Unit Tests** | High (Expected in Robolectric) | Define clean `FaceDetectionEngine` and `SelfieSegmentationEngine` interfaces; use `MlKitFaceDetectionEngine` / `MlKitSelfieSegmentationEngine` on device and deterministic confidence-map test doubles in JVM unit tests, while testing all post-mask alpha-matting, uncertainty math, and disk verification with real bitmaps. |
| **Low Segmentation Confidence on Complex Hair / Backlit Scenes** | Medium | Compute `edgeUncertaintyRatio` over the `(0.20f..0.80f)` confidence band; if `> 0.18f`, never claim `verifiedBackgroundCompliance = true`—surface an honest warning and allow one-tap switch to `Keep Original Background` or manual crop. |
| **False 4-Corner Detection on Textured Tables** | Medium | Score candidate quadrilaterals by edge gradient strength, convexity, internal angles (`45°..135°`), and area (`>= 18%` of frame). Whenever confidence `< 0.75f`, default to the interactive 4-corner drag overlay rather than silently warping. |
| **Memory Spikes on 108 MP Moto G60 Rear Captures** | High if unbounded | Always decode via `inSampleSize` capped at `2048 px` for master bitmap and `1024 px` for vision analysis, and recycle intermediate bitmaps immediately via `replaceIntermediateBitmap()` in `try/finally` blocks. |
