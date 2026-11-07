# FORMREADY AI — COMPLETE WORKFLOW, CAMERA INTELLIGENCE & PREMIUM UI/UX RE-ARCHITECTURE

## MODE: PLAN MODE ONLY — DO NOT MODIFY CODE

Act as a senior Android architect, Kotlin Jetpack Compose specialist, on-device computer vision engineer, image-processing specialist, and premium mobile UX designer.

My objective is to transform FORMREADY AI from a collection of document-processing screens into a genuinely intelligent, automated, privacy-first government application document preparation app.

The current repository is:

`vikramkumarmanjhi391-dev/FORMREADY-AI`

Current known baseline commit:

`1e433138968cfd42dd93d4e028203bf9e58701d1`

First inspect the actual latest main branch. Do not assume the above commit is still latest.

IMPORTANT: This is an architecture and implementation-planning task only. Do not edit files, generate code changes, create commits, push to GitHub, or modify any repository.

---

## 1. FIRST: AUDIT THE ACTUAL EXISTING IMPLEMENTATION

Inspect the actual source code, not just documentation or comments.

At minimum inspect:

- `UniversalCameraScreen.kt`
- `UniversalCameraController.kt`
- `UnifiedMediaInput.kt`
- `OneClickPreparationCoordinator.kt`
- `ImageProcessor.kt`
- `QualityInspector.kt`
- `PhotoStudioScreen.kt`
- `SignatureStudioScreen.kt`
- `PortalStudioScreen.kt`
- `PdfMasterScreen.kt`
- `CommonComponents.kt`
- `PortalPresetSelectorBar.kt`
- `MainScreen.kt`
- `DocumentModels.kt`
- `PortalDatabase.kt`
- `build.gradle.kts`
- Existing unit and instrumented tests.

For every relevant feature, classify it as:

1. Fully implemented and tested.
2. Partially implemented.
3. Implemented using a fixed heuristic rather than real detection.
4. Not implemented.
5. Implemented but not reliably validated.

Do not describe a fixed crop rectangle, basic thresholding, or perimeter sampling as AI detection or segmentation.

Identify existing reusable code so that we do not rebuild features unnecessarily.

## 2. UNIVERSAL CAMERA — THE CAMERA MUST UNDERSTAND THE TASK

The camera should become a unified capture and preparation entry point.

Expected behaviour:

- Automatically correct EXIF orientation.
- Detect whether the input is a person/photo, signature/thumb impression, or document/ID.
- Detect image rotation or skew where technically possible.
- Detect face position and facial landmarks for portrait cropping.
- Detect document boundaries and four corners for perspective correction.
- Detect signature content boundaries while preserving genuine ink strokes.
- Choose the appropriate processing pipeline.
- Produce output according to the requested dimensions, format, DPI and KB range.

Architecture requirement:

Use a `CaptureIntent` or equivalent clear architecture.

When the camera is launched from Photo Studio, Signature Studio, PDF Master, or a specific Portal requirement, pass that context to the camera automatically.

When opened from a general-purpose entry point with no known task, show a minimal task selector instead of guessing.

Do not force a single fixed crop rectangle on every image.

For uncertain detection, provide a simple confirmation or manual adjustment rather than producing a wrong crop silently.

Investigate genuinely offline, on-device detection options. Evaluate model licensing, model size, Android compatibility, CPU/RAM usage, Moto G60 performance and maintenance requirements before proposing a solution.

Do not add paid APIs, cloud processing, remote image uploads or mandatory API keys.

Do not create fake AI implementations or placeholder model classes that merely return fixed rectangles.

## 3. PHOTO PREPARATION — REAL PORTRAIT PROCESSING

The Photo Studio must support Gallery and Camera inputs.

Expected workflow:

1. User captures or uploads an original photo.
2. App detects the face and relevant portrait area.
3. App corrects orientation and suitable rotation.
4. App automatically crops according to the selected target aspect ratio and portrait composition.
5. App performs genuine on-device background segmentation when white background is required.
6. App creates a natural, clean white background while preserving hair, ears, shoulders and clothing boundaries.
7. App resizes and compresses according to actual portal requirements.
8. App shows a final preview and actual output validation.

Background replacement must not use a simplistic perimeter-colour assumption as a substitute for segmentation.

Preserve the user's genuine facial identity. Do not beautify, reshape, regenerate or modify facial features.

Keep the original input untouched and create a separate processed output.

If background segmentation is unavailable or unreliable, show an honest warning instead of a false SUCCESS.

## 4. PORTAL-SPECIFIC PREPARATION — A SEPARATE WORKFLOW

This is different from the general Universal Camera workflow.

The exact expected user journey:

**Portals → Select Examination → Select Required Document → Upload Original → Auto Prepare → Preview → Save**

Example:

- User selects UPSC.
- User selects Passport Photograph.
- User uploads a photo from Gallery.
- App reads the UPSC photo rule.
- App automatically detects and crops the portrait.
- App applies the required background processing.
- App resizes to exact required dimensions.
- App compresses within the required file-size range.
- App verifies the actual saved output.

The same process must work independently for Signature, Thumb Impression and Document.

Each document type must use its own processing logic.

Do not apply passport-photo cropping to signatures or ID documents.

Requirements must come from the selected portal's actual `DocumentRule`. Do not silently substitute generic preset values.

Use a dedicated full-screen Portal Detail / Preparation flow where necessary. Avoid placing a large, multi-step workflow inside a cramped AlertDialog.

