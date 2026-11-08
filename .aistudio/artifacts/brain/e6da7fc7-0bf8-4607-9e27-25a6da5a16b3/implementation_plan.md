# FORMREADY AI — PHASE 0.4 (REVISED): Critical Remediation Architecture & Safe Implementation Plan

> **Plan Mode Status:** `PLAN REVISION ONLY` — Zero source code, test, Gradle, manifest, dependency, or configuration files were modified.

---

## Epistemic Status Legend (Applied Throughout This Plan)

- **[VERIFIED FACT]:** Directly measured from the container filesystem, Gradle build/test outputs, or `unzip -l` inspection of `app-debug.apk`.
- **[DESIGN PROPOSAL]:** Concrete code/architecture specification proposed for Phase 0.4 implementation.
- **[UNVERIFIED ASSUMPTION]:** Estimate or projection (such as release `.aab` download size or R8 shrinkage) that has not yet been measured by a build tool.
- **[HARDWARE TEST PENDING]:** Behaviour requiring execution on a physical Motorola Moto G60 (`SM7150-AC`) or real camera/ML Kit native runtime.

---

## 1. Current Workspace and Source Synchronization Limitations

| Verification Item | Epistemic Tag | Status & Evidence |
| :--- | :---: | :--- |
| **Git Repository Metadata (`.git`)** | **[VERIFIED FACT]** | **NOT VERIFIED** — Running `git rev-parse HEAD` and `git status` in `/app/applet` returns `fatal: not a git repository (or any of the parent directories): .git` because `.git` metadata is not mounted in the container. |
| **Match Against Commit `95c9e3ff7908fe20565d1b39fd60ffc639f87663`** | **[VERIFIED FACT]** | **NOT VERIFIED** — Cannot cryptographically verify commit identity or ahead/behind status against `vikramkumarmanjhi391-dev/FORMREADY-AI` (`main`). |
| **Presence of Phase 1–5 Files & 63 Passing Unit Tests** | **[VERIFIED FACT]** | **VERIFIED** — All 8 test files (`63` `@Test` methods) pass (`gradle :app:testDebugUnitTest --rerun-tasks --stacktrace` $\rightarrow$ `BUILD SUCCESSFUL in 2m 38s`, `63 passed, 0 failed`), and `gradle :app:assembleDebug --stacktrace` produces `app/build/outputs/apk/debug/app-debug.apk` (`142,158,271` bytes). |

---

## 2. Existing Architecture and Affected Component Map

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. CAMERA & EXIF SINGLE-TRANSFORM PIPELINE (Phase 0.4B)                                  │
│                                                                                          │
│  [Raw JPEG File on Disk]                                                                 │
│     │  (Contains raw sensor pixels + EXIF TAG_ORIENTATION 1..8)                          │
│     ▼                                                                                    │
│  UnifiedMediaInput.decodeBitmapWithExif()  ◄── THE ONLY POINT WHERE EXIF 1..8 IS APPLIED │
│     │  (Produces UprightBitmapSpace: width = uprightW, height = uprightH)                │
│     ▼                                                                                    │
│  CameraViewportMapper.mapPreviewRectToUprightSensorRect()                                │
│     • Input 1: Measured PreviewView (pvWidth, pvHeight) — Portrait OR Landscape          │
│     • Input 2: Measured Overlay Guide Rect (onGloballyPositioned in PreviewView px)      │
│     • Input 3: Upright dimensions (uprightW, uprightH) + Front-Camera Preview Mirror flag│
│     • Output : Normalized crop RectF in UprightBitmapSpace, OR null if any dim <= 0      │
└────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                             ▼
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ 2. DOMAIN VISION PROCESSORS (Phases 0.4A, 0.4D, 0.4E)                                    │
│                                                                                          │
│  ┌──────────────────────────────┐  ┌──────────────────────────┐  ┌────────────────────┐  │
│  │ PortraitVisionProcessor      │  │ SignatureVisionProcessor │  │ DocumentCorner     │  │
│  │ • Separate faceMutex &       │  │ • Sauvola vs Gentle      │  │ Processor          │  │
│  │   segmenterMutex             │  │   Fallback distinguished │  │ • Connected-Contour│  │
│  │ • Sole cleanup owner:        │  │ • Mode-specific ink      │  │   + 4-Edge Normal  │  │
│  │   MainActivity lifecycle     │  │   ratio & bbox thresholds│  │   Gradient Gate    │  │
│  │ • Cold 1st-inference timer   │  │ • Zero portrait mask     │  │ • Mandatory manual │  │
│  │   vs warm inference timer    │  │   fields spoofed         │  │   corner fallback  │  │
│  └──────────────┬───────────────┘  └────────────┬─────────────┘  └─────────┬──────────┘  │
└─────────────────┼───────────────────────────────┼──────────────────────────┼─────────────┘
                  ▼                               ▼                          ▼
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ 3. INDEPENDENT COMPLIANCE vs. USER APPROVAL GATE (Phase 0.4A)                            │
│                                                                                          │
│  ImageProcessor.processImageForRule()                                                    │
│  • technicalBackgroundCompliant : Objective saved-pixel + mask/signature evidence ONLY   │
│  • technicalComplianceMet       : Size + Dims + DPI + (!reqWhiteBg || techBgCompliant)   │
│  • userPreviewAccepted          : Starts FALSE; resets on capture/pick/adjust            │
│  • approvalStatus               : NOT_REQUIRED | PENDING_USER_APPROVAL | APPROVED_BY_USER│
│  • isExportReady                : technicalComplianceMet &&                              │
│                                   (!reqWhiteBg || technicalBackgroundCompliant) &&       │
│                                   (!requiresUserPreviewApproval || userPreviewAccepted)  │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Design for Each Remediation (Corrections 1–8)

### PHASE 0.4A — Preview Approval, Technical Compliance Independence & Signature Validation (Corrections 1, 2, 3)

#### Correction 1 & 2: Unambiguous `isExportReady` Formula & Strict Independence of Technical Compliance and User Approval — **[DESIGN PROPOSAL]**

