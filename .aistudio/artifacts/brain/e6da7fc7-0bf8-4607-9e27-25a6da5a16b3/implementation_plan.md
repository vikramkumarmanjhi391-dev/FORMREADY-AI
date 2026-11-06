# FORMREADY AI — Master Development Roadmap & Complete Product Architecture Plan (Revised v2)

FormReady AI is a privacy-first, 100% offline-capable Android document preparation and encrypted vault application designed for Indian government exam aspirants (SSC, UPSC, BPSC, Railway, CTET, Banking), PAN applicants, citizens using state e-District/RTPS portals, and cyber-cafe operators. This revised Master Development Plan incorporates all technical, mathematical, security, and governance corrections from the architectural review, establishing a strictly sequenced **Phase 0 to Phase 10** execution blueprint with **₹0 recurring server or paid API cost**.

---

## User Review & Confirmed Architectural Corrections (v2 Revision)

> [!IMPORTANT]
> This plan has been revised to incorporate every technical and policy correction identified during review. **No code will be modified until you explicitly approve this revised roadmap, and Phase 0 will be executed, tested, and reviewed alone before Phase 1 begins.**

1. **Workflow-Specific PAN Specifications (No Universal `213×213` Assumption)**:
   - Separated PAN rules by **exact portal workflow, form type, and submission mode**:
     - **Protean (NSDL) DSC-Based Online PAN (`tin.tin.proteantech.in`)**: Photo `200 DPI`, JPEG, max `20 KB`, `3.5 × 2.5 cm` (`~276 × 197 px @ 200 DPI`); Signature `200 DPI`, JPEG, max `10 KB`, `2 × 4.5 cm` (`~354 × 157 px` landscape @ `200 DPI`).
     - **UTIITSL Physical Form 49A Guideline (`pan.utiitsl.com`)**: Specifies affixing two physical `4.5 × 3.5 cm` photographs on the printed form (not a digital `213×213` upload spec).
     - **Square pixels (`213×213 px @ 300 DPI` $\approx 18\times 18\text{ mm}$) and rectangular physical dimensions (`35×25 mm @ 300 DPI` $\approx 413\times 295\text{ px}$) are never mixed in the same preset.** Any third-party/agency scanner preset without a primary official notification PDF is marked `UNVERIFIED_RULE`.
2. **Phase 2 AI Model Verification Before Final Selection**:
   - Removed conflated tensor assumptions. MediaPipe `SelfieSegmenter (square)` (`256×256`, `float16`), `SelfieSegmenter (landscape)` (`144×256`), and `SelfieMulticlass (256×256, 6 classes)` have distinct tensor signatures and require embedded **TFLite Model Metadata** when loaded via MediaPipe Tasks.
   - Distinguished **Mask Coverage Heuristic** (foreground area ratio) from **Model Output Confidence** (mean probability/logit margin across foreground vs boundary pixels), and documented Google's Model Card limitation regarding fine hair/thin structures.
3. **Phase 1 Two-Stage CameraX Coordinate Mapping + Null Fallback**:
   - Explicitly separated **(a) Full Visible Preview-to-Capture Crop Alignment** (using CameraX `ViewPort` + `UseCaseGroup`) from **(b) Sub-Frame Guide Rectangle Mapping** (mapping the 4 corners of the smaller on-screen Photo/Signature/Document guide box into the captured image via `PreviewView.getSensorToViewTransform()` / `CoordinateTransform`), with a deterministic fallback when transforms return `null` prior to layout/binding.
4. **Phase 4 Document Scanner True Aspect Ratio & Multi-Metric Comparison**:
   - Fixed the projective geometry flaw: 4 perspective-foreshortened corners do not directly yield true physical aspect ratio via Euclidean pixel distance. Known document types (`A4 = 1:1.4142`, `ID Card CR80 = 85.60×53.98 mm`) enforce their true physical aspect ratio on the `Matrix.setPolyToPoly` destination rectangle, while unknown documents provide an estimated ratio with user override.
5. **Strict Separation of Phase 0 and Phase 1 Execution**:
   - **Phase 0** (processing contracts, honest validation, compliance flag fixes) and **Phase 1** (CameraX `ViewPort` + guide-rect transformation) are **strictly separate phases** with independent build, test, and review gates.
6. **Phase 6 Printable Sheet PDF Physical Dimensions & Decoupled Dependency**:
   - Added exact physical-size **PDF Print Sheet output** (`4×6 inch = 288×432 pt`, `A4 = 595×842 pt`) alongside `300 DPI` JPEG sheets, with explicit `"Print at 100% / Actual Size"` instructions, and removed Phase 2 as a hard dependency for Phase 6.
7. **Phase 7 Locker Metadata Privacy, `KeyInfo` Hardware Check & Ephemeral Preview**:
   - Added SQLite metadata encryption/minimization, `KeyInfo.getSecurityLevel()` (`TRUSTED_ENVIRONMENT` / `STRONGBOX` vs `SOFTWARE`) verification, and in-memory / minimized-disk previewing instead of relying on flash file overwriting.
8. **Phase 9 AdMob & Google Play Console 18+ Age Eligibility**:
   - Documented the mandatory **18+ age requirement** (or parent/guardian account ownership and KYC) for Google Play Console and AdMob monetization.

---

## 1. Overview & Core Concept (Section A — Executive Summary)

- **What It Does**: FormReady AI enables users to select a verified government portal workflow (or custom document rule), capture or pick a photo/signature/thumb/document, prepare a compliant output file locally on-device, inspect honest post-write validation results, and save to Gallery, Downloads, or an AES-256-GCM encrypted Document Locker.
- **Core Technical & Business Principles**:
  1. **Zero Paid AI API Dependency (`₹0` Recurring Server Cost)**: No Gemini API, OpenAI API, or cloud image processing.
  2. **100% Offline Core Operation**: All image, signature, document perspective, PDF, and locker features run locally without internet.
  3. **Strict Truthfulness in Validation & Presets**: Never mark unverified or remote JSON rules as `OFFICIALLY_VERIFIED`; never conflate square pixels with rectangular millimeter dimensions; never report a file size or background requirement as met without inspecting the actual output file on disk.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Workflow-Aware Studio Flow**: `Select Portal & Specific Submission Workflow (e.g., Protean DSC PAN vs SSC CGL)` $\rightarrow$ `Capture in Guided Camera or Pick from Gallery` $\rightarrow$ `On-Device Processing` $\rightarrow$ `Inspect Before/After, Restore Original if Needed & Check Honest Validation Report` $\rightarrow$ `Export`.
  2. **4-Corner Document Scanner Flow**: `Capture/Pick Document` $\rightarrow$ `Auto 4-Corner Quad Proposal + Interactive Draggable Corner Handles with Loupe` $\rightarrow$ `Select Physical Aspect Preset (A4, ID Card CR80, Auto/Custom)` $\rightarrow$ `Homography Warp & Illumination Normalization` $\rightarrow$ `Export Validated JPG / PDF`.
