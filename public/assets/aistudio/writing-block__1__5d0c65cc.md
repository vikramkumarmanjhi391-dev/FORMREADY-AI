# FORMREADY AI — PHASE 0 COMPLETION & TECHNICAL REMEDIATION

Act as a Senior Android Architect, Kotlin Engineer, Image Processing Specialist and PDF Engineering Expert.

We are continuing the existing FORMREADY AI Android project.

CURRENT GITHUB BASELINE:
Repository: vikramkumarmanjhi391-dev/FORMREADY-AI
Branch: main
Latest commit: 9bb4a3710bf96406363af80edd409b41211329c5

The latest commit already introduced:
- Background whiteness measurement.
- JFIF + EXIF DPI metadata injection.
- PDF iterative compression.
- PDF target-size UI verification.
- Photo and Signature fallback controls.
- Additional Phase 0 tests.

Do NOT recreate these features from scratch. Inspect their actual current implementation and complete the unfinished work.

IMPORTANT: Implement Phase 0 ONLY.

Do not start Phase 1 camera mapping.
Do not implement AI segmentation.
Do not implement Sauvola signature processing.
Do not implement document corner detection.
Do not change portal specifications or GitHub JSON sync.
Do not implement AdMob.
Do not change unrelated app architecture.

## 1. FIRST — AUDIT THE CURRENT WORKSPACE

Inspect the current source files and compare them with the latest committed baseline.

Required files to inspect:
- ImageProcessor.kt
- DocumentModels.kt
- PdfProcessor.kt
- PdfMasterScreen.kt
- PhotoStudioScreen.kt
- SignatureStudioScreen.kt
- QualityInspector.kt
- OneClickPreparationCoordinator.kt
- Phase5FeaturesTest.kt
- All callers of the affected processing methods.

Before editing, identify existing APIs, callers, tests and compatibility constraints.

Do not assume the GitHub snapshot and current AI Studio workspace are identical. Report any differences.

## 2. IMAGE PROCESSING — HONEST FINAL-FILE VALIDATION

Current problem:
ImageProcessor currently calls measureBackgroundWhiteness() using the pre-compression `scaled` bitmap, even though the output file has already been written.

Correct this.

Requirements:
1. Read and inspect the actual output file after writing it to disk.
2. Decode the saved image safely for final validation.
3. Measure actual final pixel dimensions.
4. Measure actual final file bytes from disk.
5. Read back JFIF DPI metadata.
6. Read back EXIF XResolution, YResolution and ResolutionUnit.
7. Release temporary decoded bitmaps and streams safely.
8. Ensure validation results describe the saved file, not a pre-compression bitmap.

Background validation:
- Do not set `hasWhiteBackground` merely because the user requested white background.
- Do not call an estimated ellipse a verified AI segmentation mask.
- Use an actual background mask if a reliable one exists.
- If no sufficiently reliable mask exists, report background status as unverified/uncertain rather than claiming successful verification.
- Preserve useful measured values such as white-pixel ratio and luminance.
- Clearly distinguish near-white tolerance from exact #FFFFFF.

CRITICAL COMPLIANCE FIX:
If a rule requires a white background and the final output does not pass the defined validation threshold, set `isCompliant = false` and return an appropriate failure/warning status.

A validation message alone is not enough.

Exact dimensions:
If exactWidthPx and exactHeightPx are required, any final output dimension mismatch must fail exact-dimension compliance.

Do not leave `isCompliant = true` merely because a downscale was necessary to meet the maximum KB.

All compliance conditions must be combined into one deterministic final result.

## 3. REMOVE JPEG MINIMUM-KB METADATA PADDING

The current ImageProcessor still calls:

`padJpegBytesToTargetMinKb()`

Remove its use as a compliance strategy.

Requirements:
- Do not inject JPEG COM segments solely to increase file size.
- Do not add artificial image noise or metadata bytes to satisfy a minimum KB limit.
- Keep genuine JPEG encoding and quality optimization.
- Verify the actual output size after saving.
- When actual bytes are below the required minimum, report SIZE_BELOW_MIN and `isCompliant = false`.
- Do not silently claim portal compliance.

Update `ProcessedFileResult` only as needed, preserving existing callers and avoiding unnecessary API breakage.

If `wasPaddedToMinKb` must remain temporarily for compatibility, it must never report true for newly generated files.

Update tests that expected artificial padding.

## 4. PDF COMPRESSION — PRESERVE ORIGINAL DOCUMENT GEOMETRY

Inspect the new `compressPdfToTargetKb()` implementation carefully.

Current concerns:
- Output page dimensions are hardcoded around A4.
- Compression scale can change the output page's physical size.
- Pages may be stretched into the output destination rectangle.
- Multiple rendered bitmaps remain in memory during each compression pass.
- Input PDF page count and original page geometry are not explicitly verified after writing.
- targetMaxKb is silently coerced to at least 10 KB.