## 5. STRICT FILE COMPLIANCE

Preserve the Phase 0.1 reliability improvements.

Verify the actual saved output, not merely the requested settings.

Requirements include:

- Actual pixel dimensions.
- Actual file size in KB.
- Actual format.
- Valid DPI metadata where supported.
- Background compliance only when genuinely verified.
- PDF page count and page geometry.
- Genuine file readability.

Never fake file size by inserting meaningless padding bytes.

If a requested minimum and maximum KB range cannot both be achieved while preserving required quality and dimensions, report the conflict or failure honestly.

Never label an unverified result as compliant.

## 6. PORTAL DATA TRUST AND VERIFICATION

Inspect the existing verification model.

A green `Officially Verified` status must require reliable official-source evidence.

Store or verify:

- Official source URL.
- Source document or notification title.
- Notification/publication date where available.
- Last verification date.
- Rule version or revision information.
- Verification state.

Old, missing, superseded or unconfirmed requirements must not silently retain a trustworthy green badge.

Use a suitable `Needs Review` or `Outdated` state until revalidated.

Do not invent official government rules or dates.

## 7. PREMIUM, CLEAN AND MINIMAL UI/UX

The biggest UI requirement is simplicity.

The current Portals screen shows too much information at once. Redesign it using the existing Home screen's clean visual hierarchy as the reference.

### Portals list screen

Make it clean and compact.

Keep only:

- Screen title.
- Compact search.
- Compact category filters.
- Portal/examination name.
- Short department or authority line.
- Small, truthful verification indicator.
- One clear entry action.

Remove from the portal list cards:

- Repeated Photo/Signature/Document KB chips.
- Long source-document text.
- Repeated verification-date paragraphs.
- Long explanations.
- Unnecessary descriptive text.

Move detailed requirements and source information into Portal Detail.

Make sure the selected `All` filter chip displays its text correctly.

The search field should be compact, approximately standard single-line input height, and must not wrap its placeholder unnecessarily.

### Portal detail and preparation screens

Show the required documents in a clean list.

Show detailed dimensions, formats and KB requirements only where they help the user select or review that requirement.

Keep the primary action obvious.

### Photo and Signature screens

Use a clean layout:

- One clear upload/capture action.
- Large, useful image preview.
- Minimal adjustment controls.
- A compact final output status.
- Save action.

Put advanced settings inside an `Adjust` panel or bottom sheet instead of showing every control and explanation permanently.

Do not repeat long promotional sentences such as "100% On-Device AI" or "exact KB guarantee" across the interface.

The app should perform the work rather than explain every internal step to the user.

### Navigation and visual language

Use native Jetpack Compose and Material 3.

Create a consistent premium Android design system:

- Well-balanced spacing.
- Consistent typography.
- Restrained colour palette.
- Carefully chosen rounded shapes.
- Consistent icon sizes.
- Clear selected/unselected navigation states.
- Proper light and dark themes.
- Accessible touch targets.
- Smooth but restrained transitions.
- Subtle spring-based feedback.
- Haptic feedback only for meaningful actions.

Do not add blur everywhere or copy iOS effects blindly.

Preserve Android click semantics, accessibility and ripple feedback. Avoid replacing normal button clicks with unnecessary custom pointer-input gestures.

Prioritise smooth performance on a Motorola Moto G60.

## 8. PRIVACY AND PERFORMANCE

All sensitive photo, signature, thumb and document preparation must remain on-device.

Do not transmit user documents to Gemini or another cloud service.

Avoid unnecessary bitmap copies and memory spikes.

Use safe bitmap ownership and recycling.

Ensure processing runs off the main thread where appropriate.

Handle large images, cancellation, invalid files and low-memory conditions safely.

## 9. TESTING REQUIREMENTS

Create a detailed test plan covering:

- Straight and rotated portraits.
- Different face positions and sizes.
- Portraits with complex hair/background boundaries.
- Gallery images with EXIF rotation.
- Signatures with faint strokes and shadows.
- ID cards photographed at angles.
- Documents with perspective distortion.
- Different portal dimensions and file-size limits.
- Impossible KB constraints.
- Invalid or unsupported formats.
- Save and reopen output files.
- Regression tests for existing Phase 0.1 features.
- Compose UI tests for the redesigned Portal workflow.

Include a manual acceptance-test checklist for a real Motorola Moto G60.

Do not claim hardware testing unless it was actually performed.

## 10. REQUIRED PLAN MODE OUTPUT

Return a complete, practical implementation roadmap with:

1. Existing feature audit: working / partial / missing.
2. Exact reasons current app behaviour differs from the desired behaviour.
3. Proposed app workflow and architecture.
4. Proposed UI screen structure.
5. Exact files to modify in each phase.
6. New files or dependencies required.
7. On-device model options, licensing, size and performance trade-offs.
8. Data-model and portal-rule changes.
9. Unit, UI and device testing strategy.
10. Risks, dependencies and likely regressions.
11. Ordered implementation phases with clear completion criteria.

Separate the work into phases small enough to implement and test independently.

Do not combine a major computer-vision engine rewrite and a full UI redesign into one uncontrolled code-generation task.

Do not remove working features, change the database schema unnecessarily, or rewrite unrelated modules.

Do not claim a feature is complete until its actual implementation and tests support that claim.

**STOP after presenting the complete plan. Do not write or modify code until I review and approve the plan.**