# FORMREADY AI — MASTER DEVELOPMENT ROADMAP
# PLAN MODE ONLY — COMPLETE PRODUCT ARCHITECTURE, GAP ANALYSIS & PHASE-WISE IMPLEMENTATION STRATEGY

Act as a Principal Android Architect, Senior Kotlin Engineer, On-Device AI/Computer Vision Specialist, Document Processing Expert, Android Security Engineer and AdMob Monetization Strategist.

We are continuing development of the EXISTING FORMREADY AI Android application.

Do not create a new application. Do not rebuild the project from scratch.

Your current task is to prepare a comprehensive, technically accurate and implementation-ready development plan for the existing project.

IMPORTANT: THIS IS A PLANNING-ONLY TASK.

Do not modify source code, Gradle files, AndroidManifest.xml, project configuration, dependencies, assets or GitHub files. Do not implement any feature yet.

---

# 1. PRODUCT VISION

FORMREADY AI should become a high-quality, privacy-first, offline-first Android application for preparing photographs, signatures, thumb impressions, identity documents, educational certificates and other documents required for Indian government applications and online portals.

Target users:
- SSC, UPSC, BPSC, Railway, CTET and Banking aspirants.
- State government examination applicants.
- PAN application users.
- Citizens applying for government certificates and online services.
- Students and cyber-cafe operators.

The core promise:

"Capture or select a document, choose the correct portal requirement, prepare it in one click, verify the result and save it."

The application should provide excellent document-processing quality, a simple user experience and genuine privacy.

---

# 2. EXISTING PROJECT MUST BE AUDITED FIRST

Inspect the complete current project before preparing the roadmap.

Use the actual current workspace and current GitHub-synced code as the source of truth.

The latest previously inspected commit was:

8e055e353ec40b113bda993eef1ca03adcf54cfb

However, do not assume this is still the current workspace version. Check the actual current source and recent changes first.

Previously existing modules include:

- HomeDashboardScreen
- PhotoStudioScreen
- SignatureStudioScreen
- PdfMasterScreen
- DocumentLockerScreen
- PortalStudioScreen
- NotesVaultScreen
- UniversalCameraScreen
- UniversalCameraController
- UnifiedMediaInput
- OneClickPreparationCoordinator
- ImageProcessor
- PdfProcessor
- QualityInspector
- PortalDatabase
- PortalPresetSelectorBar
- DocumentLockerRepository
- DocumentLockerViewModel
- Room database and encrypted document storage

Inspect actual implementations, not just filenames or comments.

Identify which features are genuinely implemented, partially implemented, placeholder implementations, unverified, or missing.

For every important feature, distinguish between:
1. UI exists.
2. Processing logic exists.
3. Processing has automated tests.
4. Real Android device testing has been completed.
5. Real-world output quality has been verified.

Never treat a UI label or a test name as proof of working functionality.

---

# 3. NON-NEGOTIABLE BUSINESS AND TECHNICAL PRINCIPLES

The application must follow these principles.

## A. No Paid AI API Dependency

Do not make Gemini API, OpenAI API or any other paid AI API necessary for the main features.

No API key should be required for normal application use.

Avoid compulsory cloud AI services.

## B. Genuine On-Device Processing

Use suitable local Android libraries and models for:
- Portrait segmentation.
- Photo background replacement.
- Document detection.
- Document perspective correction.
- Signature enhancement.
- OCR.
- Image preparation.
- PDF generation and processing.

Models must be bundled with the application or installed through a deliberately designed local application package. Do not require remote model downloads for the core offline experience.

Evaluate suitable options such as:
- MediaPipe.
- TensorFlow Lite / LiteRT.
- OpenCV.
- Android native image-processing APIs.
- Other suitable open-source libraries and models.

Check compatibility, licence, attribution, model quality and actual Android integration requirements before recommending a dependency.

Do not choose a model merely because it is popular.

## C. Quality Is the Priority

Do not sacrifice output quality merely to achieve a tiny APK or the fastest possible processing time.

Prioritize:
- Accurate portrait segmentation.
- Natural hair and shoulder edges.
- Preservation of genuine facial details.
- Preservation of genuine signature strokes.
- Readable document text.
- Accurate document perspective.
- Correct dimensions and compression.
- Reliable output validation.

However, evaluate memory usage, device compatibility and processing stability. Avoid solutions that crash or become unusable on supported Android devices.

## D. Zero Recurring Server-Cost Goal

The product architecture should avoid:
- Paid backend servers.
- Paid databases.
- Paid image-processing services.
- Paid OCR APIs.
- Cloud AI inference.
- Mandatory subscriptions.

Optional internet-dependent features may include:
- Government preset updates.
- Optional AdMob advertisements.

The core document-processing features must remain usable without internet.

## E. Privacy First