- **Visual Identity & Accessibility**:
  - Material 3 civic palette (`NavyPrimary #0D253F`, `SaffronSecondary #FF6F00`, `EmeraldTertiary #1B5E20`), `enableEdgeToEdge()` with `WindowInsets.safeDrawing` and `WindowInsets.navigationBars`, tabular numerals for `px / mm / DPI / KB` badges, and `48.dp` minimum touch targets.

---

## 3. Audited Workspace Provenance & Current Architecture (Sections B, C & D)

### B1. Audited Codebase Provenance (Baseline Commit & File Checksums)
To prevent line-number drift as code evolves in later phases, all line references in this audit are anchored to:
- **Baseline GitHub Commit**: `8e055e353ec40b113bda993eef1ca03adcf54cfb` plus the current AI Studio workspace state (`/app/applet` at `2026-11-05`).
- **Audited Workspace File SHA-256 Fingerprints**:
  - `app/build.gradle.kts` (`158 lines`): `b9746325fadf4f6e4c8e890d5be83c07ede99c4036c7d170346728b01dd212f3`
  - `ImageProcessor.kt` (`1159 lines`): `c08e64064b1e3939b017540ae566a830c7e95ce960c5b67e9f27c35cd68c37e5`
  - `PdfProcessor.kt` (`334 lines`): `43cf40be1fe7ec003b73205e2d93f6bc6930f20c1f433c45ee5d83bd8e562080`
  - `PortalDatabase.kt` (`939 lines`): `9e16e22e411375f91974c6dac46efefb80d6e916f71b5cce9ddec7cbc32e1e2d`
  - `UniversalCameraScreen.kt` (`940 lines`): `fc4947f1a8eb8e16125900bbb86c995fb6780c08c87416f02e4514b42dd25226`
  - `DocumentLockerRepository.kt`: `7b54953f90f818ba8150fc9df8fece19d70cb783b15e2873bd1530da30f79a60`

### B2. Current Architecture Diagram

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                         JETPACK COMPOSE UI LAYER                              │
│  ┌────────────────────┐  ┌────────────────────┐  ┌─────────────────────────┐  │
│  │ HomeDashboardScreen│  │ PortalStudioScreen │  │ PortalPresetSelectorBar │  │
│  └─────────┬──────────┘  └─────────┬──────────┘  └────────────┬────────────┘  │
│            ▼                       ▼                          ▼               │
│  ┌────────────────────┐  ┌────────────────────┐  ┌─────────────────────────┐  │
│  │ PhotoStudioScreen  │  │SignatureStudioScrn │  │     PdfMasterScreen     │  │
│  └─────────┬──────────┘  └─────────┬──────────┘  └────────────┬────────────┘  │
│            └───────────────────────┼──────────────────────────┘               │
│                                    ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │    UniversalCameraScreen + UniversalCameraController + UnifiedMediaInput│  │
│  └─────────────────────────────────┬───────────────────────────────────────┘  │
└────────────────────────────────────┼──────────────────────────────────────────┘
                                     ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                     COORDINATOR & PROCESSING ENGINE LAYER                     │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │                   OneClickPreparationCoordinator                        │  │
│  └───────────────┬─────────────────────────────────────────┬───────────────┘  │
│                  ▼                                         ▼                  │
│  ┌───────────────────────────────┐         ┌───────────────────────────────┐  │
│  │        ImageProcessor         │         │         PdfProcessor          │  │
│  │ • EXIF Decode & Crop          │         │ • Images to A4 PDF            │  │
│  │ • Heuristic BG / Sig Cleanup  │         │ • Front/Back ID Card PDF      │  │
│  │ • Stepped JPEG + JFIF DPI     │         │ • Unverified PDF Compression  │  │
│  └───────────────┬───────────────┘         └───────────────┬───────────────┘  │
│                  └────────────────────┬────────────────────┘                  │
│                                       ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │        QualityInspector + DocumentValidationManager (Magic Bytes/PDF)   │  │
│  └─────────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────┬───────────────────────────────────────┘
                                        ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                   LOCAL DATA, PORTAL & ENCRYPTED VAULT LAYER                  │