1. **Canonical State Definitions on `ProcessedFileResult` (`DocumentModels.kt`):**
   - `val technicalBackgroundCompliant: Boolean`:
     Evaluated **exclusively** from objective image-processing evidence and decoded saved-output pixels on disk. It **never** reads `userPreviewAccepted`:
     - **Portrait Photos (`PASSPORT_PHOTO`, `POSTCARD_PHOTO`):**
       ```kotlin
       val technicalBackgroundCompliant =
           segmentationExecuted &&
           backgroundReplacementApplied &&
           maskConfidencePassed &&
           subjectMaskConfidence >= 0.80f &&
           edgeUncertaintyRatio <= 0.18f &&
           whitenessReport.isMaskVerified &&
           whitenessReport.whitePixelRatio >= 0.85f &&
           whitenessReport.meanLuminance >= 242
       ```
     - **Signatures & Thumb Impressions (`SIGNATURE_ENGLISH`, `SIGNATURE_HINDI`, `DECLARATION_HANDWRITTEN`, `THUMB_IMPRESSION_LEFT`, `THUMB_IMPRESSION_RIGHT`):**
       ```kotlin
       val technicalBackgroundCompliant =
           signatureEvidence != null &&
           signatureEvidence.inkPresenceVerified &&
           signatureEvidence.inkRatioWithinBounds &&
           signatureEvidence.strokeBoundingBoxFound &&
           signatureEvidence.paperWhitenessRatio >= 0.85f &&
           whitenessReport.meanLuminance >= 242
       ```
     - **Documents / ID Cards (`isDocumentOrIdRule`):**
       ```kotlin
       val technicalBackgroundCompliant =
           !rule.requiresWhiteBackground || (whitenessReport.whitePixelRatio >= 0.85f && whitenessReport.meanLuminance >= 235)
       ```
   - `val isCompliant: Boolean` (Canonical Technical Document Compliance):
     ```kotlin
     val isCompliant: Boolean =
         decodeSucceeded &&
         !hasConflict &&
         sizeWithinMax &&
         sizeAboveMin &&
         exactDimsMet &&
         boundedDimsMet &&
         dpiVerified &&
         (!rule.requiresWhiteBackground || technicalBackgroundCompliant)
     ```
     *Note:* `isCompliant` reflects **pure technical compliance** of the saved file against the `DocumentRule`. A technically valid file is `isCompliant == true` immediately after processing, even while `userPreviewAccepted == false`.
   - `val requiresUserPreviewApproval: Boolean = true`:
     True for all user-facing preparation flows so no processed file can be marked export/checklist-ready until the user inspects the rendered output preview.
   - `val userPreviewAccepted: Boolean = false`:
     Defaults to `false` everywhere (`OneClickPreparationCoordinator`, `PortalDocumentPrepareScreen`, `PhotoStudioScreen`, `SignatureStudioScreen`, `ProcessedFileResult`). Resets to `false` whenever:
     - A new camera photo is captured or gallery image is picked.
     - Any parameter in `PhotoStudioAdjustBottomSheet`, `SignatureStudioAdjustBottomSheet`, `InteractiveCornerCropOverlay`, or preset selector is changed and re-run.
   - `enum class PreviewApprovalStatus { NOT_REQUIRED, PENDING_USER_APPROVAL, APPROVED_BY_USER }`:
     ```kotlin
     val approvalStatus: PreviewApprovalStatus = when {
         !requiresUserPreviewApproval -> PreviewApprovalStatus.NOT_REQUIRED
         userPreviewAccepted -> PreviewApprovalStatus.APPROVED_BY_USER
         else -> PreviewApprovalStatus.PENDING_USER_APPROVAL
     }
     ```
   - **Corrected, Unambiguous `isExportReady` Formula:**
     ```kotlin
     val isExportReady: Boolean =
         isCompliant &&
         (!rule.requiresWhiteBackground || technicalBackgroundCompliant) &&
         (!requiresUserPreviewApproval || userPreviewAccepted)
     ```
   - **Corrected `PreparationStatus` Assignment (`ImageProcessor.kt`):**
     Add `PENDING_PREVIEW_APPROVAL("Technically Compliant — Pending Preview Approval")` to `PreparationStatus`:
     ```kotlin
     val status: PreparationStatus = when {
         !decodeSucceeded -> PreparationStatus.FAILED
         hasConflict -> PreparationStatus.CONFLICT_LIMITS
         !sizeWithinMax -> PreparationStatus.SIZE_EXCEEDED
         !sizeAboveMin -> PreparationStatus.SIZE_BELOW_MIN
         !exactDimsMet || !boundedDimsMet -> PreparationStatus.DIMENSIONS_MISMATCH
         rule.requiresWhiteBackground && !technicalBackgroundCompliant ->
             if (!measuredNearWhite) PreparationStatus.BACKGROUND_NOT_WHITE
             else PreparationStatus.BACKGROUND_UNVERIFIED
         !dpiVerified -> PreparationStatus.DPI_MISMATCH
         requiresUserPreviewApproval && !userPreviewAccepted ->
             PreparationStatus.PENDING_PREVIEW_APPROVAL
         else -> PreparationStatus.SUCCESS_PREPARED
     }
     ```
   - **Legacy Compatibility Field (`verifiedBackgroundCompliance`):**
     ```kotlin
     @Deprecated(
         message = "Legacy combined field retained for backward compatibility. Use technicalBackgroundCompliant, approvalStatus, and isExportReady in all new code."
     )
     val verifiedBackgroundCompliance: Boolean =
         technicalBackgroundCompliant && (!requiresUserPreviewApproval || userPreviewAccepted)
     ```

2. **Truth-Table Verification of `isExportReady` and `PreparationStatus`:**

| Case | `rule.requiresWhiteBackground` | Other Tech Checks (Size/Dims/DPI) | `technicalBackgroundCompliant` | `requiresUserPreviewApproval` | `userPreviewAccepted` | `isCompliant` (Tech Pass) | `approvalStatus` | `status` (`PreparationStatus`) | `isExportReady` | Proof of Invariant |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| **1** | `true` | `true` | **`false`** (Low mask conf / blue bg) | `true` | `false` | **`false`** | `PENDING_USER_APPROVAL` | `BACKGROUND_UNVERIFIED` / `BACKGROUND_NOT_WHITE` | **`false`** | Technical failure fails immediately. |
| **2** | `true` | `true` | **`false`** (Low mask conf / blue bg) | `true` | **`true`** | **`false`** | `APPROVED_BY_USER` | `BACKGROUND_UNVERIFIED` / `BACKGROUND_NOT_WHITE` | **`false`** | **User acceptance NEVER overrides technical failure.** |
| **3** | `true` | `true` | **`true`** | `true` | **`false`** | **`true`** | `PENDING_USER_APPROVAL` | `PENDING_PREVIEW_APPROVAL` | **`false`** | **Technical pass without user approval is technically compliant (`isCompliant=true`) but remains pending (`isExportReady=false`).** |
| **4** | `true` | `true` | **`true`** | `true` | **`true`** | **`true`** | `APPROVED_BY_USER` | `SUCCESS_PREPARED` | **`true`** | **Technical pass + explicit user approval becomes export-ready.** |
| **5** | **`false`** | `true` | **`false`** (Non-white doc/photo) | `true` | **`false`** | **`true`** | `PENDING_USER_APPROVAL` | `PENDING_PREVIEW_APPROVAL` | **`false`** | Non-white-bg rule is not blocked by white-bg check, only awaits preview approval. |
| **6** | **`false`** | `true` | **`false`** (Non-white doc/photo) | `true` | **`true`** | **`true`** | `APPROVED_BY_USER` | `SUCCESS_PREPARED` | **`true`** | **Rules not requiring white background are NEVER blocked by `technicalBackgroundCompliant == false`.** |
| **7** | **`false`** | `true` | `false` | **`false`** | `false` | **`true`** | `NOT_REQUIRED` | `SUCCESS_PREPARED` | **`true`** | Non-white-bg batch rule with approval not required is export-ready when tech checks pass. |
| **8** | `true` | **`false`** (Size/DPI fail) | `true` | `true` | `true` | **`false`** | `APPROVED_BY_USER` | `SIZE_EXCEEDED` / `DPI_MISMATCH` | **`false`** | Size/dimension/DPI failure always blocks export regardless of background or user approval. |

---

#### Correction 3: Signature & Thumb Impression Validation Semantics & Threshold Calibration — **[DESIGN PROPOSAL]**

1. **Zero Portrait Segmentation Spoofing in `OneClickPreparationCoordinator.autoPrepareSignature()`:**
   - Always pass:
     ```kotlin
     segmentationExecuted = false,
     maskConfidencePassed = false,
     subjectMaskConfidence = 0f,
     edgeUncertaintyRatio = 0f
     ```
2. **Distinguish Sauvola Execution from Gentle Fallback (`SignatureValidationEvidence`):**
   ```kotlin
   enum class SignatureProcessingMethod {
       SAUVOLA_ADAPTIVE_THRESHOLD,
       GENTLE_CONTRAST_FALLBACK
   }

   data class SignatureValidationEvidence(
       val processingMethod: SignatureProcessingMethod,
       val sauvolaExecuted: Boolean,
       val usedGentleFallback: Boolean,
       val fallbackReason: String? = null,
       val verifiedInkPixelCount: Int,
       val inkPixelRatio: Float,
       val minRequiredInkRatio: Float,
       val maxAllowedInkRatio: Float,
       val inkPresenceVerified: Boolean,
       val inkRatioWithinBounds: Boolean,
       val strokeBoundingBoxFound: Boolean,
       val strokeBoxWidthRatio: Float,
       val strokeBoxHeightRatio: Float,
       val paperWhitenessRatio: Float,
       val paperMeanLuminance: Int
   )
   ```
