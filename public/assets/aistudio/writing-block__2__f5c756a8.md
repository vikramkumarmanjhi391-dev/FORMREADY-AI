# FORMREADY AI — PHASE 0.1: FINAL VALIDATION AUDIT & REMEDIATION

Act as a Senior Android Architect, Kotlin Image Processing Engineer and Quality Assurance Specialist.

We are continuing the existing FORMREADY AI project.

**GitHub repository:** `vikramkumarmanjhi391-dev/FORMREADY-AI`  
**Branch:** `main`  
**Latest baseline commit:** `3543a825fefcfe0a2a3ad3c574c2266313d22289`

Phase 0 has been implemented and pushed. Do not recreate its features. First inspect the actual current workspace and identify the remaining correctness issues.

## STRICT SCOPE

Implement Phase 0.1 only.

Do not start Phase 1 camera coordinate mapping.
Do not add portrait segmentation.
Do not add Sauvola signature processing.
Do not add document corner detection.
Do not change portal specifications.
Do not add AdMob.
Do not refactor unrelated modules.

## 1. BACKGROUND COMPLIANCE — CRITICAL

Inspect `ImageProcessor.measureBackgroundWhiteness()` and all callers.

Current concern:
The code can use perimeter sampling to estimate background whiteness while marking the result as unverified. However, `whiteBgMet` currently depends on `measuredWhiteBackground`, which may allow an unverified perimeter estimate to pass strict compliance.

Required behavior:

- An unverified perimeter sample must never be presented as verified segmentation-mask compliance.
- Separate `measuredNearWhite` from `verifiedBackgroundCompliance`.
- When a rule requires a white background, only mark it compliant when the validation method is sufficiently reliable and explicitly documented.
- If no reliable segmentation mask exists, report the background status as unverified and do not claim verified compliance.
- Preserve near-white ratio, exact-white ratio and mean luminance for informational display.
- Do not fabricate a mask or label a perimeter estimate as AI verification.

Add tests proving that a perimeter-only estimate cannot produce verified background compliance.

## 2. MINIMUM AND MAXIMUM KB REQUIREMENTS

Inspect:

`targetMinKb = rule.minKb.coerceAtMost(targetMaxKb)`

Do not silently lower the required minimum KB.

Required behavior:

- If minimum KB exceeds maximum KB, return an explicit requirements conflict.
- Preserve the original rule values.
- Never modify portal requirements silently.
- Validate the actual saved file size in bytes.
- Add tests for minKb > maxKb, below-minimum output and above-maximum output.

## 3. DPI METADATA — FORMAT-SPECIFIC VALIDATION

Inspect JPEG and PNG output paths.

Required behavior:

- JPEG: verify JFIF and EXIF metadata after saving.
- PNG: verify actual resolution metadata if the application claims to write it.
- Never report the requested DPI as verified merely because it was selected in the UI.
- If the format does not support or preserve the expected metadata, report that limitation honestly.
- Keep pixel dimensions separate from physical print resolution.

Add tests for JPEG and PNG behavior.

## 4. MEMORY AND RESOURCE SAFETY

Inspect `ImageProcessor.processImageForRule()`.

Required behavior:

- Avoid unnecessary full-file `readBytes()` copies for large output files.
- Use bounded or streaming reads where practical.
- Ensure all intermediate bitmaps are recycled only when owned by the processing method.
- Never recycle the caller-owned original bitmap.
- Check every transformation that creates a new bitmap and release replaced intermediate bitmaps safely.
- Avoid double recycling and use-after-recycle errors.
- Preserve existing processing behavior.

Add focused resource-lifecycle tests where possible.

## 5. PDF COMPRESSION REVIEW

Inspect the current iterative compression implementation.

Verify:
- Actual file size is measured after writing.
- Original page count and order are preserved.
- Physical page geometry remains unchanged.
- Every page retains its aspect ratio.
- Temporary candidate files are deleted.
- Unreadable or corrupted PDFs fail safely.
- Impossible size targets return `metTarget = false`.

Do not change working PDF geometry logic unless a real defect is found.

## 6. TESTS AND BUILD

Run:

`./gradlew :app:testDebugUnitTest`

`./gradlew :app:assembleDebug`

Report actual command output and test counts. Do not claim success for skipped tests.

## 7. FINAL REPORT

Provide:

A. Exact files modified.
B. Every issue found.
C. Every issue fixed.
D. Tests added and results.
E. Debug APK build result.
F. Remaining limitations.
G. Confirmation that Phase 1 and later phases were not started.

Do not claim real Moto G60 hardware testing unless it was actually performed.

After Phase 0.1, stop and wait for review.