│  ┌──────────────────────────┐  ┌───────────────────────────────────────────┐  │
│  │      PortalDatabase      │  │ DocumentLockerRepository + Room Database  │  │
│  │ • Built-in PortalSpecs   │  │ • DocumentEncryptionManager (AES-256-GCM) │  │
│  │ • JSON Sync Parser       │  │ • Plaintext Room Metadata + Cipher Files  │  │
│  └──────────────────────────┘  └───────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────┘
```

### C. Existing Feature Implementation Audit Matrix

| Feature / Module | 1. UI Exists | 2. Processing Logic Exists | 3. Automated JVM/Robolectric Tests | 4. Real Android Device Tested | 5. Real-World Output Quality Verified | Honest Audit Classification |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **Universal Camera (Preview, Flash, Lens Switch, Tap-to-Focus)** | Yes | Yes (`CameraX 1.5.0`) | Controller unit tests | Not yet on Moto G60 | Unverified on physical camera | **Partially Implemented** (Lacks `ViewPort` + guide-rect sensor coordinate transform) |
| **UnifiedMediaInput (Camera & Gallery Equivalence + EXIF)** | N/A | Yes | Yes | Not yet | Verified in JVM tests | **Genuinely Implemented** (Needs mapped guide-crop rect from Phase 1) |
| **Photo Studio — Resize, Aspect Crop, Name/Date Strip** | Yes | Yes | Yes | Not yet | Verified in JVM tests | **Genuinely Implemented** |
| **Photo Studio — Portrait White Background Replacement** | Yes | **Heuristic Only** | Tested heuristic only | No | **No** (Perimeter flood-fill fails on real rooms) | **Placeholder Heuristic** (Must be replaced by validated `.tflite` segmentation in Phase 2) |
| **Photo Studio — JFIF DPI Header Injection** | Yes | Yes (`APP0` byte injection) | Yes | Not yet | Verified byte structure | **Partially Implemented** (Needs EXIF `XResolution`/`YResolution` sync) |
| **Signature & Thumb Studio — Shadow Removal & Ink Modes** | Yes | Coarse 16×16 grid threshold | Synthetic test | Not yet | Thin strokes at risk under shadows | **Partially Implemented** (Needs Sauvola adaptive threshold + real signature dataset test) |
| **Smart Document Scanner — 4-Corner & Perspective Warp** | Partial | **Contrast Filter Only** (`applyMagicDocFilter`) | Filter tested only | No | **No** (No 4-corner detection or homography warp exists) | **Missing Core CV** (Must implement quad detection + known-ratio `setPolyToPoly` warp) |
| **PDF Master — Multi-Page A4 PDF & Front/Back ID Merger** | Yes | Yes (`PdfDocument`) | Yes (Robolectric shadow) | Not yet | Needs real device check | **Genuinely Implemented** |
| **PDF Master — Compress PDF to Target KB** | Yes | Single-pass fixed scale | Basic test | No | **No** (Does not verify final KB <= `targetMaxKb` and shows false success) | **Flawed Implementation** (Must loop & report failure honestly if target KB is unreachable) |
| **Student Tools — Printable 4×6 & A4 Passport Sheet** | Yes | Fixed `4×2` / `4×3` grid | Basic file test | No | **No** (Stretches tiles to cells; lacks PDF physical print sheet) | **Flawed Implementation** (Must enforce exact `35×45 mm` tile math + PDF print output) |
| **Portal Requirement Engine (`PortalDatabase`)** | Yes | Yes | Yes | Not yet | **Contains contradictory PAN specs** (`213×213 px` mixed with `35×25 mm`) | **Needs Urgent Accuracy Audit (Phase 5)** |
| **GitHub JSON Preset Sync** | Yes | Basic `JSONArray` parser | Yes | Not yet | Uses placeholder URL & marks remote JSON `OFFICIALLY_VERIFIED` | **Flawed Governance** (Must mark `UNVERIFIED_RULE` & validate schema) |
| **Document Locker & Notes Vault (AES-256-GCM + Room)** | Yes | Yes (`AndroidKeyStore`) | Yes | Not yet | Room metadata is plaintext; `KeyInfo` level unchecked | **Partially Hardened** (Needs Phase 7 security upgrades) |

---

### D. Verification of All Implementation & Domain Concerns

1. **PAN Preset Mathematical & Regulatory Inconsistency (`PortalDatabase.kt` lines 610–715)**:
   - **Finding**: `nsdl_pan` and `uti_pan` currently mix `exactWidthPx = 213, exactHeightPx = 213, dpi = 300` (`~18×18 mm` square) with `physicalWidthMm = 25f, physicalHeightMm = 35f` (`~295×413 px @ 300 DPI` rectangle) and claim `OFFICIALLY_VERIFIED`.
   - **Official Fact**: Protean's official DSC-based PAN guideline (`tin.tin.proteantech.in`) specifies **Photograph: `200 DPI`, JPEG, max `20 KB`, `3.5 × 2.5 cm`** and **Signature: `200 DPI`, JPEG, max `10 KB`, `2 × 4.5 cm`**. UTIITSL's physical form guideline (`pan.utiitsl.com`) specifies affixing two physical `4.5 × 3.5 cm` photos on the printed form.
   - **Correction**: Split presets by explicit workflow/submission type, fix pixel-to-mm math ($P_{\text{px}} = \text{round}(\text{mm} / 25.4 \times \text{DPI})$), and mark any legacy/agency scanner preset lacking a primary official notification URL as `UNVERIFIED_RULE`.
2. **Heuristic Background Replacement & False Whiteness Validation (`ImageProcessor.kt` lines 403, 522–657)**:
   - **Finding**: `applyAutoWhiteBackgroundAndFaceEnhance()` is a border-color flood-fill heuristic, and `hasWhiteBackground` is set from a boolean flag rather than inspecting background pixels.
   - **Correction**: In Phase 0, validate background whiteness on non-subject background pixels; in Phase 2, replace the heuristic with a verified on-device segmentation model.
3. **CameraX Preview vs Guide-Rectangle Mapping (`UniversalCameraScreen.kt` & `UnifiedMediaInput.kt`)**:
   - **Finding**: Static `guideCropRectNorm` ignores both `PreviewView` `FILL_CENTER` cropping and the transformation between the sub-frame UI overlay and the captured image.
   - **Correction**: In Phase 1, implement both `ViewPort`/`UseCaseGroup` alignment and 4-corner guide-rectangle transformation with a safe fallback when transforms are `null`.
4. **Document Perspective Destination Aspect Ratio (`PdfMasterScreen.kt` / `ImageProcessor.kt`)**:
   - **Finding**: No 4-corner warp exists yet, and naive Euclidean distance between foreshortened corners distorts aspect ratio on tilted photos.
   - **Correction**: In Phase 4, use known physical aspect ratios (`A4 = 1:1.4142`, `CR80 ID Card = 1.5858:1`) for the `Matrix.setPolyToPoly` destination rectangle, and provide an adjustable aspect ratio for custom documents.
5. **PDF Compression False Success (`PdfProcessor.kt` lines 163–210 & `PdfMasterScreen.kt`)**:
   - **Finding**: `compressPdfToTargetKb()` runs a single pass without checking if the resulting file actually meets `targetMaxKb`, while the UI unconditionally claims `"Compressed Successfully! (Below X KB limit)"`.
   - **Correction**: In Phase 0/6, check actual disk bytes after compression and display an honest warning/error if `outFile.length() > targetMaxKb * 1024L`.

---

## 4. Technical Architecture & On-Device AI Model Strategy (Sections E, F, G & H)

### E. Target Technical Architecture

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                    CAMERA & GALLERY UNIFIED INPUT PIPELINE                    │
│  • Stage 1: CameraX UseCaseGroup + ViewPort (Aligns Preview & ImageCapture)   │
│  • Stage 2: Guide-Box 4-Corner Transform (PreviewView -> Bitmap Coordinates)  │
│  • Safe Fallback: Aspect-Aware Center Inset Crop when Transform is null       │
└───────────────────────────────────────┬───────────────────────────────────────┘
                                        ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                   ON-DEVICE COMPUTER VISION & AI ENGINES                      │
│  ┌─────────────────────────┐ ┌─────────────────────────┐ ┌─────────────────┐  │
│  │ MODULE 2: PHOTO ENGINE  │ │ MODULE 3: SIG & THUMB   │ │ MODULE 4: DOC   │  │
│  │ • Verified .tflite Seg  │ │ • Integral-Image        │ │ •Quad Detector  │  │
│  │   (Checksum + Metadata) │ │   Sauvola Threshold     │ │  + Loupe UI     │  │
│  │ • Guided Edge Feather   │ │ • Stroke Preservation   │ │ •Known-Ratio    │  │
│  │ • Mask-Region BG Check  │ │ • 1-Tap Restore Original│ │  setPolyToPoly  │  │
│  └────────────┬────────────┘ └────────────┬────────────┘ └────────┬────────┘  │
└───────────────┼───────────────────────────┼───────────────────────┼───────────┘
                └───────────────────────────┼───────────────────────┘
                                            ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│              DETERMINISTIC ENCODING & HONEST VALIDATION ENGINE                │
│  • Workflow-Specific Portal Rules (Exact px OR mm @ DPI -> px conversion)     │
│  • JFIF APP0 + EXIF XResolution/YResolution Dual DPI Writer                   │
│  • Honest Disk Size & Mask-Background Whiteness Inspector                     │
│  • Physical Print Sheet Generator (Both 300 DPI JPEG & Exact-Point PDF)       │
└───────────────────────────────────────────────────────────────────────────────┘
```

### F. On-Device AI & Computer Vision Model Evaluation Strategy (Section 7)

#### 1. Portrait Segmentation Candidates (To Be Benchmarked on Moto G60 Before Final Lock)
Rather than assuming tensor shapes from outdated documentation, Phase 2 begins with a **Model Verification & Benchmark Harness** that inspects each candidate `.tflite` file's SHA-256 checksum, embedded TFLite Model Metadata (`MetadataExtractor`), input/output tensor shapes/types, and real portrait quality on the Moto G60:

| Candidate Model | Official Google MediaPipe Source | Input / Output Tensors (Verified via `MetadataExtractor`) | Quantization & Size | MediaPipe Tasks vs LiteRT Compatibility | Known Limitations (Google Model Card) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Candidate 1: MediaPipe `selfie_segmenter.tflite` (Square)** | `storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite` | `256 × 256` RGB input $\rightarrow$ Category / Confidence mask | `float16` (`~250 KB`) | Contains TFLite Model Metadata for `ImageSegmenter`; also runnable via LiteRT | Fast, small; may soften fine flyaway hair strands or thin glasses frames against complex backgrounds |
| **Candidate 2: MediaPipe `selfie_multiclass_256x256.tflite`** | `storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite` | `256 × 256` RGB input $\rightarrow$ `6` semantic classes (`0: Background`, `1: Hair`, `2: Body-skin`, `3: Face-skin`, `4: Clothes`, `5: Others`) | `float32` (`~16.2 MB`) | Full MediaPipe Tasks `ImageSegmenter` metadata support | Separate `Hair` class allows gentler alpha matting on hair edges vs shoulder edges, at the cost of larger APK asset size and higher inference latency |

- **True Confidence vs Mask Coverage Distinction**:
  1. **Model Probability Confidence**: Computed from the model's float confidence mask $P_{\text{fg}}(x, y) \in [0, 1]$ by measuring the ratio of uncertain boundary pixels ($0.30 < P_{\text{fg}}(x, y) < 0.70$) to total foreground pixels. High boundary uncertainty indicates the model cannot cleanly separate subject from background.
  2. **Geometric Coverage Sanity Check**: Separately checks whether the detected subject occupies a plausible portrait area (`12%–90%` of the frame).
  3. **User Fallback**: Whenever model probability confidence or coverage check fails, the UI warns the user honestly and provides a 1-tap toggle to keep the original background or re-capture against a plain wall.

#### 2. Document 4-Corner Detection & Perspective Rectification Comparison (Phase 4)

| Evaluation Criterion | **Approach A: Native Kotlin Sobel/Contour Quad Detector + `Matrix.setPolyToPoly`** | **Approach B: OpenCV Android SDK (`Canny` + `findContours` + `approxPolyDP` + `warpPerspective`)** |
| :--- | :--- | :--- |
| **1. APK Size Impact** | `0 KB` native library overhead | `+28 MB to +45 MB` across ABIs (or `~8 MB` if ABI-filtered/stripped) |
| **2. Detection Accuracy** | High on clear table contrast; requires multi-scale Sobel + adaptive Otsu + convex-hull quad fitting to match OpenCV on patterned tables | Industry-proven `Canny` + morphological dilation + Douglas-Peucker `approxPolyDP` contour simplification |
| **3. Processing Latency (Moto G60)** | `~18–35 ms` on `360px` downsampled luminance buffer; `~25 ms` for `setPolyToPoly` warp | `~10–20 ms` via JNI C++ |
| **4. Failure Rate & Recovery** | Whenever quad confidence is below threshold (e.g., white paper on white bedsheet), falls back to the **Interactive 4-Corner Handle + Magnifying Loupe UI** | Same requirement: even OpenCV fails on white-on-white surfaces, so interactive corner handles remain mandatory |
| **5. Destination Aspect Ratio Math** | Enforces **exact physical aspect ratio** for known types (`A4 = 210:297`, `A5 = 148:210`, `ID Card CR80 = 85.60:53.98`) and estimated foreshortening-corrected ratio + user selector for custom documents | Uses identical destination rectangle aspect-ratio presets |

---

## 5. Sequenced Phase-Wise Development Roadmap (Sections I & J — Phases 0 to 10)

> [!WARNING]
> **Strict Phase Gate Rule**: Each phase below must be implemented, compiled, unit-tested, and reviewed **individually**. Do **not** combine Phase 0 and Phase 1 in a single implementation turn.

---

### Phase 0 — Existing Project Audit and Processing Contract Stabilization (Execute First, Standalone)
1. **Objective**: Fix existing validation falsehoods and processing contract bugs without touching camera or neural models:
   - Fix `ProcessedFileResult.hasWhiteBackground` so it measures actual background pixels rather than echoing the request flag.
   - Fix `PdfProcessor.compressPdfToTargetKb()` and `PdfMasterScreen.kt` so they check actual output file size on disk and report an honest warning/error when `targetMaxKb` cannot be met.
   - Add dual JFIF `APP0` + EXIF (`XResolution`, `YResolution`, `ResolutionUnit`) DPI writing and verification.
   - Add a 1-tap **"Restore Original Image"** action in Photo and Signature studios.
2. **Existing Source Files to Inspect**: `DocumentModels.kt`, `ImageProcessor.kt`, `PdfProcessor.kt`, `QualityInspector.kt`, `PdfMasterScreen.kt`, `PhotoStudioScreen.kt`, `SignatureStudioScreen.kt`.
3. **Exact Files to Create or Modify**:
   - Modify: `DocumentModels.kt`, `ImageProcessor.kt`, `PdfProcessor.kt`, `QualityInspector.kt`, `PdfMasterScreen.kt`, `PhotoStudioScreen.kt`, `SignatureStudioScreen.kt`.
4. **Technical Implementation Strategy**:
   - Implement `ImageProcessor.measureBackgroundWhiteness(bitmap, backgroundMask)` which evaluates mean luminance ($\ge 245$) and low chroma variance across identified background pixels (using outer non-subject mask regions).
   - Update `PdfProcessor.compressPdfToTargetKb()` to return a structured `PdfCompressionResult(file: File, finalSizeBytes: Long, targetMaxKb: Int, metTarget: Boolean)` and update `PdfMasterScreen.kt` to show a warning card if `metTarget == false`.
5. **Dependencies & Licensing**: Standard Android SDK (`ExifInterface`, `PdfDocument`, `PdfRenderer`). Apache 2.0.
6. **Offline/Network Requirements**: 100% Offline.
7. **Security & Privacy Risks**: None.
8. **Automated Tests**: Unit tests verifying false-positive `hasWhiteBackground` is eliminated on colored backgrounds, dual JFIF+EXIF DPI readback works, and `compressPdfToTargetKb` accurately flags oversized outputs.
9. **Real-Device Tests (Moto G60)**: Verify Photo/Signature/PDF outputs report truthful KB, px, DPI, and compliance status.
10. **Acceptance Criteria**: Zero false-positive compliance badges across ImageProcessor and PdfProcessor; all unit tests pass.
11. **Dependencies on Earlier Phases**: None.
12. **Features Explicitly Out of Scope**: CameraX coordinate mapping (Phase 1), `.tflite` segmentation (Phase 2), PAN preset overhaul (Phase 5).
13. **Rollback/Regression Considerations**: Preserve all existing UI `testTag`s and method signatures.

