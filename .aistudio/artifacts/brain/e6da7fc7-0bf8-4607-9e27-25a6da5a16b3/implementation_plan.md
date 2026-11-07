# FORMREADY AI — Complete Workflow, Camera Intelligence & Premium UI/UX Re-Architecture Plan (Final Corrected Revision)

**Plan Status**: `PENDING FINAL USER REVIEW & APPROVAL` (Not Automatically Approved)

Transform FORMREADY AI into an on-device government application document preparation suite with calibrated camera viewport mapping, replaceable portrait face-detection and segmentation interfaces backed initially by bundled Google ML Kit, separate Sauvola signature and 4-corner document vision pipelines, a full-screen Portal Preparation workflow, and a clean Material 3 Studio experience with honest verification semantics.

---

## A. Correction Audit: Previous Incorrect Claims vs. Corrected Replacements

| # | Area | Previous Incorrect Claim / Assumption | Corrected Technical Replacement in This Plan |
| :--- | :--- | :--- | :--- |
| **1** | **ML Kit Dependency & APK Size Analysis** | Claimed Face Detection was ~2.4 MB per ABI, Selfie Segmentation was ~0.6 MB, and total bundled impact was ~3 MB; conflated SDK size, download size estimate, and APK/AAB sizes. | Uses Google's official Android ML Kit documentation estimates for bundled model download-size impact (**~6.9 MB** for `com.google.mlkit:face-detection`, **~4.5 MB** for `com.google.mlkit:segmentation-selfie`, **~11.4 MB** combined indicative download impact). Explicitly separates SDK wrapper size, bundled model download-size impact, Debug APK size, Release APK/AAB size, and per-ABI split size, marking all unmeasured build outputs as **Pending Actual Build Measurement**. |
| **2** | **Motorola Moto G60 Performance Claims** | Presented fixed millisecond latency numbers (e.g., `< 200 ms`, `< 1.2 s`) as expected Moto G60 performance without hardware measurements. | Removes all unmeasured latency claims. Defines a physical Motorola Moto G60 benchmark protocol measuring cold/warm model initialization, face detection, single-image segmentation, end-to-end preparation, peak Java/native RAM, 108 MP / 32 MP large-image handling, and 15-run repeated thermal stability, requiring actual **median** and **p95** device measurements. |
| **3** | **Background Verification Semantics** | Treated the existence of a segmentation mask plus white background replacement as sufficient to mark `verifiedBackgroundCompliance = true`. | Separates 6 distinct stages: (1) model inference execution, (2) core subject mask confidence, (3) boundary/edge uncertainty ratio in `(0.20..0.80)`, (4) white background replacement execution, (5) post-save decoded disk pixel validation, and (6) explicit user preview acceptance. Marks uncertain edges or unaccepted previews as `BACKGROUND_UNVERIFIED` with an honest warning and manual correction tools. |
| **4** | **Licensing & Privacy / Telemetry** | Described the ML Kit SDK/models under Apache 2.0 and claimed zero network communication with Google servers. | Documents **Google ML Kit Terms of Service** (`https://developers.google.com/ml-kit/terms`) separately from Apache 2.0 open-source dependencies. Preserves 100% local on-device image/document processing with **no cloud upload of user photos or documents**, while accurately disclosing that the ML Kit SDK may contact Google servers for service configuration, compatibility checks, and operational/performance telemetry. |
| **5** | **Portrait Face Crop Rules & Identity** | Treated `50%–70%` head-height and `8%–14%` top headroom as universal government portal rules, and claimed literal pixel-perfect facial preservation after resizing/compression. | Applies portal-specific head/headroom ratios **only** when verified from an official portal notification (`minHeadHeightRatio`, `maxHeadHeightRatio`, `topHeadroomRatio` on `DocumentRule`). Otherwise uses configurable framing defaults (`PortraitFramingProfile.DEFAULT_BALANCED`) with interactive manual crop controls. Preserves genuine facial identity (zero beautification, smoothing, or generative warping) without claiming literal pixel-for-pixel identity after resampling and JPEG encoding. |
| **6** | **Portal Verification Governance (180-Day Rule)** | Treated 180 days since `lastVerifiedDate` as an automatic expiry or invalidation of a portal's official requirements. | Treats the 180-day threshold strictly as a **non-destructive internal review trigger** (`isInternalReviewDue = true`). Validates official provenance using an auditable domain allowlist, notification title/number, recruitment cycle, source publication/revision date, last checked date, and rule conflict checks. Older check dates trigger a review reminder without invalidating or blocking the portal's `DocumentRule` specifications. |
| **7** | **Phase 3 Monolithic Scope** | Combined Signature/Thumb Sauvola binarization and Document 4-corner homography into a single Phase 3. | Splits into two independently testable phases: **Phase 3A** (Signature & Thumb — integral-image Sauvola adaptive thresholding, blue/black ink chroma preservation, connected-stroke bounding box) and **Phase 3B** (Documents & IDs — Sobel gradient 4-corner detection, quadrilateral confidence scoring, interactive 4-corner drag overlay, and 3×3 homography perspective warp). |
| **8** | **Repository Baseline & Test Count Verification** | Relied on a previously written commit SHA and assumed 58 passing tests without re-inspecting the active container environment. | Re-inspected the actual workspace directly: verified that `.git` metadata is not mounted in the container (`fatal: not a git repository`), counted the exact `@Test` annotations across all 8 test classes on disk (**58 `@Test` methods total**), and preserved all Phase 0.1 fixes (strict `minKb > maxKb` conflict detection, no fake JPEG COM padding, dual JFIF+EXIF vs. PNG `pHYs` DPI verification, PDF geometry preservation, and safe `Bitmap` ownership). |
| **9** | **Approved Product & UX Direction** | Risk of unnecessary scope drift during corrections. | Retains all approved architectural and UX decisions: bundled ML Kit Face Detection & Selfie Segmentation behind replaceable engine interfaces, full-screen `Portal Detail` and `Per-Document Prepare` screens, clean Photo/Signature Studio preview with `Adjust` modal bottom sheet, task-aware `CaptureIntent`, separate Photo/Signature/Thumb/Document pipelines, and offline-first local document processing. |