User photographs, signatures, thumb impressions, Aadhaar/PAN documents, certificates and private notes must remain on the device during processing.

Do not upload private documents or images for AI analysis.

Use Android Keystore and secure local storage for sensitive documents.

Do not introduce account registration unless a feature genuinely requires it.

## F. Free Main Features

The primary document-processing capabilities should remain free.

The planned business model is optional, user-friendly advertisement revenue.

Do not design mandatory payment gates or subscription requirements for ordinary processing features.

---

# 4. COMPLETE FEATURE BLUEPRINT

Plan the following complete product capabilities.

## MODULE 1 — UNIVERSAL SMART CAMERA

One reusable CameraX system with different modes:

1. Passport Photo Mode.
2. Signature Mode.
3. Thumb Impression Mode.
4. Identity Document Mode.
5. Certificate / A4 Document Mode.
6. Multi-Page PDF Mode.
7. Document Locker Capture Mode.

Required capabilities:
- Live camera preview.
- Accurate mode-specific guide overlays.
- Front and rear cameras where appropriate.
- Flash support where hardware allows.
- Tap-to-focus.
- Capture review.
- Retake.
- Use Photo.
- Correct image orientation.
- Correct preview-to-captured-image coordinate mapping.
- Safe temporary-file lifecycle.

Camera images and Gallery images must enter the same downstream processing architecture.

Do not use static guessed guide-frame coordinates as a substitute for accurate CameraX transformation mapping.

## MODULE 2 — SMART PHOTO STUDIO

Plan a high-quality local portrait-processing pipeline:

1. Camera or Gallery input.
2. Accurate crop.
3. Local subject segmentation.
4. Foreground mask refinement.
5. Natural hair and shoulder edge preservation.
6. Background replacement according to the selected verified portal rule.
7. White background where required.
8. Optional manual background selection.
9. Exact width and height.
10. Correct DPI metadata.
11. Controlled image compression.
12. True output validation.
13. Before/After comparison.
14. Save to Gallery, Downloads or Document Locker.

Include Name and Date of Photo functionality where required by a verified notification.

Do not modify facial identity or fabricate facial features.

Do not claim perfect segmentation for images where the model cannot produce a reliable mask.

## MODULE 3 — SMART SIGNATURE AND THUMB STUDIO

Plan:
- Camera guide-box capture.
- Gallery selection.
- Accurate cropping.
- Blank-margin detection.
- Adaptive paper-lighting normalization.
- Shadow reduction.
- Genuine ink preservation.
- Black ink and original blue ink modes.
- Left and right thumb impression presets.
- Exact dimensions.
- DPI metadata.
- Min/Max KB validation.
- Before/After preview.
- Original-image preservation.

Thin signature strokes must not be silently erased by aggressive thresholding.

## MODULE 4 — SMART DOCUMENT SCANNER

Plan:
- Document boundary detection.
- Four-corner detection.
- Perspective correction.
- Rotation and orientation correction.
- Shadow reduction.
- Illumination normalization.
- Text sharpening.
- Colour, grayscale and black-and-white scan modes.
- Multi-page scanning.
- Page ordering.
- Page rotation.
- JPG and PDF output.
- Actual output-size validation.

Use a suitable local document-detection approach.

Do not claim that a basic contrast filter is equivalent to genuine document-corner detection.

## MODULE 5 — PDF MASTER

Preserve and expand:

- Images to PDF.
- Multi-page PDF creation.
- PDF compression.
- PDF merging.
- Front/Back ID card PDF.
- PDF page rotation.
- Page rendering.
- PDF validation.
- Actual page-count verification.
- Export and sharing.

PDF compression must verify the actual final file size. Never report a target size as achieved unless the final file on disk meets it.

## MODULE 6 — STUDENT AND CYBER-CAFE POWER TOOLS

Plan the following:

### A. Photo + Signature Joint Combiner
Combine a prepared photograph and signature only in layouts permitted by the relevant portal instructions.

### B. Printable Passport Photo Sheet
- 4×6 inch print sheet.
- A4 print sheet.
- 300 DPI output where specified.
- 8-photo and 12-photo layouts only when they physically fit.
- Correct aspect ratio.
- Accurate margins.
- Cutting guides.
- Before/After and print preview.

Do not hardcode a photo count if the actual dimensions and margins do not allow that count to fit.

### C. Local Document Utilities
Consider additional genuinely useful cyber-cafe utilities while keeping the existing main feature scope manageable.

## MODULE 7 — PORTAL REQUIREMENT ENGINE

Plan a shared portal database used across Photo, Signature and PDF Studio.

Initial portal categories:
- Universal Passport.
- NSDL / Protean PAN.
- UTI PAN.
- SSC.
- Railway.
- BPSC.
- CTET.
- UPSC.
- IBPS / SBI.
- State RTPS.
- Other verified government application portals.