Fix these issues.

Requirements:
1. Preserve each original PDF page's physical width and height.
2. Do not force every input PDF to A4.
3. Do not change the physical page dimensions merely because raster resolution has been reduced.
4. Preserve each page's aspect ratio; never stretch content to an unrelated rectangle.
5. Use lower render resolution and JPEG quality as compression controls without changing original page geometry.
6. Preserve original page count and page order.
7. Release page bitmaps and intermediate resources safely after use.
8. Handle empty, unreadable, corrupted and unusually large PDFs safely.
9. Avoid unbounded memory growth when processing multi-page PDFs.
10. Reject invalid targetMaxKb values explicitly instead of silently changing the requested target.
11. After writing the final PDF, reopen it and verify:
   - It is a valid PDF.
   - Actual file size in bytes.
   - Page count.
   - Physical page dimensions.
   - Page aspect ratios.
12. If the minimum readable candidate still exceeds the target, return the file with an honest `metTarget = false` result.

Preserve existing method callers, or update every caller and test if a structured result is introduced.

Do not claim readability is guaranteed merely because a smaller PDF was produced.

## 5. PDF MASTER UI

Inspect the current PDF compression result card.

Ensure:
- Success is shown only when the actual written file meets the requested limit and passes PDF validation.
- Oversized output gets a warning, not a success indicator.
- Actual size is displayed.
- If page count or dimensions changed unexpectedly, show a validation failure.
- Saving/sharing still works for valid output even if the requested size target could not be met.

Avoid misleading success messages.

## 6. PHOTO AND SIGNATURE FALLBACK LABELS

Inspect the newly added fallback buttons.

The current Photo fallback disables white-background replacement but still runs crop, resize, compression and optional name/date stamping.

The Signature fallback uses a gentle processing configuration and is not a bit-for-bit restoration of the original source.

Therefore:
- Do not claim that these actions restore the original file exactly.
- Use labels that accurately describe their actions, such as “Keep Original Background” and “Gentle Ink Processing”.
- Preserve the original source bitmap until the user exits or replaces the input.
- If an actual Original Preview/Export feature already exists, retain it.
- Do not add misleading Toast messages.

Do not remove existing processing controls.

## 7. AUTOMATED TESTS

Update existing Phase5FeaturesTest.kt and add focused tests where required.

Required test cases:

IMAGE:
- A colored background must not pass white-background compliance.
- A rule requiring a white background must fail compliance if final saved output does not meet the measured threshold.
- Exact-dimension mismatch after downscaling must fail compliance.
- Minimum KB not met must return SIZE_BELOW_MIN.
- No JPEG COM metadata padding is applied.
- Actual file size and final dimensions match the saved output.
- JFIF and EXIF DPI readback must work for both X and Y resolution and resolution unit.

PDF:
- Compress a multi-page PDF and preserve page count and page order.
- Preserve original A4 page dimensions.
- Preserve a non-A4 page's original physical dimensions.
- Preserve page aspect ratios.
- A valid file below the requested KB limit reports metTarget=true.
- An impossible target reports metTarget=false without falsely claiming success.
- Invalid PDF input fails safely.
- Test resource cleanup and repeated compression.

UI:
- Verify that PDF result labels agree with the actual final disk size.
- Verify truthful fallback labels and behavior.

Tests must exercise real processing logic, not merely test method names or hardcoded values.

## 8. BUILD AND TEST VERIFICATION

Run the actual available Gradle commands:

`./gradlew :app:testDebugUnitTest`

`./gradlew :app:assembleDebug`

If either command fails:
- Show the actual error.
- Fix errors caused by Phase 0 changes.
- Rerun the affected command.
- Do not hide skipped or failed tests.

Do not claim tests passed unless the command output confirms it.

Do not claim Moto G60 physical verification; that requires the user to test on the phone.

## 9. STRICT SCOPE CONTROL

Only modify files required to complete Phase 0.

Do not:
- Modify camera classes.
- Add TFLite models.
- Add new document scanner engines.
- Add Sauvola processing.
- Change portal dimensions or official preset rules.
- Remove Firebase/network dependencies in this phase.
- Add AdMob.
- Change applicationId, package name or signing configuration.
- Rebuild the app from scratch.
- Delete working existing features.

Preserve existing Compose testTags and public processing APIs wherever practical.

## 10. FINAL REPORT

After implementation, provide:

A. Exact files modified.
B. Bugs fixed.
C. Tests added or changed.
D. Gradle test result.
E. Debug APK build result.
F. Known remaining limitations.
G. Manual Moto G60 checks still required.
H. Confirmation that Phase 1 and later phases were not started.

Do not claim that the entire FORMREADY AI application is complete.

Complete Phase 0 only, then stop and wait for review.