3. **Separate Calibrated Thresholds for Handwritten Signatures vs. Thumb Impressions:**
   Why separate thresholds are necessary:
   - A handwritten running signature consists of thin pen strokes (`1–4 px` wide) with large interior loops of white paper; its ink density within the tight bounding box is typically **1.0% to 22%**.
   - A left/right thumb impression (`LTI`/`RTI`) is a dense oval pad of ink ridges occupying **6.0% to 58%** of the cropped frame.

| Validation Metric | Handwritten Signature (`SIGNATURE_ENGLISH`, `SIGNATURE_HINDI`, `DECLARATION_HANDWRITTEN`) | Thumb Impression (`THUMB_IMPRESSION_LEFT`, `THUMB_IMPRESSION_RIGHT`) | Calibration & Rejection Purpose |
| :--- | :---: | :---: | :--- |
| **`minRequiredInkPixels`** | `>= 25` 3×3-supported ink pixels | `>= 80` 3×3-supported ink pixels | Rejects blank paper and isolated dust specks. |
| **`inkPixelRatio` Range (on saved output)** | **`0.008f .. 0.28f`** (`0.8% .. 28.0%`) | **`0.060f .. 0.58f`** (`6.0% .. 58.0%`) | Lower bound rejects blank/faint scans; upper bound rejects dark shadow blocks or inverted full-black frames. |
| **Stroke Bounding Box Span (`strokeBoxWidthRatio`, `strokeBoxHeightRatio`)** | `widthRatio >= 0.15f` AND `heightRatio >= 0.08f` | `widthRatio >= 0.20f` AND `heightRatio >= 0.20f` | Ensures strokes span a plausible signature line or thumb pad rather than a single corner dot. |
| **Saved-Output Paper Whiteness (`paperWhitenessRatio` on non-ink mask)** | `>= 0.85f` near-white ($\ge 240$) AND `paperMeanLuminance >= 242` | `>= 0.85f` near-white ($\ge 240$) AND `paperMeanLuminance >= 242` | Verifies that uneven gray paper shadows were actually whitened in the final saved JPEG/PNG on disk. |
| **Effect of `usedGentleFallback == true`** | **Does NOT bypass any check above.** If gentle fallback ran on blank paper (`inkPixelRatio < 0.008f`) or left gray shadows (`paperWhitenessRatio < 0.85f`), `technicalBackgroundCompliant` is **`false`**. Even if all 4 checks pass under gentle fallback, `validationMessages` includes `"Note: Processed via gentle contrast fallback; verify stroke legibility."` | Same strict enforcement (`inkPixelRatio in 0.06f..0.58f` and `paperWhitenessRatio >= 0.85f` required). | Prevents `usedGentleFallback` from ever acting as a free pass for blank or shadowed inputs. |

---

### PHASE 0.4B — Camera / EXIF Coordinate Pipeline Without Double-Transformation (Correction 4) — **[DESIGN PROPOSAL]**

#### 1. Exact Specification of the 4 Coordinate Spaces & Single Point of Each Transform
In the previous draft, proposing EXIF coordinate remapping inside `CameraViewportMapper` while `UnifiedMediaInput.decodeBitmapWithExif()` already normalizes all 8 EXIF orientations created a **double-transformation hazard**. Here is the corrected single-transform pipeline:

| Stage | Coordinate Space | Dimensions | Single Transform Applied at This Stage |
| :---: | :--- | :---: | :--- |
| **1** | **`RawFileSpace`** (JPEG file written by `ImageCapture`) | `(rawSensorW, rawSensorH)` | None (raw sensor orientation + embedded `ExifInterface.TAG_ORIENTATION` `1..8`). |
| **2** | **`UprightBitmapSpace`** (`Bitmap` returned by `UnifiedMediaInput.decodeBitmapWithExif`) | `(uprightW, uprightH)` | **EXIF Normalization happens ONCE AND ONLY ONCE here:** `decodeBitmapWithExif` reads `TAG_ORIENTATION` (`NORMAL`, `FLIP_HORIZONTAL`, `ROTATE_180`, `FLIP_VERTICAL`, `TRANSPOSE`, `ROTATE_90`, `TRANSVERSE`, `ROTATE_270`) and applies the `Matrix` rotation/flip so the resulting `Bitmap` is upright (`top = visual top`, `left = unmirrored camera visual left`). |
| **3** | **`PreviewViewSpace`** (`PreviewView` on screen + Compose overlay) | `(pvWidthPx, pvHeightPx)` | CameraX `Preview` scales `UprightBitmapSpace` into `(pvWidthPx, pvHeightPx)` using `ScaleType.FILL_CENTER`. For `LENS_FACING_FRONT`, Android's `PreviewView` applies a **horizontal display mirror** so the user sees a mirror image. |
| **4** | **`CameraViewportMapper.mapPreviewRectToSensorNormalizedRect()`** | Maps `PreviewViewSpace` $\rightarrow$ `UprightBitmapSpace` `[0..1]` | **Applies ONLY:**<br>1. Horizontal mirror inversion (`[left, right] -> [1 - right, 1 - left]`) **if and only if** `isFrontCamera && !isSavedOutputMirrored` (compensating for `PreviewView`'s live selfie mirror).<br>2. Inverse `FILL_CENTER` scale & center-crop offset between `(pvWidthPx, pvHeightPx)` and `(uprightW, uprightH)`.<br>**NEVER re-applies EXIF rotation/transpose to the rectangle**, because `ImageProcessor.cropBitmap` crops the already-decoded `UprightBitmapSpace` `Bitmap`! |

#### 2. Helper to Compute `UprightBitmapSpace` Dimensions Without Decoding Full Pixels
When `UniversalCameraScreen` builds `UnifiedMediaInput(file)` before full bitmap decoding:
- `BitmapFactory.Options().apply { inJustDecodeBounds = true }` reads `(rawW, rawH)` from the file header.
- `ExifInterface(file).getAttributeInt(TAG_ORIENTATION, ORIENTATION_NORMAL)` reads `exifOrientation`.
- `CameraViewportMapper.resolveUprightDimensions(rawW, rawH, exifOrientation)` swaps `(rawW, rawH) -> (rawH, rawW)` **only** for the 4 dimension-swapping EXIF orientations (`ORIENTATION_ROTATE_90`, `ORIENTATION_ROTATE_270`, `ORIENTATION_TRANSPOSE`, `ORIENTATION_TRANSVERSE`) so `ViewportMappingConfig` receives the exact `(uprightW, uprightH)` that `decodeBitmapWithExif()` will produce.
- *(Note: `mapUprightNormalizedRectToRawSensorRect(uprightRectNorm, exifOrientation)` is retained strictly as a standalone pure utility function for callers/tests inspecting raw un-decoded sensor buffers, and is never chained with `decodeBitmapWithExif`).*

#### 3. Live Overlay Measurement (`onGloballyPositioned`) & Strict Unknown-Geometry Gate
- In `UniversalCameraScreen.kt`:
  - Attach `Modifier.onGloballyPositioned` to the `PreviewView` container to record `previewBoundsInRoot: Rect?`.
  - Attach `Modifier.onGloballyPositioned` to the inner cutout box inside `CameraModeGuidanceOverlay` (the `230.dp × 300.dp` passport oval, `130.dp` height signature cutout box, `160.dp × 200.dp` thumb box, or `290.dp × 395.dp` document frame) to record `guideBoundsInRoot: Rect?`.
  - Compute `measuredGuideRectNorm: RectF?` from the intersection of `guideBoundsInRoot` and `previewBoundsInRoot` divided by `(pvWidth, pvHeight)`.
- **Strict Unknown-Geometry Rule:**
  - Remove `?: 1080`, `?: 1920`, `?: 1200`, `?: 1600` in `UniversalCameraScreen.kt`.
  - Remove `?: mode.guideCropRectNorm` in `UnifiedMediaInput.kt`, `PhotoStudioScreen.kt`, and `SignatureStudioScreen.kt`.
  - If `pvWidth <= 0 || pvHeight <= 0 || uprightW <= 0 || uprightH <= 0 || measuredGuideRectNorm == null`, set `mappedSensorCropRectNorm = null` and `geometryVerified = false`.
  - **Downstream Handling When `mappedSensorCropRectNorm == null`:**
    - **Photo rules:** May use `PortraitVisionProcessor` face detection framing if a single clear face is detected; if 0 or multiple faces are detected, set `requiresManualCropConfirmation = true` and prompt the user to adjust crop or retake.
    - **Signature, Thumb Impression, and Document rules:** **Never** invoke face-framing fallback. When `mappedSensorCropRectNorm == null` (or from Gallery), signatures use connected-stroke bounding-box extraction (or manual crop if ambiguous), and documents open `InteractiveCornerCropOverlay` for manual corner confirmation or retake.

---

### PHASE 0.4C — Official Portal Governance & Traceable Source Audit (Correction 5)

#### 1. Strict Domain Allowlist & Conservative Provenance Gate — **[DESIGN PROPOSAL]**
1. **Remove `.gov.in` / `.nic.in` Wildcard Suffix Acceptance:**
   In `OfficialDomainAllowlist.isVerifiedOfficialUrl()`, delete `return host.endsWith(".gov.in") || host.endsWith(".nic.in")`. Only hosts explicitly listed in `VERIFIED_OFFICIAL_DOMAINS` return `true`.
2. **Do NOT Add `onlineservices.nsdl.com` or Keep Unverified Presets as `OFFICIALLY_VERIFIED`:**
   - Per Correction 5, we do **not** add `onlineservices.nsdl.com` to `VERIFIED_OFFICIAL_DOMAINS` without traceable official documentation, and we do **not** invent notification numbers or source clauses.
3. **Traceable Provenance Requirements in `OfficialDomainAllowlist.evaluatePortalProvenance()`:**
   A preset can retain `effectiveStatus == VerificationStatus.OFFICIALLY_VERIFIED` **only** when:
   - `isVerifiedOfficialUrl(portal.officialUrl)` is `true` (explicitly allowlisted domain),
   - `portal.sourceDocumentTitle.isNotBlank()`,
   - `!portal.sourceIdentifier.isNullOrBlank()` (either an official `notificationNumber` or an explicit official brochure/guideline identifier),
   - `portal.applicableWorkflow.isNotBlank()`,
   - `daysSinceLastCheck != null && daysSinceLastCheck in 0..INTERNAL_REVIEW_FRESHNESS_DAYS`, AND
   - Every `DocumentRule` in `portal.documents` has `!rule.sourceClauseRef.isNullOrBlank() && rule.isRuleIndividuallyVerified`.
   - Whenever any of these fields is missing (`null`/blank) or stale (`> 180` days), `evaluatePortalProvenance()` downgrades `effectiveStatus` to `VerificationStatus.OFFICIAL_SOURCE_REVIEW` (or `UNVERIFIED_RULE` if the domain is not in `VERIFIED_OFFICIAL_DOMAINS`).

#### 2. Complete Traceability Table for All Presets in `PortalDatabase.OFFICIAL_PORTALS` — **[VERIFIED FACT / DESIGN PROPOSAL]**

| Portal ID | Exact `officialUrl` in Code | Source Title in Code | Document / Guideline Identifier | Applicable Submission Workflow | Exact Clause / Table Reference | Date Checked in Code | Phase 0.4 Status & Rationale |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `upsc_cse` | `https://upsconline.nic.in` | `UPSC CSE Official Notification Instructions for Photo & Signature` | `UNRECORDED` (No exam year notification No. stored) | Online OTR & CSP Application Upload | `UNRECORDED` | `Feb 2025` ($>180$ days) | **`OFFICIAL_SOURCE_REVIEW`** (Missing notification ID, clause ref, and $>180$ days since check) |
| `ssc_cgl` | `https://ssc.gov.in` | `SSC General Instructions for Document Upload` | `UNRECORDED` | SSC OTR & Online Exam Application (JPEG upload; note live webcam photo also used on portal) | `UNRECORDED` | `Jan 2025` ($>180$ days) | **`OFFICIAL_SOURCE_REVIEW`** (Missing notice ID, clause ref, and $>180$ days since check) |
| `bpsc_cce` | `https://onlinebpsc.bihar.gov.in` | `BPSC Online Application Guidelines and Dimensions` | `UNRECORDED` | BPSC Online Application Portal | `UNRECORDED` | `Feb 2025` | **`OFFICIAL_SOURCE_REVIEW`** (Already conservative in codebase) |
| `rrb_recruitment` | `https://rrbapply.gov.in` | `CEN Railway Recruitment Information Brochure - Document Guidelines` | `UNRECORDED` (No specific CEN No. stored) | Online RRB CEN Application Upload | `UNRECORDED` | `Jan 2025` ($>180$ days) | **`OFFICIAL_SOURCE_REVIEW`** (Missing CEN number, clause ref, and $>180$ days since check) |
| `ibps_bank` | `https://ibps.in` | `IBPS Guidelines for Scanning and Upload of Documents` | `UNRECORDED` | IBPS CRP Online Application (Photo, Sign, LTI, Handwritten Declaration) | `UNRECORDED` | `Feb 2025` ($>180$ days) | **`OFFICIAL_SOURCE_REVIEW`** (Missing annexure clause ref and $>180$ days since check) |
| `nta_exams` | `https://nta.ac.in` | `NTA Information Bulletin - Appendix for Upload Specifications` | `UNRECORDED` | Combined NEET UG / JEE Main / CUET UG Online Form | `UNRECORDED` | `Feb 2025` ($>180$ days) | **`OFFICIAL_SOURCE_REVIEW`** (Aggregates multiple exams without per-bulletin clause refs) |
| `bihar_rtps` | `https://serviceonline.bihar.gov.in` | `Bihar RTPS Document Attachment Guidelines` | `UNRECORDED` | ServicePlus Bihar Citizen Certificate Application | `UNRECORDED` | `Feb 2025` | **`OFFICIAL_SOURCE_REVIEW`** (Already conservative in codebase) |
| `passport_seva` | `https://passportindia.gov.in` | `Passport Seva Photo Specifications and Document Upload Rules` | `UNRECORDED` | Passport Seva Online Portal Supporting Doc Upload | `UNRECORDED` | `Jan 2025` | **`OFFICIAL_SOURCE_REVIEW`** (Already conservative in codebase) |
| `sarathi_parivahan` | `https://sarathi.parivahan.gov.in` | `Parivahan Citizen Portal Upload Parameters (State-specific variations)` | `UNRECORDED` | Sarathi LL/DL Online Upload (varies by State RTO) | `UNRECORDED` | `Mid 2024 (Re-verify with state RTO)` | **`NEEDS_REVERIFICATION`** (Already marked `NEEDS_REVERIFICATION`) |
| `ctet_exam` | `https://ctet.nic.in` | `CTET Information Bulletin Photo Dimensions` | `UNRECORDED` | CBSE CTET Online Application Upload | `UNRECORDED` | `Jan 2025` ($>180$ days) | **`OFFICIAL_SOURCE_REVIEW`** (Missing bulletin cycle/clause ref and $>180$ days) |
| `nsdl_pan` | `https://tin.tin.proteantech.in` *(currently `onlineservices.nsdl.com` in code)* | `Protean (NSDL) DSC-Based Online PAN Application Guidelines` | `Protean TIN DSC PAN Guidelines` | **DSC-Based Online PAN Application (Form 49A / CSF)**: Photo `200 DPI, 3.5×2.5 cm, ≤20 KB JPEG`; Sign `200 DPI, 2×4.5 cm, ≤10 KB JPEG`. *(Legacy `213×213 px @ 300 DPI` preset separated as a distinct third-party/agency scanning preset under review).* | `Protean DSC PAN Photo & Signature Table (200 DPI, 3.5×2.5 cm ≤20 KB; 2×4.5 cm ≤10 KB)` | `Mar 2025` ($>180$ days) | **`OFFICIAL_SOURCE_REVIEW`** (Kept under review per Correction 5; separates DSC 200 DPI workflow from legacy 213×213 preset so square pixels and 3.5×2.5 cm physical dims are never mixed) |
| `uti_pan` | `https://www.pan.utiitsl.com` | `UTIITSL PAN Scanning & Upload Specifications` | `UNRECORDED` | Digital Portal Upload vs. Physical Form 49A (`4.5×3.5 cm` pasted photographs) | `UNRECORDED` | `Mar 2025` ($>180$ days) | **`OFFICIAL_SOURCE_REVIEW`** (Workflow-specific digital scan preset; kept under review with explicit workflow note) |
| `universal_passport` | `https://india.gov.in` | `Universal Government Form Specifications` | `None` (Generic convenience template) | General-purpose `350×450 px` (`3.5×4.5 cm`) photo & `140×60 px` signature | `None` | `Mar 2025` | **`USER_SUPPLIED`** (Not an official examination portal; must never claim `OFFICIALLY_VERIFIED`) |
| `parseAndRegisterPortalsFromJson` | Dynamic JSON URL | `GitHub JSON Synced Specification` | `None` | User/Remote JSON Preset Sync | `None` | `"Synced"` | **`USER_SUPPLIED` / `OFFICIAL_SOURCE_REVIEW`** (Remove hardcoded `OFFICIALLY_VERIFIED` on line 877) |

---

### PHASE 0.4D — Document Detection Reliability, Heuristic Limitations & Manual Fallback (Correction 6)

#### 1. Multi-Stage Connected-Contour & Directional Normal-Gradient Detector — **[DESIGN PROPOSAL]**
To eliminate the Phase 0.3 defect (where global `(x ± y)` contrast extrema silently warped non-document scenes), `DocumentCornerProcessor.detectDocumentCorners()` is redesigned to:
1. Compute a smoothed luminance + Sobel gradient map `(Gx, Gy, mag)` on a downsampled grid (`maxDim = 240`), ignoring a 2-pixel outer frame margin.
2. Extract 8-connected foreground/edge-enclosed candidate components, ranking the top $K$ components by a combined score of area plausibility, convexity, and perimeter gradient strength.
3. Trace each candidate component's outer contour, compute its convex hull, and reduce the hull to a 4-vertex quadrilateral `(tl, tr, br, bl)`.
4. Evaluate **4-Edge Directional Normal-Gradient Continuity**: along each of the 4 sides, sample 32 points and require local gradient magnitude $|\nabla L| \ge 48$, gradient vector alignment within $\pm 25^\circ$ of the edge normal $\hat{n}_i$, and consistent interior-vs-exterior luminance polarity along $\ge 65\%$ of **every** edge (`minOf(e0, e1, e2, e3) >= 0.65f`).
5. **Conservative Auto-Warp Gate (`AUTO_WARP_CONFIDENCE_THRESHOLD = 0.80f`):** Automatic perspective rectification (`autoWarpEligible = true`) is permitted **only** when `confidence >= 0.80f` AND `minEdgeSupport >= 0.65f` AND all 4 interior angles lie in `55°..125°`. In **all** other cases (`autoWarpEligible == false`), the image is **not** silently warped and `InteractiveCornerCropOverlay` is presented for manual 4-corner confirmation.

#### 2. Explicit Engineering Limitations of Heuristic Parameters — **[DESIGN PROPOSAL / UNVERIFIED ASSUMPTION]**
A pure-Kotlin heuristic detector **will not** reliably auto-detect every real-world document scene. Each heuristic threshold has concrete physical trade-offs that justify why `InteractiveCornerCropOverlay` is retained as the mandatory safe fallback:

| Heuristic Parameter | Failure Mode / Physical Limitation | Mitigation & Fallback Strategy |
| :--- | :--- | :--- |
| **1. Minimum Connected-Component Area (`16%` vs. `5%` for ID Cards)** | A small PVC ID card (PAN/Aadhaar, $85.6 \times 54\text{ mm}$) held 40 cm from a wide-angle phone camera often covers only **6%–14%** of the sensor frame. A strict 16% minimum area floor rejects genuine small ID cards. | Lower the candidate discovery floor to **`5%` of frame area** so small ID cards still produce pre-positioned initial corner handles, while requiring `area >= 16%` for *unattended* `autoWarpEligible = true` (cards covering `5%..16%` pre-populate the 4 handles and open `InteractiveCornerCropOverlay` for 1-tap user confirmation). |
| **2. Maximum Connected-Component Area (`92%`) & Border-Touch Limit** | When a user fills 93%–98% of the viewfinder with an A4 page or lets 2 edges of the page touch the screen border, the page merges with the frame boundary or exceeds 92% area. | Do not discard `92%..98%` components if they exhibit strong interior text/contrast; instead, mark `requiresUserConfirmation = true` and initialize the 4 handles near the detected borders. |
| **3. Top-$K$ ($K=4$) Component Selection** | If a document is photographed on a high-contrast patterned tablecloth or keyboard with $>4$ large connected blobs, or if a dark fold/shadow splits a page into two separate components, top-4 component ranking by area alone can miss the true page or select only half the page. | Rank components by `area * boundaryGradientSupport` rather than raw pixel count alone, and fall back to manual 4-corner handles whenever the top candidate fails 4-edge continuity. |
| **4. Four-Edge Directional Continuity (`>= 0.65f` per edge)** | Rounded corners on laminated cards, a user's thumb holding the bottom edge of a certificate, glare spots on glossy paper, or white paper on a light cream desk ($\Delta L < 20$) will drop at least one edge below `0.65f`. | Any single edge dropping below `0.65f` sets `autoWarpEligible = false` and opens `InteractiveCornerCropOverlay` initialized with the best-fit quad so the user can drag the occluded corner. |
| **5. Rectified Aspect-Ratio Bounds (`0.45 .. 2.20`)** | Narrow fee receipts, long caste/income certificates, or wide horizontal declarations may fall outside `0.45..2.20`. | Aspect ratios outside `0.45..2.20` are not rejected from manual cropping; they simply set `requiresUserConfirmation = true` so the user confirms the non-standard shape. |

#### 3. Synthetic + Local Real-Image Validation Protocol (100% On-Device, Zero Cloud Upload)
- **Tier 1 (Automated Robolectric Suite in `Phase5FeaturesTest.kt`):** Deterministic 8-scene corpus (clean dark background, simulated wood-grain desk, multiple rectangular objects, low-contrast white-on-cream, diagonal shadow gradient, clipped 3-edge document, 4-corner background clutter, and blank surface).
- **Tier 2 (Local Real-Photo Corpus on Device — [HARDWARE TEST PENDING]):** Capture 16 real non-sensitive test photographs on the Motorola Moto G60 (blank/specimen forms, printed sample grids, and dummy cards on wood, fabric, bedsheet, and cluttered desk surfaces) stored strictly in local device storage (`app/src/androidTest/assets/` or local device folder — **never** uploading personal/citizen documents to any cloud service) to empirically measure false-positive auto-warp rate (target: `0%` on non-document/cluttered scenes) and manual-fallback rate.

---

### PHASE 0.4E — ML Kit Client Lifecycle, Independent Locks & Single Lifecycle Owner (Correction 7) — **[DESIGN PROPOSAL]**

#### 1. Separate Synchronization for `FaceDetector` and `SelfieSegmenter`
Using a single global `Mutex` for both `FaceDetector` and `SelfieSegmenter` unnecessarily serializes independent models. Instead, `PortraitVisionProcessor` will use **two dedicated, independent resource holders**:
```kotlin
private val faceDetectorMutex = Mutex()
private var cachedFaceDetector: FaceDetector? = null
private var faceDetectorFirstInferenceDone = false

private val segmenterMutex = Mutex()
private var cachedSelfieSegmenter: Segmenter? = null
private var segmenterFirstInferenceDone = false
```
- **Why this is safe and non-blocking:**
  - `mlKitFaceDetectionEngine.detectFaces()` acquires only `faceDetectorMutex.withLock { ... }`.
  - `mlKitSelfieSegmentationEngine.segmentPortrait()` acquires only `segmenterMutex.withLock { ... }`.
  - Neither blocks the other if invoked independently, and within a single client, the `Mutex` guarantees that `.close()` inside `releaseEngines()` can **never** execute while `.process(inputImage).awaitTask()` is in flight on the native JNI thread!

#### 2. Single Clear Lifecycle Owner (`MainActivity` Only)
To prevent conflicting cleanup calls from multiple Composable screens (`PhotoStudioScreen`, `PortalDocumentPrepareScreen`, `PortalStudioScreen`) racing with screen transitions:
- **Sole Lifecycle Owner:** **`MainActivity`** is the **only** component in the application permitted to invoke `PortraitVisionProcessor.releaseEngines()` (specifically in `MainActivity.onStop()` when `!isChangingConfigurations` and in `MainActivity.onTrimMemory(level)` when `level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN`).
- Individual Compose screens **must not** call `releaseEngines()` in `DisposableEffect`. Thus, navigating from `PortalDetailScreen` $\rightarrow$ `PortalDocumentPrepareScreen` $\rightarrow$ `PortalDetailScreen` $\rightarrow$ `PhotoStudioScreen` keeps the native ML Kit clients warm throughout the user's active foreground session, while backgrounding the app immediately frees the native C++/TFLite heaps.

#### 3. Separate Cold First-Inference vs. Warm Inference & Honest Sampled Memory Fields
- In `PortraitVisionProcessor.kt`:
  - Time `FaceDetection.getClient(options)` under `Stage.CLIENT_CONSTRUCTION`.
  - Time the **first** `detector.process(inputImage).awaitTask()` after client creation under `Stage.FACE_DETECTION_COLD_FIRST_INFERENCE` (then set `faceDetectorFirstInferenceDone = true`).
  - Time all subsequent `detector.process(inputImage).awaitTask()` calls on the same cached client under `Stage.FACE_DETECTION_WARM_INFERENCE`.
  - Apply the exact same 3-stage split (`CLIENT_CONSTRUCTION`, `SELFIE_SEGMENTATION_COLD_FIRST_INFERENCE`, `SELFIE_SEGMENTATION_WARM_INFERENCE`) for `SelfieSegmenter`.
  - When `releaseEngines()` runs, reset `faceDetectorFirstInferenceDone = false` and `segmenterFirstInferenceDone = false`.
- In `VisionBenchmarkRecorder.kt`:
  - Record pre-stage and post-stage snapshots (`sampledPreJavaHeapBytes`, `sampledPostJavaHeapBytes`, `sampledJavaHeapDeltaBytes`, `sampledPreNativeHeapBytes`, `sampledPostNativeHeapBytes`, `sampledNativeHeapDeltaBytes`).
  - Rename summary fields to `maxSampledPostJavaHeapBytes`, `maxSampledJavaHeapDeltaBytes`, `maxSampledPostNativeHeapBytes`, and `maxSampledNativeHeapDeltaBytes` so sampled boundary snapshots are never misrepresented as continuous hardware peak memory.

---

## 4. Empirical APK-Size Investigation & Unverified Estimate Disclaimers (Correction 8)

### 1. Verified Empirical Breakdown of `app/build/outputs/apk/debug/app-debug.apk` (`142,158,271` Bytes) — **[VERIFIED FACT]**
Measured directly via `unzip -l app/build/outputs/apk/debug/app-debug.apk`:

| Artifact Component | `arm64-v8a` | `armeabi-v7a` | `x86` | `x86_64` | Total Uncompressed in `app-debug.apk` |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`libxeno_native.so` (ML Kit Selfie Segmentation `16.0.0-beta6`)** | `21,597,896` B (`20.60 MiB`) | `16,008,520` B (`15.27 MiB`) | `19,902,212` B (`18.98 MiB`) | `19,852,240` B (`18.93 MiB`) | **`77,360,868` B (`73.78 MiB`)** |
| **`libface_detector_v2_jni.so` (ML Kit Face Detection `16.1.7`)** | `8,520,960` B (`8.13 MiB`) | `5,388,224` B (`5.14 MiB`) | `9,658,172` B (`9.21 MiB`) | `9,685,400` B (`9.24 MiB`) | **`33,252,756` B (`31.71 MiB`)** |
| **Other Native Libs (`CameraX`, `AndroidX Graphics`, `DataStore`)** | `54,648` B | `39,576` B | `59,520` B | `71,392` B | **`225,136` B (`0.21 MiB`)** |
| **All Native `.so` Libraries (`lib/*`)** | **`30,173,504` B (`28.78 MiB`)** | **`21,436,320` B (`20.44 MiB`)** | **`29,619,904` B (`28.25 MiB`)** | **`29,609,032` B (`28.24 MiB`)** | **`110,838,760` B (`105.70 MiB`)** |
| **Bundled ML Kit Models (`assets/models_bundled/*` + `selfiesegmentation*.tflite`)** | — | — | — | — | **`4,514,208` B (`4.31 MiB`)** |
| **13 Debug DEX Files (`classes.dex` .. `classes13.dex`)** | — | — | — | — | **`78,711,408` B (`75.07 MiB` uncompressed; ~`18.2 MiB` compressed)** |

### 2. Unverified Delivery & Minification Projections — **[UNVERIFIED ASSUMPTION]**
- **Release APK / App Bundle (`.aab`) Size:** **UNVERIFIED** (Not yet built or measured with `bundletool`).
- **Previously Quoted `~16–19 MB` Play Store Download Size & `~80–85%` R8 DEX Savings:** Explicitly marked **UNVERIFIED ASSUMPTION**. While an Android App Bundle (`.aab`) strips the 3 non-target ABIs (`80,665,256` bytes of uncompressed `armeabi-v7a`, `x86`, and `x86_64` `.so` files for an `arm64-v8a` device like the Moto G60) and R8 tree-shakes unused `material-icons-extended` classes, the exact release `.apk` and `.aab` sizes must be measured empirically by running `gradle :app:assembleRelease :app:bundleRelease` and `bundletool get-size total` before claiming any specific megabyte figure.

---

## 5. Revised Implementation Order: Blocker-First, Independently Testable Batches (Correction 9)

Each batch is strictly isolated, puts the **Phase 0.4A Preview Approval & Signature Validation BLOCKER first**, and cannot be marked complete until all listed regression tests and Gradle commands pass.

---

### BATCH 1 (PRIORITY 1 — BLOCKER): Phase 0.4A — Preview Approval, Technical Compliance Independence & Signature Validation

- **Exact Files to Modify:**
  1. `app/src/main/java/com/example/data/model/DocumentModels.kt`
  2. `app/src/main/java/com/example/processing/SignatureVisionProcessor.kt`
  3. `app/src/main/java/com/example/processing/ImageProcessor.kt`
  4. `app/src/main/java/com/example/processing/OneClickPreparationCoordinator.kt`
  5. `app/src/main/java/com/example/ui/screens/PortalDocumentPrepareScreen.kt`
  6. `app/src/main/java/com/example/ui/screens/PhotoStudioScreen.kt`
  7. `app/src/main/java/com/example/ui/screens/SignatureStudioScreen.kt`
  8. `app/src/test/java/com/example/Phase5FeaturesTest.kt`
- **Code Change Boundaries:**
  - Add `PreviewApprovalStatus`, `SignatureProcessingMethod`, `SignatureValidationEvidence`, `PreparationStatus.PENDING_PREVIEW_APPROVAL`, `technicalBackgroundCompliant`, `requiresUserPreviewApproval`, `approvalStatus`, `isExportReady`, and `signatureEvidence` to `DocumentModels.kt`.
  - Change `userPreviewAccepted` default to `false` in `OneClickPreparationCoordinator`, `PortalDocumentPrepareScreen`, `PhotoStudioScreen`, and `SignatureStudioScreen`; reset `userPreviewAccepted = false` on every new capture, gallery pick, or Adjust sheet re-run.
  - Add `OneClickPreparationCoordinator.updateUserPreviewAcceptance(result, accepted)` so toggling the preview acceptance checkbox updates `userPreviewAccepted`, `approvalStatus`, `status`, and `isExportReady` without overriding `technicalBackgroundCompliant`.
  - Remove fake portrait segmentation values (`segmentationExecuted = true`, `0.94f`, `0.04f`) from `autoPrepareSignature()` and compute calibrated `SignatureValidationEvidence` (separate thresholds for signatures `0.008f..0.28f` vs. thumb impressions `0.06f..0.58f`).
- **Required Regression Tests (`Phase5FeaturesTest.kt`):**
  1. `testBatch1IsExportReadyTruthTableAll8Cases` (verifies all 8 rows of the Truth Table in Section 3).
  2. `testBatch1InitialPreviewAcceptanceStartsFalseAndResetsOnParameterChange`.
  3. `testBatch1SignatureAndThumbValidationEvidenceWithoutPortraitSpoofing` (tests Sauvola pass, gentle fallback on valid ink, gentle fallback rejection on blank/shadowed paper, and separate LTI/RTI vs. signature ink ratio bounds).
- **Build & Verification Commands:**
  `gradle :app:testDebugUnitTest --rerun-tasks --stacktrace && gradle :app:assembleDebug --stacktrace`
- **Acceptance Criteria:**
  - `userPreviewAccepted` is `false` on all newly processed outputs until explicitly accepted.
  - `technicalBackgroundCompliant` is `true` for technically valid outputs even when `userPreviewAccepted == false` (`status == PENDING_PREVIEW_APPROVAL`, `isCompliant == true`, `isExportReady == false`).
  - `userPreviewAccepted = true` on a technically failing background keeps `technicalBackgroundCompliant == false`, `isCompliant == false`, and `isExportReady == false`.
  - `autoPrepareSignature` reports `segmentationExecuted == false`, `subjectMaskConfidence == 0f`, `edgeUncertaintyRatio == 0f`, and populates `signatureEvidence`.
  - 100% of unit/Robolectric tests pass (`0` failures).
- **Rollback Method:**
  Revert Batch 1 changes in `ImageProcessor.kt`, `OneClickPreparationCoordinator.kt`, and the 3 studio screens while keeping additive model fields defaulted in `DocumentModels.kt`.

---

### BATCH 2 (PRIORITY 2 — HIGH): Phase 0.4B — Camera/EXIF Single-Transform Pipeline & Unknown-Geometry Gate

- **Exact Files to Modify:**
  1. `app/src/main/java/com/example/camera/CameraViewportMapper.kt`
  2. `app/src/main/java/com/example/camera/UnifiedMediaInput.kt`
  3. `app/src/main/java/com/example/camera/UniversalCameraScreen.kt`
  4. `app/src/main/java/com/example/camera/UniversalCameraController.kt`
  5. `app/src/main/java/com/example/ui/screens/PhotoStudioScreen.kt`
  6. `app/src/main/java/com/example/ui/screens/SignatureStudioScreen.kt`
  7. `app/src/test/java/com/example/Phase5FeaturesTest.kt`
- **Code Change Boundaries:**
  - Remove `1080×1920` and `1200×1600` fallback dimensions in `UniversalCameraScreen.kt` and remove `?: mode.guideCropRectNorm` fallbacks in `UnifiedMediaInput.kt`, `PhotoStudioScreen.kt`, and `SignatureStudioScreen.kt`.
  - Measure live `PreviewView` bounds and inner guide cutout bounds via `Modifier.onGloballyPositioned` in `UniversalCameraScreen.kt`.
  - Add `CameraViewportMapper.resolveUprightDimensions(rawW, rawH, exifOrientation)` and support all 8 EXIF orientations without double-transforming post-EXIF bitmaps; remove the portrait-only `min/max` swap in `cropUprightSensorBitmapToPreviewGuide`.
  - Ensure unknown geometry (`mappedSensorCropRectNorm == null`) never triggers face-framing fallback for signatures or documents, requiring manual crop confirmation or retake.
- **Required Regression Tests (`Phase5FeaturesTest.kt`):**
  1. `testBatch2All8ExifOrientationsSingleTransformPipeline` (end-to-end test writing synthetic asymmetric images with all 8 EXIF orientation tags `1..8`, decoding via `UnifiedMediaInput.decodeBitmapWithExif`, and mapping preview rects in both portrait `1080×2400` and landscape `2400×1080` `PreviewView` layouts for front and rear cameras).
  2. `testBatch2UnknownCameraGeometryReturnsNullCropAndNeverSilentlyCrops`.
- **Build & Verification Commands:**
  `gradle :app:testDebugUnitTest --rerun-tasks --stacktrace && gradle :app:assembleDebug --stacktrace`
- **Acceptance Criteria:**
  - Zero fallback dimension constants (`1080`, `1920`, `1200`, `1600`) or static guide fraction fallbacks remain in camera crop mapping.
  - All 8 EXIF orientations and both portrait/landscape `PreviewView` dimensions map within $\le 1\text{ px}$ of expected upright coordinates without double rotation/flip.
- **Rollback Method:**
  Restore `CameraViewportMapper.kt` and `UniversalCameraScreen.kt` while keeping `mappedSensorCropRectNorm = null` as the safe default.

---

### BATCH 3 (PRIORITY 3 — HIGH): Phase 0.4C — Strict Official Portal Allowlist & Conservative Provenance Governance

- **Exact Files to Modify:**
  1. `app/src/main/java/com/example/data/model/DocumentModels.kt`
  2. `app/src/main/java/com/example/data/portal/OfficialDomainAllowlist.kt`
  3. `app/src/main/java/com/example/data/portal/PortalDatabase.kt`
  4. `app/src/test/java/com/example/ExampleUnitTest.kt`
  5. `app/src/test/java/com/example/Phase5FeaturesTest.kt`
- **Code Change Boundaries:**
  - Remove `host.endsWith(".gov.in") || host.endsWith(".nic.in")` from `OfficialDomainAllowlist.isVerifiedOfficialUrl()`.
  - Add `sourceIdentifier`, `applicableWorkflow` to `PortalSpec` and `sourceClauseRef`, `isRuleIndividuallyVerified` to `DocumentRule`.
  - Update `OfficialDomainAllowlist.evaluatePortalProvenance()` so missing `sourceIdentifier`, missing `sourceClauseRef`, unparseable date, or `daysSinceLastCheck > 180` downgrades `effectiveStatus` to `VerificationStatus.OFFICIAL_SOURCE_REVIEW` (and unlisted domains to `UNVERIFIED_RULE`).
  - Update `PortalDatabase.kt` presets (`universal_passport` $\rightarrow$ `USER_SUPPLIED`, remote JSON sync $\rightarrow$ `OFFICIAL_SOURCE_REVIEW`, Protean DSC PAN `200 DPI, 3.5×2.5 cm ≤20 KB / 2×4.5 cm ≤10 KB` workflow separation under `OFFICIAL_SOURCE_REVIEW`).
- **Required Regression Tests (`Phase5FeaturesTest.kt` & `ExampleUnitTest.kt`):**
  1. `testBatch3UnlistedGovInAndNicInDomainsAreRejected`.
  2. `testBatch3IncompleteOrStaleProvenanceDowngradesToOfficialSourceReview`.
  3. `testBatch3PanWorkflowSeparationAndUniversalPresetStatus`.
- **Build & Verification Commands:**
  `gradle :app:testDebugUnitTest --rerun-tasks --stacktrace && gradle :app:assembleDebug --stacktrace`
- **Acceptance Criteria:**
  - Arbitrary `.gov.in` / `.nic.in` domains not in `VERIFIED_OFFICIAL_DOMAINS` return `isVerifiedOfficialUrl == false`.
  - No preset without complete, non-stale source & rule clause provenance is reported as `OFFICIALLY_VERIFIED`.
- **Rollback Method:**
  Revert `OfficialDomainAllowlist.kt` and `PortalDatabase.kt` status fields without affecting any `DocumentRule` processing logic.

---

### BATCH 4 (PRIORITY 4 — HIGH): Phase 0.4D — Connected-Contour Document Detection & 8-Scene Validation Corpus

- **Exact Files to Modify:**
  1. `app/src/main/java/com/example/processing/DocumentCornerProcessor.kt`
  2. `app/src/main/java/com/example/ui/screens/PortalDocumentPrepareScreen.kt`
  3. `app/src/test/java/com/example/Phase5FeaturesTest.kt`
- **Code Change Boundaries:**
  - Replace global `(x ± y)` candidate extrema in `DocumentCornerProcessor.kt` with 8-connected component filtering (`5%..92%` area discovery, `16%..92%` auto-warp eligibility), contour hull 4-vertex simplification, and 4-edge directional normal-gradient continuity (`minEdgeSupport >= 0.65f`, `confidence >= 0.80f`).
  - Ensure ambiguous detections (`autoWarpEligible == false`) never auto-warp and always open `InteractiveCornerCropOverlay` for manual corner confirmation.
- **Required Regression Tests (`Phase5FeaturesTest.kt`):**
  1. `testBatch4DocumentCornerDetection8SceneValidationCorpus` (tests all 8 scenes: clean document on dark desk, textured wooden desk, multiple rectangular objects, low-contrast white-on-cream, diagonal shadow, clipped document, 4-corner background clutter, and blank surface).
- **Build & Verification Commands:**
  `gradle :app:testDebugUnitTest --rerun-tasks --stacktrace && gradle :app:assembleDebug --stacktrace`
- **Acceptance Criteria:**
  - Cluttered-corner, blank, low-contrast, and clipped scenes all return `autoWarpEligible == false` (`requiresUserConfirmation == true`).
  - Clean high-contrast 4-edge documents return `autoWarpEligible == true` with corner error $\le 0.05$.
- **Rollback Method:**
  Revert `DocumentCornerProcessor.detectDocumentCorners()` to return `DocumentQuadNorm.defaultInsetFrame()` (manual 4-corner confirmation for all documents).

---

### BATCH 5 (PRIORITY 5 — MEDIUM): Phase 0.4E — Dual-Lock ML Kit Lifecycle (`MainActivity`-Owned) & Sampled Memory Telemetry

- **Exact Files to Modify:**
  1. `app/src/main/java/com/example/processing/VisionBenchmarkRecorder.kt`
  2. `app/src/main/java/com/example/processing/PortraitVisionProcessor.kt`
  3. `app/src/main/java/com/example/MainActivity.kt`
  4. `app/src/test/java/com/example/Phase5FeaturesTest.kt`
- **Code Change Boundaries:**
  - Replace per-image client creation/closing in `PortraitVisionProcessor.kt` with separate `faceDetectorMutex`-guarded `cachedFaceDetector` and `segmenterMutex`-guarded `cachedSelfieSegmenter`.
  - Wire `PortraitVisionProcessor.releaseEngines()` exclusively to `MainActivity.onStop()` (when `!isChangingConfigurations`) and `MainActivity.onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`.
  - Separate `CLIENT_CONSTRUCTION`, `*_COLD_FIRST_INFERENCE`, and `*_WARM_INFERENCE` stages, and replace `peak*HeapBytes` with `sampledPre*`, `sampledPost*`, and `sampled*DeltaBytes` in `VisionBenchmarkRecorder.kt`.
- **Required Regression Tests (`Phase5FeaturesTest.kt`):**
  1. `testBatch5MlKitClientReuseConcurrentLocksAndLifecycleRelease`.
  2. `testBatch5VisionBenchmarkRecorderSampledPrePostMemoryAndColdWarmStages`.
- **Build & Verification Commands:**
  `gradle :app:testDebugUnitTest --rerun-tasks --stacktrace && gradle :app:assembleDebug --stacktrace`
- **Acceptance Criteria:**
  - First inference after initialization or `releaseEngines()` records `*_COLD_FIRST_INFERENCE`; subsequent inferences record `*_WARM_INFERENCE`.
  - Concurrent face detection and segmentation calls do not block each other and never race with `releaseEngines()`.
  - Memory telemetry fields accurately report pre/post stage sampled heap bytes and deltas.
- **Rollback Method:**
  Revert `MainActivity.kt`, `PortraitVisionProcessor.kt`, and `VisionBenchmarkRecorder.kt` independently of Batches 1–4.

---

## 6. Summary of Pending Hardware Validation — **[HARDWARE TEST PENDING]**

The following validations require physical execution on a **Motorola Moto G60 (`SM7150-AC`, Snapdragon 732G, 6 GB RAM, Android 12, `arm64-v8a`)** and are **NOT** claimed from JVM/Robolectric tests:
1. Empirical median and p95 latencies (over 20 iterations on 12 MP `3000×4000` and 108 MP `9000×12000` captures) for `CLIENT_CONSTRUCTION`, `FACE_DETECTION_COLD_FIRST_INFERENCE`, `FACE_DETECTION_WARM_INFERENCE`, `SELFIE_SEGMENTATION_COLD_FIRST_INFERENCE`, `SELFIE_SEGMENTATION_WARM_INFERENCE`, `SAUVOLA_SIGNATURE`, `DOCUMENT_CORNER_WARP`, and `END_TO_END_PREPARATION`.
2. Thermal throttling and native heap stability across 25 consecutive portrait/document preparations.
3. Local 16-photo real-world document corner detection accuracy and false-positive evaluation across wooden, fabric, and cluttered desk surfaces.
4. Empirical `bundletool` size measurement of `:app:bundleRelease` for the Moto G60 device spec.