Each rule should contain:
- Portal identifier.
- Document type.
- Accepted formats.
- Exact or permitted dimensions.
- Minimum and maximum KB.
- DPI where specified.
- Background requirements.
- Page limits.
- Official source URL.
- Source document title.
- Verification status.
- Last verified date.
- Rule version.
- Notes and warnings.

### Critical Accuracy Requirement

Never mark user-generated, sample, guessed or remotely supplied JSON rules as officially verified automatically.

Use distinct statuses:
- Officially Verified.
- Official Source Found — Review Required.
- User Supplied.
- Unverified.
- Needs Re-verification.
- Outdated.

Plan a safe mechanism for adding and updating local presets.

## MODULE 8 — OPTIONAL GITHUB JSON PRESET SYNC

The app may fetch new portal presets from a controlled GitHub Raw JSON source.

Requirements:
- Local built-in presets always available.
- Previously synchronized presets cached locally.
- Strict JSON schema validation.
- HTTPS only.
- Safe timeouts and error handling.
- Versioning.
- Duplicate detection.
- Source traceability.
- No arbitrary executable code from remote data.
- No automatic official verification.
- Invalid update must not corrupt existing presets.

Audit the current placeholder/default GitHub sync URL and existing sample JSON implementation. Plan a correct owner-controlled preset repository and schema strategy.

## MODULE 9 — DOCUMENT LOCKER AND PRIVACY

Preserve the existing:
- Encrypted document storage.
- Room database.
- Android Keystore integration.
- Folder management.
- Important Documents.
- Global Search.
- Encrypted Notes.
- Camera import.
- PDF import.
- Duplicate detection.

Plan a security review covering:
- Encryption.
- Key handling.
- Backup exclusion.
- Temporary files.
- Export permissions.
- Document deletion.
- App uninstall and data recovery expectations.

Never imply that cloud backup exists for encrypted documents unless the implementation actually supports a safe and tested backup flow.

## MODULE 10 — ADVERTISEMENT MONETIZATION

Plan a later AdMob implementation.

The intended business model:

- Main app features remain free.
- No paid API or processing charges.
- Reasonable App Open Ads at appropriate loading opportunities.
- No advertisement during active photo processing.
- No advertisement during PDF generation.
- No interruption during document saving or exporting.
- No deceptive clicks.
- No accidental-click layouts.
- No ads when the app is offline or an ad is unavailable.
- Ad failure must never block core functionality.

Plan frequency controls and compliance with current Google Play and AdMob policies.

Do not implement advertising in the current planning task.

---

# 5. EXISTING IMPLEMENTATION ISSUES TO VERIFY

Do not assume these issues are still present without inspecting the current source. Verify each and classify it as fixed, partially fixed, or outstanding.

Previously identified concerns:

1. ImageProcessor.applyAutoWhiteBackgroundAndFaceEnhance() uses estimated perimeter colours and fixed geometric subject assumptions instead of a genuine segmentation model.

2. Camera guide frame coordinates are not necessarily mapped accurately from PreviewView space to the final captured JPEG, especially with FILL_CENTER, rotation and lens differences.

3. ProcessedFileResult.hasWhiteBackground can represent a requested operation rather than measured output quality.

4. PortalDatabase.parseAndRegisterPortalsFromJson() previously assigned OFFICIALLY_VERIFIED to remotely supplied JSON presets.

5. NSDL sample photo dimensions and physical-size metadata may be inconsistent.

6. JPEG metadata padding was used to satisfy minimum KB values. Assess whether this is acceptable under relevant file standards and portal validation behaviour. Never claim that adding metadata bytes proves genuine portal compliance.

7. Signature processing uses threshold-based cleanup. Assess the need for improved local adaptive processing.

8. Document clear-scan processing must be distinguished from real corner detection and perspective correction.

9. Printable photo-sheet layout must be checked against real physical dimensions.

10. PdfProcessor compression must be checked against actual output size and page count.

11. Document and Camera temporary-file ownership must be safe.

12. GitHub JSON sync must use a real controlled endpoint and schema.

Find any further problems through source inspection.

---

# 6. REQUIRED DEVELOPMENT ROADMAP

Create a phase-wise implementation roadmap.

Use the following suggested groups as a starting framework, but change their order where the actual code dependencies require it.

### Phase 0 — Existing Project Audit and Architecture Stabilization
- Verify current code and build health.
- Identify broken or duplicate implementations.
- Establish a reliable shared processing architecture.
- Preserve existing working features.

### Phase 1 — Camera and Unified Media Reliability
- Camera lifecycle.
- Preview-to-image mapping.
- Camera/Gallery equivalence.
- Temporary-file ownership.
- Multi-page capture.

### Phase 2 — True Offline AI Photo Engine
- Model evaluation.
- Local model bundling.
- Segmentation.
- Mask refinement.
- Background compositing.
- Accuracy validation.