---

## B. Complete Revised Architecture and Data Flow

### B.1 System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                        USER ENTRY POINTS & WORKFLOWS                           │
│                                                                                │
│  ┌───────────────────────┐  ┌──────────────────────┐  ┌─────────────────────┐  │
│  │    Portals Workflow   │  │  Photo / Sign Studio │  │  Universal Camera   │  │
│  │  Compact Portals List │  │  Clean Large Preview │  │  CaptureIntent      │  │
│  │          │            │  │   + Primary Action   │  │   ├─ ForPortalRule  │  │
│  │          ▼            │  │   + Preview Confirm  │  │   ├─ ForStudio      │  │
│  │  Portal Detail Screen │  │   + Adjust Modal     │  │   └─ Generic (4-Tab │  │
│  │          │            │  │     Bottom Sheet     │  │      Task Selector) │  │
│  │          ▼            │  │                      │  │                     │  │
│  │  Per-Document Prepare │  │                      │  │                     │  │
│  └──────────┬────────────┘  └──────────┬───────────┘  └──────────┬──────────┘  │
└─────────────┼──────────────────────────┼─────────────────────────┼─────────────┘
              │                          │                         │
              ▼                          ▼                         ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│              UNIFIED MEDIA INPUT & CAMERA VIEWPORT MAPPER                      │
│  • Magic-Byte Validation + Bounded Decode (inSampleSize) + EXIF 8-Orientation  │
│  • CameraViewportMapper: PreviewView (FILL_CENTER) ↔ Sensor Aspect Ratio       │
│    + Display Rotation (0°/90°/180°/270°) + Front-Camera Horizontal Mirroring   │
└────────────────────────────────────────┬───────────────────────────────────────┘
                                         │
                                         ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│                ON-DEVICE INTELLIGENT VISION & ROUTING ENGINE                   │
│                                                                                │
│  ┌──────────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐  │
│  │  Portrait Pipeline (P2)  │ │ Signature/Thumb(3A) │ │ Document / ID (3B)  │  │
│  │ • FaceDetectionEngine    │ │ • Integral-Image    │ │ • Sobel Gradient +  │  │
│  │   (ML Kit Bundled Impl)  │ │   Sauvola Adaptive  │ │   4-Corner Quad     │  │
│  │ • Portal Rule Framing or │ │   Thresholding      │ │   Confidence Score  │  │
│  │   Configurable Default   │ │ • 3×3 Despeckle +   │ │ • Interactive 4-Pt  │  │
│  │   + Manual Crop Override │ │   Connected-Stroke  │ │   Corner Handles    │  │
│  │ • SelfieSegmentationEng. │ │   Bounding Box Crop │ │   (when conf < 0.75 │  │
│  │   + Alpha Edge Feather   │ │ • Blue/Black Ink    │ │   or user-invoked)  │  │
│  │ • 6-Part Confidence &    │ │   Chroma Preserved  │ │ • 3×3 Homography    │  │
│  │   Edge Uncertainty Audit │ │ • Gentle Fallback   │ │   Bilinear Warp     │  │
│  └────────────┬─────────────┘ └──────────┬──────────┘ └──────────┬──────────┘  │
└───────────────┼──────────────────────────┼───────────────────────┼─────────────┘
                │                          │                       │
                ▼                          ▼                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│            PHASE 0.1 DETERMINISTIC RULE ENFORCER & DISK VERIFIER               │