---

### Phase 1 — CameraX Coordinate Mapping & Unified Media Reliability (Execute Second, After Phase 0 Review)
1. **Objective**: Implement accurate two-stage CameraX coordinate alignment (`ViewPort`/`UseCaseGroup` visible crop alignment + 4-corner guide-rectangle transformation into captured bitmap coordinates) with a safe fallback when transforms are `null`, and harden temporary camera file ownership.
2. **Existing Source Files to Inspect**: `UniversalCameraController.kt`, `UniversalCameraScreen.kt`, `UnifiedMediaInput.kt`.
3. **Exact Files to Create or Modify**:
   - Modify: `UniversalCameraController.kt`, `UniversalCameraScreen.kt`, `UnifiedMediaInput.kt`.
4. **Technical Implementation Strategy**:
   - **Stage 1 — Shared `ViewPort` via `UseCaseGroup`**:
     - Build a `ViewPort` from `previewView.viewPort` (matching `PreviewView.ScaleType.FILL_CENTER` and display rotation) and bind `Preview` + `ImageCapture` together via `UseCaseGroup.Builder().setViewPort(viewPort)` so `ImageCapture.OutputFileResults` / `ImageProxy.cropRect` aligns with the visible preview.
   - **Stage 2 — Sub-Frame Guide Box Transformation**:
     - Map the 4 corners of the on-screen guide rectangle (Passport Oval bounding box, Signature Cutout Box, Document Frame) from `PreviewView` pixel coordinates to the decoded post-EXIF bitmap coordinates, accounting for front-camera horizontal mirroring.
   - **Safe Fallback**:
     - If `previewView.viewPort` or `sensorToViewTransform` is `null` (e.g., before layout pass completes or in headless tests), compute the exact mathematical `FILL_CENTER` viewport intersection from `(previewWidth, previewHeight)` and `(bitmapWidth, bitmapHeight)`.
5. **Dependencies & Licensing**: `androidx.camera:camera-core:1.5.0`, `camera-camera2`, `camera-lifecycle`, `camera-view` (Apache 2.0).
6. **Offline/Network Requirements**: 100% Offline.
7. **Security & Privacy Risks**: Immediate deletion of retaken/cancelled files in `cacheDir/camera_captures`.
8. **Automated Tests**: Unit tests verifying Stage 1 + Stage 2 coordinate mapping across `20:9` (`1080×2460` Moto G60) and `16:9` viewports against `4:3` (`4000×3000`) sensor bitmaps, including front-camera mirroring and `null`-transform fallback.
9. **Real-Device Tests (Moto G60)**: Place a physical card/signature precisely on the inner border of the on-screen guide box on the Moto G60; capture with rear and front cameras and verify the cropped result matches all 4 borders.
10. **Acceptance Criteria**: Sub-frame guide box crops accurately on `20:9` and `16:9` screens with zero `NullPointerException` when transforms are unavailable.
11. **Dependencies on Earlier Phases**: Phase 0 completed and approved.
12. **Features Explicitly Out of Scope**: Neural segmentation (Phase 2).
13. **Rollback/Regression Considerations**: Keep `UnifiedMediaInput` compatible with Gallery URIs (where guide crop is only applied if user crops or in guided camera mode).

---

### Phase 2 — True Offline AI Portrait Segmentation Engine
1. **Objective**: Benchmark and integrate a bundled on-device portrait segmentation model (`selfie_segmenter.tflite` vs `selfie_multiclass_256x256.tflite`), validate model metadata/checksums, implement luminance-guided edge feathering, and validate background whiteness on segmented background pixels.
2. **Existing Source Files to Inspect**: `ImageProcessor.kt`, `PhotoStudioScreen.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`.
3. **Exact Files to Create or Modify**:
   - Create: `app/src/main/assets/models/` (bundled verified `.tflite` model), `PortraitSegmentationEngine.kt`.
   - Modify: `ImageProcessor.kt`, `PhotoStudioScreen.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`.
4. **Technical Implementation Strategy**:
   - Verify `.tflite` file SHA-256 checksum, embedded TFLite Model Metadata, and exact input/output tensor shapes before binding.
   - Compute both **Model Probability Confidence** (mean foreground probability & boundary uncertainty ratio) and **Subject Coverage Sanity Check**, warning the user when the model cannot produce a reliable mask.
   - Validate `hasWhiteBackground` on the **segmented background pixel mask** ($P_{\text{fg}}(x, y) < 0.15$) rather than only the 4-pixel image border.
5. **Dependencies & Licensing**: MediaPipe Tasks Vision (`com.google.mediapipe:tasks-vision`) or LiteRT (`com.google.ai.edge.litert:litert`) — Apache 2.0.
6. **Offline/Network Requirements**: 100% Offline (bundled in APK `assets/`).
7. **Security & Privacy Risks**: Local in-memory inference only.
8. **Automated Tests**: Model checksum & tensor metadata verification test, mask probability confidence test, and mask-based background whiteness test.
9. **Real-Device Tests (Moto G60)**: Test on 15+ real portraits (flyaway hair, dark hair against dark wall, glasses, collared shirts, low indoor light) on Moto G60; measure PSS memory and latency.
10. **Acceptance Criteria**: Verified `.tflite` segmentation running 100% offline with honest confidence reporting and natural hair/shoulder transitions on tested portraits.
11. **Dependencies on Earlier Phases**: Phase 0, Phase 1.
12. **Features Explicitly Out of Scope**: Face alteration/beautification.
13. **Rollback/Regression Considerations**: Close native segmenter resources deterministically; allow user to toggle off background removal ("Keep Original Background") at any time.

---

### Phase 3 — Signature and Thumb Processing Intelligence
1. **Objective**: Replace coarse thresholding with an **Integral-Image Sauvola Local Adaptive Threshold** that removes uneven paper shadows while preserving thin pen strokes, supported by a 1-tap **"Restore Original"** option and tested against real handwritten signatures.
2. **Existing Source Files to Inspect**: `ImageProcessor.kt`, `SignatureStudioScreen.kt`, `OneClickPreparationCoordinator.kt`.
3. **Exact Files to Create or Modify**:
   - Create: `SignatureProcessingEngine.kt`.
   - Modify: `ImageProcessor.kt`, `SignatureStudioScreen.kt`.
4. **Technical Implementation Strategy**:
   - Compute 2D integral image and squared integral image for $O(1)$ local mean $\mu(x,y)$ and standard deviation $\sigma(x,y)$ per pixel.
   - Apply Sauvola adaptive thresholding with soft alpha anti-aliasing along stroke edges so fine ballpoint tails are never clipped.
   - Preserve genuine blue/black ink chromaticity in **Original Ink** mode and crisp monochrome in **Black Ink** mode.
   - Provide an explicit **"Original (No Filter)"** mode and darkness fine-tuning slider.