### Phase 3 — Signature and Thumb Processing Intelligence
- Adaptive paper normalization.
- Thin-stroke preservation.
- Reliable thresholding.
- Output validation.

### Phase 4 — Smart Document Scanner
- Document detection.
- Four-corner mapping.
- Perspective correction.
- Scan enhancement.

### Phase 5 — Portal Preset Accuracy and Unified Processing
- Shared portal configuration.
- Source verification.
- Local rules.
- GitHub JSON update strategy.
- Honest validation.

### Phase 6 — PDF and Cyber-Cafe Utilities
- Multi-page PDF.
- Photo + Signature Combiner.
- Correct 4×6 and A4 print sheets.
- Other planned utility features.

### Phase 7 — Document Locker and Application Security Review
- Encryption.
- Temporary files.
- Storage.
- Permissions.
- Privacy verification.

### Phase 8 — Offline-First and Network Dependency Audit
- Remove unnecessary cloud/API dependencies only after checking actual usage.
- Verify model bundling.
- Verify offline core feature operation.
- Isolate optional online preset sync and advertisements.

### Phase 9 — AdMob Monetization
- AdMob integration.
- App Open Ad strategy.
- Frequency rules.
- Offline fallback.
- User experience and policy compliance.

### Phase 10 — Final QA and Play Store Release Preparation
- Device tests.
- Regression tests.
- Security tests.
- Performance tests.
- Privacy policy and Data Safety preparation.
- Store assets.
- Release build.

You may split or combine phases based on dependency analysis.

For EACH phase, provide:

1. Objective.
2. Existing source files to inspect.
3. Exact files likely to be created or modified.
4. Technical implementation strategy.
5. Dependencies and licensing.
6. Offline/network requirements.
7. Security and privacy risks.
8. Automated tests.
9. Real-device tests.
10. Acceptance criteria.
11. Dependencies on earlier phases.
12. Features explicitly out of scope.
13. Rollback/regression considerations.

Keep each phase small enough to implement, review and test independently using Google AI Studio.

---

# 7. QUALITY, DEVICE AND MODEL SELECTION STRATEGY

Do not choose models using model names alone.

For every proposed local AI model, provide:

- Model name and version.
- Licence and attribution.
- Download/source provenance.
- Input/output tensor specifications.
- Estimated model asset size.
- Runtime requirements.
- Android integration method.
- Segmentation or OCR quality considerations.
- Memory requirements.
- CPU/GPU/NNAPI compatibility where relevant.
- Offline behaviour.
- Test images and quality evaluation strategy.

Quality is the leading priority, but device compatibility and crash prevention remain mandatory.

Include a physical-device testing strategy using the existing Moto G60 and a range of Android devices where available.

Google One cloud storage is useful for project backups and files, but it does not increase the Android phone's RAM or provide local inference resources. Do not treat cloud storage as a substitute for local app assets or device memory.

---

# 8. COST AND DEPENDENCY REPORT

Prepare a clear table showing:

- One-time development requirements.
- Recurring server costs.
- Paid API requirements.
- Model licensing costs.
- Open-source dependency licences.
- Google Play publication requirements.
- AdMob requirements.
- Optional internet-dependent features.

The design target is ₹0 recurring server/API processing cost.

Do not promise that all publishing, account or operational costs are zero.

Identify any possible future cost before recommending a dependency.

---

# 9. REQUIRED FINAL OUTPUT

Return a detailed Master Development Plan containing:

A. Executive summary.

B. Current project architecture.

C. Existing feature implementation audit.

D. Feature gap analysis.

E. Technical architecture recommendation.

F. On-device AI model comparison.

G. Offline and network dependency map.

H. Privacy and security strategy.

I. Full phase-wise development roadmap.

J. File-level implementation plan for each phase.

K. Automated and real-device test strategy.

L. Portal requirement accuracy strategy.

M. AdMob monetization strategy.

N. Cost and licensing assessment.

O. Major risks and mitigation plans.

P. Clear definition of what "complete FORMREADY AI" will mean.

Q. Recommended first implementation phase and why it should be first.

---

# 10. STRICT PLANNING MODE RULES

This request is for planning only.

Do not edit or create source files.

Do not change Gradle dependencies.

Do not generate implementation code.

Do not modify GitHub.

Do not start Phase 0 or any implementation automatically.

Do not assume a previous plan is technically correct without validating it against the current source code.

Do not claim an unimplemented feature exists.

Do not claim that a file is portal-compliant unless its measurable requirements and source rules have been validated.

Do not promise processing in under two seconds without measurement.

Do not treat UI labels, comments or synthetic tests as proof of real-device performance.

At the end, show the complete roadmap and wait for explicit approval before entering implementation mode.

The final result should be a realistic, dependency-aware, high-quality development blueprint for the existing FORMREADY AI project.