│  • Exact / Bounded Pixel Resize • Candidate Name & DOP Strip                   │
│  • Stepped JPEG Quality Search (Zero Fake Metadata Padding; Honest MinKB Check)│
│  • Post-Save Disk Verification: Byte Size, Decoded Width×Height, Dual JFIF     │
│    APP0 + EXIF DPI (or PNG pHYs), 6-Part Background Compliance, PDF Geometry   │
└────────────────────────────────────────────────────────────────────────────────┘
```

### B.2 End-to-End Data Flow Across All Pipelines
1. **Task-Aware Capture & Media Ingestion**:
   - Every camera launch receives a typed `CaptureIntent` (`ForPortalRule(portalId, portalName, rule)`, `ForStudio(mode, targetAspectRatio)`, or `Generic(initialMode)`).
   - On capture, `ImageProcessor.decodeBitmapFromUri` normalizes EXIF orientation into upright sensor coordinates, and `CameraViewportMapper.mapPreviewRectToSensorNormalizedRect()` converts the visible on-screen guide frame (`PreviewView.ScaleType.FILL_CENTER`) into exact upright sensor coordinates, reversing front-camera preview mirroring when `lensFacing == LENS_FACING_FRONT`.
2. **Portrait Pipeline (Phase 2 — Replaceable Interfaces + 6-Part Background Verification)**:
   - `FaceDetectionEngine` (backed by bundled ML Kit Face Detection in production and deterministic fakes in JVM unit tests) detects faces, Euler tilt angles, and bounding boxes.
   - If the active `DocumentRule` defines verified official `minHeadHeightRatio` / `maxHeadHeightRatio` / `topHeadroomRatio`, those bounds govern the portrait crop; otherwise `PortraitFramingProfile.DEFAULT_BALANCED` computes an initial head-and-shoulders crop that the user can manually adjust.
   - `SelfieSegmentationEngine` (backed by bundled ML Kit Selfie Segmentation) produces a per-pixel foreground probability map $p(x,y) \in [0, 1]$.
   - Background compliance is evaluated across **6 explicit conditions**:
     1. `segmentationExecuted`: Model inference succeeded and returned a valid confidence grid matching image dimensions.
     2. `subjectMaskConfidence`: Core foreground confidence mean $\ge 0.80$ and foreground area ratio within $12\%..82\%$ of frame.
     3. `edgeUncertaintyRatio`: Fraction of transition-band pixels $p(x,y) \in (0.20, 0.80)$ relative to foreground pixels is $\le 0.18$.
     4. `backgroundReplacementApplied`: Confident background ($p < 0.20$) set to `#FFFFFF` and boundary band ($0.20 \le p \le 0.80$) smoothly alpha-composited over `#FFFFFF`.
     5. `outputBackgroundPixelChecksPassed`: Post-save decoded image on disk has $\ge 85\%$ near-white pixels ($\text{R,G,B} \ge 240$, chroma diff $\le 15$) and mean luminance $\ge 242$ across the segmented background mask.
     6. `userPreviewAccepted`: The user has inspected and accepted the processed preview in the UI.
   - Only when **all 6 conditions** pass does `verifiedBackgroundCompliance` become `true`. If `edgeUncertaintyRatio > 0.18` or `userPreviewAccepted == false`, the result stays in `PreparationStatus.BACKGROUND_UNVERIFIED` with an honest warning and one-tap options to adjust crop or keep the original background.
3. **Signature & Thumb Pipeline (Phase 3A)**:
   - `SignatureVisionProcessor` computes 64-bit integral images ($S$ and $S_{sq}$) to run local Sauvola adaptive thresholding ($T(x,y) = \mu(x,y) \cdot [1 + k \cdot (\frac{\sigma(x,y)}{R} - 1)]$), eliminating uneven paper shadows while preserving blue or black ink chroma.
   - Isolated 1–2 px dust specks are filtered via 3×3 neighborhood density checks before computing the connected-stroke bounding box with configurable breathing margins.
   - If detected ink coverage falls outside plausible bounds ($< 0.15\%$ or $> 45\%$), the processor falls back to gentle contrast normalization and marks `usedGentleFallback = true`.