5. **Dependencies & Licensing**: Pure Kotlin / Android `Bitmap` (Apache 2.0).
6. **Offline/Network Requirements**: 100% Offline.
7. **Security & Privacy Risks**: Scrub signature bitmaps on screen exit.
8. **Automated Tests**: Synthetic uneven shadow gradient tests + bundled test fixtures of real photographed signatures (fine blue ballpoint, black gel pen, angled phone shadow).
9. **Real-Device Tests (Moto G60)**: Capture real signatures on white and slightly yellowish paper under single-bulb shadow on Moto G60.
10. **Acceptance Criteria**: Complete shadow removal to `#FFFFFF` paper without breaking thin pen strokes, plus instant original-image restore.
11. **Dependencies on Earlier Phases**: Phase 0, Phase 1.
12. **Features Explicitly Out of Scope**: Automated signature verification.
13. **Rollback/Regression Considerations**: Preserve existing `SignatureStudioScreen` controls and test tags.

---

### Phase 4 — Smart Document Scanner (4-Corner Detection, Interactive Loupe & Known-Ratio Perspective Warp)
1. **Objective**: Implement 4-corner document detection, an interactive draggable 4-corner handle UI with magnifying loupe, and `Matrix.setPolyToPoly` perspective rectification that respects **true physical document aspect ratios** (`A4`, `A5`, `CR80 ID Card`, or user-adjustable custom ratio).
2. **Existing Source Files to Inspect**: `PdfMasterScreen.kt`, `ImageProcessor.kt`, `OneClickPreparationCoordinator.kt`.
3. **Exact Files to Create or Modify**:
   - Create: `DocumentScannerEngine.kt`, `DocumentCornerCropDialog.kt`.
   - Modify: `PdfMasterScreen.kt`, `OneClickPreparationCoordinator.kt`, `ImageProcessor.kt`.
4. **Technical Implementation Strategy**:
   - Detect candidate 4-corner quad (`TL, TR, BR, BL`) using multi-scale Sobel edge gradients + convex-hull polygon approximation.
   - Present `DocumentCornerCropDialog` with 4 draggable corner handles, a magnifying loupe, and a **Target Document Ratio Selector**:
     - **`A4 Certificate / Marksheet`** ($1 : \sqrt{2} \approx 1 : 1.4142$)
     - **`ID Card (Aadhaar / PAN / Voter ID)`** (`85.60 mm × 53.98 mm` $\approx 1.5858 : 1$)
     - **`Auto / Custom Ratio`** (foreshortening-compensated edge ratio with manual adjustment)
   - Rectify perspective via `android.graphics.Matrix.setPolyToPoly(srcCorners, 0, dstRect, 0, 4)` and apply illumination division normalization for shadow-free text.
5. **Dependencies & Licensing**: Android Native `Matrix.setPolyToPoly` + Kotlin CV (Apache 2.0); benchmarked against OpenCV Android SDK.
6. **Offline/Network Requirements**: 100% Offline.
7. **Security & Privacy Risks**: Local processing only.
8. **Automated Tests**: Projective warp accuracy test verifying tilted `A4` and `ID Card` quads rectify to exact `1:1.4142` and `1.5858:1` target aspect ratios.
9. **Real-Device Tests (Moto G60)**: Scan an Aadhaar card and an A4 marksheet at `20°–35°` camera tilt on dark and light tabletops.
10. **Acceptance Criteria**: Accurate 4-corner detection + manual handle override + distortion-free physical aspect ratio rectification.
11. **Dependencies on Earlier Phases**: Phase 0, Phase 1.
12. **Features Explicitly Out of Scope**: Cloud OCR.
13. **Rollback/Regression Considerations**: Fallback to default 85% inset quad handles if background has low contrast.

---

### Phase 5 — Portal Preset Accuracy, Workflow Separation & GitHub JSON Sync Governance
1. **Objective**: Overhaul `PortalDatabase.kt` so every preset is tied to an explicit application workflow, form type, and submission mode; fix all PAN pixel-vs-mm contradictions; enforce `UNVERIFIED_RULE` status on unverified/remote presets; and harden HTTPS JSON sync.
2. **Existing Source Files to Inspect**: `PortalDatabase.kt`, `PortalPresetSelectorBar.kt`, `PortalStudioScreen.kt`, `DocumentModels.kt`.
3. **Exact Files to Create or Modify**:
   - Modify: `PortalDatabase.kt`, `PortalPresetSelectorBar.kt`, `PortalStudioScreen.kt`, `DocumentModels.kt`.
4. **Technical Implementation Strategy**:
   - **Workflow-Specific PAN Presets**:
     1. **`protean_pan_dsc_online` (Protean / NSDL DSC-Based Online PAN — `OFFICIALLY_VERIFIED` via `tin.tin.proteantech.in`)**:
        - Photo: `200 DPI`, Colour `JPEG`, max `20 KB`, physical `3.5 cm × 2.5 cm` (`276 × 197 px` / `197 × 276 px` @ `200 DPI`).
        - Signature: `200 DPI`, `JPEG`, max `10 KB`, physical `2 cm × 4.5 cm` (`354 × 157 px` @ `200 DPI`).
     2. **`uti_pan_physical_print` (UTIITSL Physical Form 49A Print Guideline — `OFFICIALLY_VERIFIED` via `pan.utiitsl.com`)**:
        - Photo for Physical Form Print: `4.5 cm × 3.5 cm` (`35 mm × 45 mm` = `413 × 531 px @ 300 DPI`), clearly labeled as **"For Physical Form 49A Print Sheet"**.
     3. **`pan_legacy_agency_213` (Legacy / Back-Office Cropping Utility `213×213 px @ 300 DPI` — Marked `UNVERIFIED_RULE` / `User-Selected Agency Spec`)**:
        - Kept separate with no contradictory millimeter claims (`18×18 mm @ 300 DPI`), clearly badged as an unverified legacy agency utility so users who specifically need `213×213 px` can still select it without misleading official claims.
   - **Mathematical Consistency Check**:
     - Add a unit-tested invariant across all `PortalDatabase` rules: whenever `exactWidthPx`, `physicalWidthMm`, and `dpi` are all non-null on a `DocumentRule`, they must satisfy $|\text{exactWidthPx} - (\text{physicalWidthMm} / 25.4 \times \text{dpi})| \le 2\text{ px}$, or else only the authoritative specification (pixels OR physical mm @ DPI) is set.
   - **GitHub JSON Sync Governance & HTTPS Policy**:
     - Assign `VerificationStatus.UNVERIFIED_RULE` to remotely synced JSON rules (never `OFFICIALLY_VERIFIED`).
     - Use standard Android System Trust Store HTTPS certificate validation (`HttpsURLConnection`) with a strict HTTPS host allowlist (`raw.githubusercontent.com`) and configurable repository path (no brittle leaf-certificate pinning that breaks on CA/cert rotation).
5. **Dependencies & Licensing**: Standard Android SDK (`org.json`, `HttpsURLConnection`).
6. **Offline/Network Requirements**: Built-in presets work 100% offline; JSON sync is optional HTTPS.
7. **Security & Privacy Risks**: Strict schema validation, `256 KB` payload cap, HTTPS-only enforcement.
8. **Automated Tests**: Unit test enforcing pixel-to-mm-at-DPI mathematical consistency across every rule in `PortalDatabase`, plus verification status tests for JSON sync.
9. **Real-Device Tests (Moto G60)**: Verify workflow-specific PAN presets and verification badges in `PortalPresetSelectorBar` and `PortalStudioScreen`.
10. **Acceptance Criteria**: Zero mathematical contradictions between `px`, `mm`, and `DPI` in `PortalDatabase`, and zero auto-verified remote presets.
11. **Dependencies on Earlier Phases**: Phase 0.
12. **Features Explicitly Out of Scope**: Server-side rule database.
13. **Rollback/Regression Considerations**: Maintain backward compatibility in `PortalPresetSelectorBar` chip selection.

---

### Phase 6 — PDF Master & Physically Accurate Printable Photo Sheets (Independent of Phase 2)
1. **Objective**: Implement iterative verified PDF compression and physically accurate `4×6 inch` and `A4` printable passport photo sheets in **both `300 DPI` JPEG and exact physical-point PDF formats** with explicit `"Print at 100% / Actual Size"` instructions.
2. **Existing Source Files to Inspect**: `PdfProcessor.kt`, `PdfMasterScreen.kt`, `ImageProcessor.kt`, `PhotoStudioScreen.kt`.
3. **Exact Files to Create or Modify**:
   - Modify: `PdfProcessor.kt`, `PdfMasterScreen.kt`, `ImageProcessor.kt`, `PhotoStudioScreen.kt`.
4. **Technical Implementation Strategy**:
   - **Printable Passport Sheet (`ImageProcessor.createPrintablePassportSheet` & `PdfProcessor.createPrintablePassportSheetPdf`)**:
     - **`4×6 inch` Sheet**: `1800 × 1200 px @ 300 DPI` (`6×4 inch` landscape) = `8` standard `35×45 mm` (`413 × 531 px`) photos at exact `1:1` physical scale with cutting guide lines; plus an exact `432 × 288 pt` (`6×4 inch`) PDF file.
     - **`A4` Sheet**: `2480 × 3508 px @ 300 DPI` (`210×297 mm`) = `12`, `16`, or `20` standard `35×45 mm` (`413 × 531 px`) photos at exact `1:1` physical scale; plus an exact `595 × 842 pt` (`A4`) PDF file.
     - Display a prominent print instruction banner in the UI and in the PDF header margin: **"Print at 100% Scale / Actual Size (Disable 'Fit to Page')"**.
   - **Iterative Verified PDF Compression**:
     - Step through resolution and JPEG stream quality levels, verify final `outFile.length()` on disk, and surface an honest error if `targetMaxKb` is unreachable without destroying legibility.
5. **Dependencies & Licensing**: Android `PdfDocument`, `PdfRenderer`, `Canvas` (Apache 2.0).
6. **Offline/Network Requirements**: 100% Offline.
7. **Security & Privacy Risks**: None.
8. **Automated Tests**: Unit tests verifying exact `413×531 px` tile preservation on `1800×1200` (`4×6"`) and `2480×3508` (`A4`) sheets, PDF page dimensions (`432×288 pt` and `595×842 pt`), and honest PDF compression failure reporting.
9. **Real-Device Tests (Moto G60)**: Export both JPEG and PDF print sheets and verify print preview scale in Android System Print service.
10. **Acceptance Criteria**: 100% physically accurate `35×45 mm` print sheets in both JPEG and PDF formats; honest PDF compression validation.
11. **Dependencies on Earlier Phases**: Phase 0 (Does **not** require Phase 2 AI segmentation to be completed first).
12. **Features Explicitly Out of Scope**: Proprietary printer drivers.
13. **Rollback/Regression Considerations**: Preserve existing print sheet buttons and test tags.

---

### Phase 7 — Document Locker & Metadata Security Hardening
1. **Objective**: Encrypt sensitive Room metadata fields, inspect and report true `AndroidKeyStore` hardware security level (`TRUSTED_ENVIRONMENT` / `STRONGBOX` vs `SOFTWARE`), and minimize disk writes for decrypted previews.
2. **Existing Source Files to Inspect**: `DocumentEncryptionManager.kt`, `DocumentLockerRepository.kt`, `LockerDocumentEntity.kt`, `NoteEntity.kt`, `DocumentLockerScreen.kt`.
3. **Exact Files to Create or Modify**:
   - Modify: `DocumentEncryptionManager.kt`, `DocumentLockerRepository.kt`, `DocumentLockerScreen.kt`, `NotesVaultScreen.kt`.
4. **Technical Implementation Strategy**:
   - **Metadata Protection**: Encrypt sensitive user-entered fields (`title`, `notes`, `originalFileName` in `LockerDocumentEntity` and `title` in `NoteEntity`) using `DocumentEncryptionManager` before storing in SQLite, or store minimal non-identifying labels in SQLite columns while keeping full metadata inside an encrypted header.
   - **True Keystore Hardware Security Inspection**:
     - Query `SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo`.
     - On API 31+, read `keyInfo.securityLevel` (`SECURITY_LEVEL_STRONGBOX`, `SECURITY_LEVEL_TRUSTED_ENVIRONMENT`, `SECURITY_LEVEL_SOFTWARE`); on API 24–30, read `keyInfo.isInsideSecureHardware`.
     - Display the actual measured security level in the Vault Security Info sheet (never claiming hardware-backed encryption on a device/JVM where `securityLevel == SECURITY_LEVEL_SOFTWARE`).
   - **Minimized Disk Exposure for Previews**:
     - Decode image previews (`JPG`/`PNG`) directly in memory (`BitmapFactory.decodeByteArray(decryptedBytes, ...)`) **without writing a temporary plaintext file to flash storage**.
     - Only use a strictly scoped ephemeral file when `PdfRenderer` or external `FileProvider` sharing requires a file descriptor, and unlink it immediately when `DisposableEffect` disposes or `ON_STOP` triggers.
5. **Dependencies & Licensing**: Android `KeyInfo`, `AndroidKeyStore`, `Room` (Apache 2.0).
6. **Offline/Network Requirements**: 100% Offline.
7. **Security & Privacy Risks**: Eliminates plaintext image preview files on flash storage and protects sensitive document titles/notes in SQLite.
8. **Automated Tests**: Unit tests verifying in-memory bitmap decryption produces zero temp files in `cacheDir/decrypted_preview`, encrypted metadata round-trips cleanly, and `KeyInfo` security level reporting works across API levels.
9. **Real-Device Tests (Moto G60)**: Verify `KeyInfo` reports `TRUSTED_ENVIRONMENT` (Qualcomm TEE on Moto G60) and image previews leave zero files in cache.
10. **Acceptance Criteria**: In-memory image previewing, encrypted sensitive metadata, and truthful hardware security status reporting.
11. **Dependencies on Earlier Phases**: Phase 0.
12. **Features Explicitly Out of Scope**: Cloud key backup.
13. **Rollback/Regression Considerations**: Handle migration/reading of existing Room rows gracefully so previously saved locker items remain accessible.