4. **Document & ID Card Pipeline (Phase 3B)**:
   - `DocumentCornerProcessor` computes Sobel gradients and local contrast to locate the 4 outer corners of an angled document or ID card (`TL, TR, BR, BL`) and scores quadrilateral confidence ($0.0..1.0$) using area coverage, convexity, aspect plausibility, and edge gradient strength.
   - If confidence $\ge 0.75$, perspective rectification runs automatically via an 8-DOF homography solver (`solveHomography` + bilinear inverse mapping); if confidence $< 0.75$ or the user taps **Adjust Corners**, `InteractiveCornerCropOverlay` lets the user drag the 4 corner handles before warping and generating the compliant JPG and PDF.

---

## C. Corrected Dependency, License, APK-Size and Performance Analysis

### C.1 Dependency & Artifact Size Breakdown (Official Documentation vs. Build Measurements)

| Metric / Artifact Category | `com.google.mlkit:face-detection:16.1.7` | `com.google.mlkit:segmentation-selfie:16.0.0-beta6` | Combined / Total Impact | Source / Verification Status |
| :--- | :--- | :--- | :--- | :--- |
| **SDK Dependency Wrapper Size (AAR classes/manifests excluding native `.so` & model assets)** | Pending build inspection | Pending build inspection | Pending build inspection | Must be inspected from resolved Gradle AAR contents during build verification. |
| **Bundled Model Download-Size Impact (Official Google Docs Estimate)** | **~6.9 MB** (statically linked bundled model) | **~4.5 MB** (statically linked bundled model) | **~11.4 MB** indicative download-size increase | Official Google ML Kit Android Documentation ([Face Detection Android](https://developers.google.com/ml-kit/vision/face-detection/android), [Selfie Segmentation Android](https://developers.google.com/ml-kit/vision/selfie-segmentation/android)). Documentation estimate only, not a guaranteed final APK/AAB byte delta. |
| **Baseline Debug APK Size (Before ML Kit)** | N/A | N/A | **`26,495,618` bytes (~26.50 MB)** | Measured on the Phase 0.1 baseline `app-debug.apk` prior to adding ML Kit dependencies. |
| **Post-ML Kit Debug APK Size (`app-debug.apk`)** | Included | Included | **Pending actual build measurement** | Must be measured via `ls -l app/build/outputs/apk/debug/app-debug.apk` after `gradle :app:assembleDebug`. |
| **Release APK / App Bundle Size (`app-release.aab` / `.apk`)** | Included | Included | **Pending actual build measurement** | Must be measured via `gradle :app:bundleRelease` / `:app:assembleRelease`. |
| **Per-ABI Split Size (`arm64-v8a`, `armeabi-v7a`, `x86_64`)** | Unknown until split build | Unknown until split build | **Pending actual ABI split measurement** | No per-ABI size reduction is claimed without empirical output from APK splits or `bundletool`. |

### C.2 Licensing, Terms of Service & Privacy / Telemetry Analysis

| Component / Module | License / Governing Terms | Network / Telemetry Behaviour & User Data Privacy |
| :--- | :--- | :--- |
| **Google ML Kit Face Detection (`com.google.mlkit:face-detection`) & Selfie Segmentation (`com.google.mlkit:segmentation-selfie`)** | **Google ML Kit Terms of Service** (`https://developers.google.com/ml-kit/terms` and Google APIs Terms of Service). **Not** licensed as a whole under Apache 2.0. | **User Media Privacy**: All face detection and portrait segmentation inference executes locally on the device; user photos, signatures, and documents are **never** uploaded to Google servers.<br>**SDK Telemetry & Configuration**: Per Google's ML Kit documentation and Terms of Service, the ML Kit SDK may contact Google servers periodically to report operational/performance metrics (such as API call latency, model version, and device/app metadata) and to receive service configuration or compatibility updates. |
| **AndroidX CameraX, Jetpack Compose, Room, Lifecycle, Coroutines, Security-Crypto, OkHttp, Moshi** | **Apache License 2.0** | Open-source libraries executing locally on device. OkHttp is used only when the user explicitly triggers a preset sync from the configured public JSON endpoint. |
| **In-House Sauvola Binarization (`SignatureVisionProcessor`) & 4-Corner Homography (`DocumentCornerProcessor`)** | **Project First-Party Kotlin Source** | Pure on-device CPU math using standard `android.graphics.Bitmap` arrays; zero external libraries, zero network calls, and zero telemetry. |

### C.3 Physical Motorola Moto G60 Benchmark Plan (Zero Unmeasured Claims)

> [!WARNING]
> All latency and memory figures remain **unmeasured** until executed on a physical **Motorola Moto G60** (Qualcomm Snapdragon 732G, 6 GB RAM, Android 12, 108 MP rear sensor / 32 MP front sensor). No fixed latency numbers are claimed in advance.

`VisionBenchmarkRecorder` will instrument and record actual on-device measurements across $N = 20$ runs per stage on a physical Motorola Moto G60, reporting **min**, **median (p50)**, **p95**, **max**, and **heap delta**:

| Benchmark Stage | What Is Measured on Physical Motorola Moto G60 | Required Metrics to Report |
| :--- | :--- | :--- |
| **1. Model Initialization** | First-call client creation & native graph load (`coldInitMs`) vs. subsequent warm reuse (`warmInitMs`) for `FaceDetection` and `Segmentation`. | Cold ms, Warm median ms, Warm p95 ms |
| **2. Face Detection** | `FaceDetectionEngine.detectFaces()` on a bounded `1024 px` portrait bitmap. | Median ms, p95 ms, max ms, Java/Native heap KB |
| **3. Single-Image Segmentation** | `SelfieSegmentationEngine.segment()` + 6-part confidence/uncertainty audit + alpha compositing onto `#FFFFFF`. | Median ms, p95 ms, max ms, Java/Native heap KB |
| **4. End-to-End Image Preparation** | Complete pipeline from `UnifiedMediaInput` decode → vision stage → rule resize/strip → stepped JPEG quality search → post-save disk verification (`ImageProcessor.processImageForRule`). | Median ms, p95 ms, max ms per document type (Photo, Signature, Thumb, Document) |
| **5. Peak RAM Usage** | Peak Java heap (`Runtime.totalMemory() - freeMemory()`) and native heap (`Debug.getNativeHeapAllocatedSize()`) before, during, and after bitmap recycling. | Peak Java MB, Peak Native MB, Post-GC residual delta MB |
| **6. Large-Image Handling (108 MP & 32 MP)** | Ingestion of native Moto G60 108 MP rear (`12000×9000`) and 32 MP front (`6528×4896`) JPEGs through `inSampleSize` downsampling without `OutOfMemoryError`. | Decode ms, `inSampleSize` factor chosen, Peak RAM MB, 0 OOM crashes |
| **7. Repeated Processing & Thermal Behaviour** | 15 consecutive back-to-back preparations (5 Portrait + 5 Signature + 5 Document) on battery power. | Batch 1–5 median/p95 ms vs. Batch 11–15 median/p95 ms, memory leak check |

---

## D. Complete Revised Implementation Phases

### Phase 0: Baseline Inspection & Verification Gate
- **Scope**:
  - Verify current workspace test suite and baseline APK status before building.
  - Ensure all Phase 0.1 compliance fixes remain intact (`minKb > maxKb` conflict detection, no fake JPEG COM padding, dual JFIF+EXIF vs. PNG `pHYs` DPI verification, PDF page geometry preservation, and safe `Bitmap` ownership).

### Phase 1: Calibrated Camera Viewport Mapping, `CaptureIntent` & Portal Provenance Governance
- **Scope**:
  - Introduce `CaptureIntent` (`ForPortalRule`, `ForStudio`, `Generic`) so `UniversalCameraScreen` adapts its guide overlay and aspect ratio to the exact portal `DocumentRule` or shows a 4-mode task selector (`Photo`, `Signature`, `Thumb`, `Document`) when opened generically.
  - Implement `CameraViewportMapper` to convert normalized guide-frame coordinates from `PreviewView` (`ScaleType.FILL_CENTER`) into upright sensor coordinates across `0°/90°/180°/270°` rotations, `4:3` / `16:9` / `20:9` sensor-to-screen ratios, and front-camera horizontal mirroring.
  - Implement `OfficialDomainAllowlist` and update `PortalSpec` with `notificationNumber`, `recruitmentCycle`, and `sourceRevisionDate`. Enforce the non-destructive 180-day `isInternalReviewDue` reminder policy.
  - Update the About & Privacy dialog (`MainScreen.kt`) with accurate Google ML Kit Terms of Service and local-processing/telemetry disclosures.
- **Fallback Behaviour**:
  - If preview viewport dimensions are unavailable (`0×0`), `CameraViewportMapper` falls back gracefully to the upright normalized guide rectangle without crashing.

### Phase 2: On-Device Portrait Vision (ML Kit Face Detection + Selfie Segmentation + 6-Part Verification)
- **Scope**:
  - Add `com.google.mlkit:face-detection:16.1.7` and `com.google.mlkit:segmentation-selfie:16.0.0-beta6` behind replaceable `FaceDetectionEngine` and `SelfieSegmentationEngine` interfaces in `PortraitVisionProcessor`.
  - Implement portal-specific face framing (when `minHeadHeightRatio` / `maxHeadHeightRatio` / `topHeadroomRatio` are defined on `DocumentRule`) and configurable default framing (`PortraitFramingProfile.DEFAULT_BALANCED`) with manual crop override.
  - Implement the 6-part background verification pipeline in `PortraitVisionProcessor` and `ImageProcessor.processImageForRule`, plus `VisionBenchmarkRecorder`.
- **Fallback Behaviour**:
  - If face detection finds 0 faces or multiple faces, or if `edgeUncertaintyRatio > 0.18f`, the pipeline preserves a safe aspect-ratio crop, keeps `verifiedBackgroundCompliance = false` (`PreparationStatus.BACKGROUND_UNVERIFIED`), displays a specific human-readable warning, and offers one-tap **Manual Crop** or **Keep Original Background**.

### Phase 3A: Signature & Thumb Impression Vision (Sauvola Adaptive Thresholding & Ink Bounds)
- **Scope**:
  - Implement `SignatureVisionProcessor` in its own dedicated module for `SIGNATURE_ENGLISH`, `SIGNATURE_HINDI`, `THUMB_IMPRESSION_LEFT`, and `THUMB_IMPRESSION_RIGHT`.
  - Use integral-image Sauvola adaptive thresholding to turn uneven shadowed paper into pure `#FFFFFF` while preserving blue or black pen ink chroma and extracting the connected-stroke bounding box with safe padding.
- **Fallback Behaviour**:
  - If detected ink pixel coverage is $< 0.15\%$ (blank/faint page) or $> 45\%$ (dark/underexposed frame), automatically fall back to gentle contrast normalization (`usedGentleFallback = true`), set `maskConfidencePassed = false`, and surface the **Gentle Ink Processing** toggle.

### Phase 3B: Document & ID Card Vision (4-Corner Detection, Confidence Scoring & Homography Warp)
- **Scope**:
  - Implement `DocumentCornerProcessor` and `InteractiveCornerCropOverlay` as an independent phase for `AADHAAR_CARD`, `PAN_CARD`, `MARKSHEET_10TH`, `MARKSHEET_12TH`, `CASTE_CERTIFICATE`, `DOMICILE_CERTIFICATE`, `INCOME_CERTIFICATE`, and `OTHER_DOCUMENT`.
  - Detect 4 document corners using Sobel gradient magnitude and luminance contrast, score quadrilateral confidence (`0.0f..1.0f`), and rectify perspective via an 8-DOF 3×3 homography solver (`solveHomography` + bilinear interpolation).
- **Fallback Behaviour**:
  - Whenever quadrilateral confidence is $< 0.75f$, do not auto-warp; present `InteractiveCornerCropOverlay` with 4 draggable corner handles initialized to a safe frame so the user can confirm or reset the corners.

### Phase 4: Full-Screen Portal Preparation Workflow (`Portals List → Portal Detail → Per-Document Prepare`)
- **Scope**:
  - Redesign `PortalStudioScreen` and `PortalCard` so the Portals list is clean and scannable: Title, single-line non-wrapping search bar, non-truncated `"All"` + category filter chips, and compact cards showing Portal Name, Authority, truthful `StatusBadge` (backed by `OfficialDomainAllowlist`), and one `"Prepare"` action.
  - Create full-screen `PortalDetailScreen` (displaying official notification metadata, source URL, non-destructive `isInternalReviewDue` reminder if $> 180$ days, and required documents list) and `PortalDocumentPrepareScreen` (dedicated per-document capture/upload, vision pipeline routing, preview acceptance toggle, `Adjust` sheet, and Save/Share).
- **Fallback Behaviour**:
  - Android system back navigation (`BackHandler`) cleanly pops from `PortalDocumentPrepareScreen` → `PortalDetailScreen` → `PortalStudioScreen`.

### Phase 5: Minimalist Photo & Signature Studio Redesign (`Adjust` Modal Bottom Sheet)
- **Scope**:
  - Create `StudioAdjustBottomSheet` (`ModalBottomSheet`) housing manual overrides (width/height px, aspect lock, target KB slider, DPI selector, candidate name & DOP strip fields, ink mode/threshold sliders, and joint/printable sheet power tools).
  - Refactor `PhotoStudioScreen` and `SignatureStudioScreen` to present a clean layout: compact preset selector, single Camera/Gallery action bar, large preview with Before/After toggle and preview acceptance checkbox, compact verified output card, primary Save/Share buttons, and an `Adjust` button that opens `StudioAdjustBottomSheet`. Remove repetitive marketing banners.

---

## E. Tests and Acceptance Criteria for Every Phase

| Phase | Automated Unit / Robolectric & Compose UI Tests | Acceptance Criteria |
| :--- | :--- | :--- |
| **Phase 1** | `CameraViewportMapperTest`: Rear & front cameras, horizontal mirror reversal on front camera, `0°/90°/180°/270°` rotations, `FILL_CENTER` scaling across `4:3`, `16:9`, and `20:9`.<br>`OfficialDomainAllowlistTest`: Official `.gov.in`/`.nic.in` allowlist pass vs. non-official `https://` rejection, and 180-day `isInternalReviewDue` non-destructive behavior. | Preview guide rectangle maps within $\le 2\text{ px}$ of synthetic sensor fiducial markers across all 4 rotations and both lenses; non-official URLs never show `OFFICIALLY_VERIFIED`; $>180$-day portals show review note without altering `DocumentRule` values. |
| **Phase 2** | `PortraitVisionProcessorTest`: Straight, tilted, and off-center portraits; portal-specific head ratios vs. `DEFAULT_BALANCED`; 6-part background verification (`verifiedBackgroundCompliance = true` only when mask confidence $\ge 0.80$, `edgeUncertaintyRatio <= 0.18`, saved disk whiteness $\ge 85\%$, and `userPreviewAccepted == true`; `false` when `edgeUncertaintyRatio > 0.18` or `userPreviewAccepted == false`). | Zero false-positive `verifiedBackgroundCompliance` on unmasked, low-confidence, or unaccepted previews; exact pre/post ML Kit debug APK byte sizes recorded. |
| **Phase 3A** | `SignatureVisionProcessorTest`: Blue and black ink signatures under simulated linear/radial paper shadow gradients; isolated dust-speck filtering; connected-stroke bounding box trimming; blank/low-contrast fallback (`usedGentleFallback = true`). | Shadowed paper regions become `#FFFFFF` ($\ge 90\%$ near-white on disk) while preserving blue/black ink chroma and stroke continuity; fallback triggers cleanly on blank inputs. |
| **Phase 3B** | `DocumentCornerProcessorTest`: Synthetic angled white document quadrilateral on dark/textured background (`15°–30°` skew); confidence scoring ($\ge 0.75$ on clean quad, $< 0.75$ on low-contrast clutter); 3×3 homography perspective rectification accuracy. | Skewed synthetic document corners are detected within $\le 4\%$ normalized error and warped into an upright rectangular aspect ratio without cropping internal text. |
| **Phase 4** | `PortalWorkflowComposeTest`: `"All"` filter chip full text visibility; compact `PortalCard` rendering; click flow from `PortalStudioScreen` → `PortalDetailScreen` → `PortalDocumentPrepareScreen` and back navigation. | Cramped `OneClickPreparationDialog` is replaced by full-screen navigation; each portal document rule routes to its matching Photo, Signature, Thumb, or Document/PDF pipeline. |
| **Phase 5** | `StudioRedesignComposeTest` + Full Phase 0.1 Regression Suite (`DocumentValidationTest`, `Phase4FeaturesTest`, `Phase5FeaturesTest`, `DocumentLocker*Test`): Verify `Adjust` bottom sheet interaction, preview acceptance toggle, and 100% pass rate on all existing compliance tests. | All existing Phase 0.1 tests and new Phase 1–5 tests pass (`0` failures); UI clutter and repetitive promotional text are eliminated. |

---

## F. Actual Baseline Commit, Workspace State & Test Status Audit

1. **Git Repository & Commit Hash Status**:
   - Running `git status` / `git rev-parse HEAD` in the container workspace returns:
     `fatal: not a git repository (or any of the parent directories): .git`
   - Because the cloud container mounts the project file tree without the `.git` directory, a live git SHA cannot be queried via `git` inside this environment. Previously referenced SHAs in uploaded notes (`2cf6dc7` / `819749b`) originate from external GitHub history prior to workspace import.
2. **Actual Unit Test Inventory on Disk**:
   - Direct inspection of `app/src/test/java/com/example/` via `grep -rc "@Test" app/src/test/java/com/example/` confirms **58 `@Test` methods across 8 test classes**:
     - `Phase5FeaturesTest.kt`: **17** tests
     - `DocumentValidationTest.kt`: **9** tests
     - `DocumentEncryptionTest.kt`: **7** tests
     - `DocumentLockerDatabaseTest.kt`: **6** tests
     - `DocumentLockerRepositoryTest.kt`: **6** tests
     - `ExampleUnitTest.kt`: **5** tests
     - `Phase4FeaturesTest.kt`: **4** tests
     - `ExampleRobolectricTest.kt`: **4** tests
   - On the Phase 0.1 baseline build prior to adding ML Kit dependencies, all **58 tests passed (`58 completed, 0 failed`)** and the baseline `app-debug.apk` size was **`26,495,618` bytes**.
3. **Transparent Disclosure of Current Workspace Files**:
   - During the interrupted execution turn immediately preceding this `PLAN MODE ONLY` prompt, initial code files for Phase 1–3B (`CaptureIntent.kt`, `CameraViewportMapper.kt`, `OfficialDomainAllowlist.kt`, `VisionBenchmarkRecorder.kt`, `PortraitVisionProcessor.kt`, `SignatureVisionProcessor.kt`, `DocumentCornerProcessor.kt`, `InteractiveCornerCropOverlay.kt`) and ML Kit dependency entries in `gradle/libs.versions.toml` / `app/build.gradle.kts` were written to the workspace before this Plan Mode stop gate arrived.
   - Immediately upon receiving this `STRICT MODE: PLAN MODE ONLY — NO CODE CHANGES` instruction, all code modifications and background Gradle tasks were halted. No source files, Gradle files, or tests have been modified in this turn.

---

## G. Risks, Fallback Behaviour and Unresolved Decisions

| Risk / Unknown | Impact | Mitigation / Fallback & Status |
| :--- | :--- | :--- |
| **1. Post-ML Kit APK / AAB & Per-ABI Split Byte Sizes (Remaining Unknown)** | Final binary size increase from bundling ML Kit Face Detection + Selfie Segmentation is estimated at ~11.4 MB in Google docs, but exact APK/AAB byte delta is unknown until compiled. | **Status: Pending Build Measurement**. Measure exact `app-debug.apk` and release bundle byte sizes immediately after plan approval during build verification. |
| **2. Physical Motorola Moto G60 Latency & Thermal Numbers (Remaining Unknown)** | Cloud build containers have no Snapdragon 732G / Moto G60 hardware or physical camera sensors. | **Status: Pending Physical Device Execution**. Do not claim hardware benchmark numbers in container builds; use `VisionBenchmarkRecorder` on the physical Moto G60 to capture median and p95 latencies. |
| **3. Unverified Portal Specifications (PAN & State Portals)** | NSDL/Protean and UTIITSL PAN specifications vary by application mode (DSC vs. physical form vs. e-KYC). | **Status: Mitigated**. Keep conflicting or workflow-dependent presets marked as `UNVERIFIED` or workflow-scoped (`NSDL DSC 200 DPI` vs. `UTIITSL`), and never mark a portal `OFFICIALLY_VERIFIED` without an allowlisted domain and notification title. |
| **4. ML Kit Native JNI Execution in Host JVM Robolectric Tests** | Bundled ML Kit native `.so` libraries target Android ARM/x86 device runtimes and may throw `UnsatisfiedLinkError` in host JVM unit tests. | **Status: Mitigated**. Wrap ML Kit calls behind `FaceDetectionEngine` and `SelfieSegmentationEngine` with `try/catch (Throwable)` fallback, and inject deterministic confidence-map engines in JVM unit tests so all framing, 6-part background verification, and disk compliance logic is 100% unit-tested. |

---

## H. Final Build Readiness Checklist

- [x] **1. ML Kit Size Analysis Corrected**: Official Google documentation download-size estimates (~6.9 MB Face Detection, ~4.5 MB Selfie Segmentation, ~11.4 MB combined) separated from SDK size, Debug APK size, Release APK/AAB size, and per-ABI split size; unmeasured artifacts marked pending build measurement.
- [x] **2. Unmeasured Moto G60 Claims Removed**: All fixed latency claims removed and replaced with a 7-stage physical Moto G60 benchmark protocol reporting `median` and `p95`.
- [x] **3. 6-Part Probabilistic Background Verification Defined**: Model execution, subject confidence, edge uncertainty ratio, background replacement, post-save disk pixel check, and user preview acceptance separated; uncertain edges remain in an honest warning state (`BACKGROUND_UNVERIFIED`).
- [x] **4. Licensing & Privacy Disclosed Accurately**: Google ML Kit Terms of Service documented separately from Apache 2.0 libraries; local image processing preserved while disclosing ML Kit diagnostic/configuration telemetry.
- [x] **5. Portrait Framing & Identity Rules Corrected**: Official head-size rules applied only when specified by a portal; configurable defaults and manual crop used otherwise; genuine facial identity preserved without claiming pixel-perfect invariance after resampling.
- [x] **6. Portal Governance & 180-Day Review Corrected**: `OfficialDomainAllowlist`, notification metadata, and non-destructive 180-day `isInternalReviewDue` threshold defined.
- [x] **7. Phase 3 Split into Phase 3A & Phase 3B**: Signature/Thumb Sauvola pipeline (3A) and Document/ID 4-Corner Homography pipeline (3B) separated with distinct files, fallbacks, and tests.
- [x] **8. Baseline Audited Truthfully**: Container `.git` absence and 58 `@Test` method inventory documented; Phase 0.1 fixes preserved.
- [ ] **9. User Final Approval Gate**: Awaiting user review and approval (`Proceed`) before resuming code changes and running the full Gradle build and test verification.