---

### Phase 8 — Offline-First and Network Dependency Audit
1. **Objective**: Audit symbol usage across the entire project (`main`, `debug`, `test`, `androidTest`), remove unused cloud/network dependencies (`firebase-ai`, `firebase-appcheck`, `retrofit`, `okhttp`, `moshi`), and verify 100% offline operation.
2. **Existing Source Files to Inspect**: `app/build.gradle.kts`, `gradle/libs.versions.toml`, all source sets.
3. **Exact Files to Create or Modify**:
   - Modify: `app/build.gradle.kts`.
4. **Technical Implementation Strategy**:
   - Run project-wide symbol search across `app/src/` before commenting out unused dependencies in `app/build.gradle.kts`.
   - Verify `compile_applet` and `:app:testDebugUnitTest` succeed cleanly.
5. **Dependencies & Licensing**: Apache 2.0 AndroidX & local CV libraries only.
6. **Offline/Network Requirements**: 100% Offline core verification.
7. **Security & Privacy Risks**: Removes unused network/cloud SDK code from APK.
8. **Automated Tests**: Full unit test suite after dependency cleanup.
9. **Real-Device Tests (Moto G60)**: Full Airplane Mode test across all modules.
10. **Acceptance Criteria**: Zero unused cloud AI/network libraries in active dependencies; clean build and test pass.
11. **Dependencies on Earlier Phases**: Phases 0–7.
12. **Features Explicitly Out of Scope**: AdMob (Phase 9).
13. **Rollback/Regression Considerations**: Keep removed dependencies commented in `app/build.gradle.kts` per template convention.

---

### Phase 9 — AdMob Monetization & Account Eligibility Governance
1. **Objective**: Plan and integrate optional, non-intrusive AdMob App Open Ads with strict cooldowns, zero interruption during active workflows, offline fallback, and compliance with Google's **18+ account ownership requirement**.
2. **Existing Source Files to Inspect**: `MainActivity.kt`, `MainScreen.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`.
3. **Exact Files to Create or Modify**:
   - Create: `ads/AppOpenAdManager.kt`, `ads/AdPolicyGuard.kt`.
   - Modify: `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`.
4. **Technical Implementation Strategy**:
   - **Account & Age Eligibility Requirement (18+ Rule)**:
     - Both **Google AdMob** and **Google Play Console** require the account owner to be **18 years of age or older** with verified government ID and PAN/bank details. If the developer is under 18, a parent or legal guardian must register and own the Google Play Console and AdMob accounts in their name.
   - **Non-Interruption Guard (`AdPolicyGuard`)**:
     - Suppress ads during camera capture, active photo/signature/PDF processing, file picking/saving intents, and offline sessions.
     - Enforce a 4-hour minimum cooldown and max 2 impressions/day.
5. **Dependencies & Licensing**: `com.google.android.gms:play-services-ads`.
6. **Offline/Network Requirements**: Silent no-op when offline.
7. **Security & Privacy Risks**: Never include document names or user data in ad requests.
8. **Automated Tests**: Unit tests for `AdPolicyGuard` state suppression and cooldowns.
9. **Real-Device Tests (Moto G60)**: Test with official AdMob test unit IDs in online and Airplane modes.
10. **Acceptance Criteria**: Zero workflow interruptions and 100% policy compliance.
11. **Dependencies on Earlier Phases**: Phase 8.
12. **Features Explicitly Out of Scope**: Interstitial ads blocking file downloads.
13. **Rollback/Regression Considerations**: Master kill-switch flag in `AdPolicyGuard`.

---

### Phase 10 — Final QA, Moto G60 Memory/Performance Profiling & Release Preparation
1. **Objective**: Execute comprehensive physical device profiling on Moto G60 measuring **Java Heap**, **Native Heap (`Debug.getNativeHeapAllocatedSize()`)**, and **Total PSS (`Debug.MemoryInfo.totalPss`)**, verify ProGuard/R8 rules, and finalize Google Play Data Safety documentation.
2. **Existing Source Files to Inspect**: `proguard-rules.pro`, `AndroidManifest.xml`, all test suites.
3. **Exact Files to Create or Modify**:
   - Modify: `proguard-rules.pro`, `MemoryTelemetryLogger.kt` (debug diagnostic utility), end-to-end tests.
4. **Technical Implementation Strategy**:
   - Measure actual **Java Heap**, **Native Heap** (where `Bitmap` pixel buffers on Android 8.0+ and `.tflite` tensors reside), and **Total PSS** before, during, and after 10 consecutive `108MP / 12MP` photo, signature, and multi-page PDF operations on Moto G60.
   - Verify zero native memory leaks across repeated studio runs.
5. **Dependencies & Licensing**: None added.
6. **Offline/Network Requirements**: Full offline & online verification.
7. **Security & Privacy Risks**: Final `AndroidManifest.xml` and `file_paths.xml` least-privilege audit.
8. **Automated Tests**: Full Robolectric & JVM regression suite.
9. **Real-Device Tests (Moto G60)**: Complete physical sign-off matrix.
10. **Acceptance Criteria**: Stable Java Heap, Native Heap, and Total PSS across repeated stress runs; 100% test pass rate.
11. **Dependencies on Earlier Phases**: Phases 0–9.
12. **Features Explicitly Out of Scope**: Paid cloud telemetry.
13. **Rollback/Regression Considerations**: Tagged release configuration.

---

## 6. Cost, Licensing & Eligibility Report (Section N)

| Category | Component / Requirement | One-Time Cost | Recurring Cost | License / Policy | Important Notes |
| :--- | :--- | :---: | :---: | :--- | :--- |
| **Core Processing & AI** | On-device Kotlin CV + Bundled `.tflite` | **₹0** | **₹0 / month** | **Apache License 2.0** | Zero cloud API or token fees |
| **Optional Preset Sync** | Owner-controlled GitHub Raw JSON | **₹0** | **₹0 / month** | Public GitHub Repo | Standard HTTPS validation + host allowlist |
| **Google Play Console** | Developer Account Registration | **$25 USD** (One-time) | **₹0 / month** | Google Play Developer Agreement | **Account holder must be 18+ years old** (Parent/Guardian account required if under 18) |
| **AdMob Monetization** | Google AdMob Account (Phase 9) | **₹0** | **₹0 / month** | Google AdMob Policies | **Account holder must be 18+ years old** for identity & payout verification |

---

## 7. Recommended First Step After Plan Approval (Section Q)

Upon your approval of this revised roadmap:
1. We will execute **Phase 0 ONLY** (fixing `hasWhiteBackground` to measure background pixels, adding dual JFIF+EXIF DPI verification, fixing `PdfProcessor.compressPdfToTargetKb()` to verify disk bytes and report failures honestly, and adding 1-tap Original Image restore).
2. We will run `compile_applet` and `:app:testDebugUnitTest`, present the Phase 0 results for your review, and wait for your confirmation before starting Phase 1.
