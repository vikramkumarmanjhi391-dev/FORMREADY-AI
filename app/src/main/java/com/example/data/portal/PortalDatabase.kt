package com.example.data.portal

import com.example.data.model.ColorMode
import com.example.data.model.DocumentRule
import com.example.data.model.DocumentType
import com.example.data.model.FileFormat
import com.example.data.model.PortalSpec
import com.example.data.model.VerificationStatus

object PortalDatabase {

    val OFFICIAL_PORTALS: List<PortalSpec> = listOf(
        // 1. UPSC Civil Services
        PortalSpec(
            id = "upsc_cse",
            portalName = "UPSC Online Portal",
            examOrServiceName = "Civil Services (Preliminary / Mains) Examination",
            department = "Union Public Service Commission",
            stateOrCentral = "Central",
            year = "2025-2026",
            category = "Civil Services",
            officialUrl = "https://upsconline.nic.in",
            sourceDocumentTitle = "UPSC CSE Official Notification Instructions for Photo & Signature",
            applicableWorkflow = "Online OTR & CSP Application Upload",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Feb 2025",
            version = "v2.1",
            documents = listOf(
                DocumentRule(
                    id = "upsc_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 20,
                    maxKb = 300,
                    minWidthPx = 350,
                    maxWidthPx = 1000,
                    minHeightPx = 350,
                    maxHeightPx = 1000,
                    exactWidthPx = null,
                    exactHeightPx = null,
                    targetAspectRatio = 1.0f,
                    requiresWhiteBackground = true,
                    requiresNameAndDateStrip = true,
                    instructions = "Must be taken within 10 days of filling form. Candidate's name and date of photo capture must clearly appear at bottom."
                ),
                DocumentRule(
                    id = "upsc_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 20,
                    maxKb = 300,
                    minWidthPx = 350,
                    maxWidthPx = 1000,
                    minHeightPx = 350,
                    maxHeightPx = 1000,
                    targetAspectRatio = 1.0f,
                    requiresWhiteBackground = true,
                    instructions = "Signature in black ink on clean white paper. No block letters."
                ),
                DocumentRule(
                    id = "upsc_id_card",
                    documentType = DocumentType.AADHAAR_CARD,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 20,
                    maxKb = 300,
                    maxPages = 1,
                    instructions = "Photo ID card (Aadhaar/PAN/Voter/Passport) in single PDF page, clear and legible."
                )
            )
        ),

        // 2. SSC CGL / CHSL / MTS
        PortalSpec(
            id = "ssc_cgl",
            portalName = "Staff Selection Commission (SSC One-Time Registration & Apply)",
            examOrServiceName = "Combined Graduate Level (CGL) / CHSL",
            department = "Staff Selection Commission, GoI",
            stateOrCentral = "Central",
            year = "2025",
            category = "Recruitment",
            officialUrl = "https://ssc.gov.in",
            sourceDocumentTitle = "SSC General Instructions for Document Upload",
            applicableWorkflow = "SSC OTR & Online Exam Application (JPEG upload; live webcam capture also used on portal)",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Jan 2025",
            version = "v3.0",
            documents = listOf(
                DocumentRule(
                    id = "ssc_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPEG, FileFormat.JPG),
                    minKb = 20,
                    maxKb = 50,
                    exactWidthPx = 200,
                    exactHeightPx = 230,
                    minWidthPx = 100,
                    maxWidthPx = 300,
                    minHeightPx = 120,
                    maxHeightPx = 400,
                    physicalWidthMm = 35f,
                    physicalHeightMm = 45f,
                    targetAspectRatio = 3.5f / 4.5f,
                    requiresWhiteBackground = true,
                    instructions = "Light/plain white background. Both ears must be clearly visible. No caps or dark glasses."
                ),
                DocumentRule(
                    id = "ssc_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPEG, FileFormat.JPG),
                    minKb = 10,
                    maxKb = 20,
                    exactWidthPx = 140,
                    exactHeightPx = 60,
                    physicalWidthMm = 40f,
                    physicalHeightMm = 20f,
                    targetAspectRatio = 4.0f / 2.0f,
                    requiresWhiteBackground = true,
                    instructions = "Sign on white paper with black/blue pen. Horizontal orientation."
                ),
                DocumentRule(
                    id = "ssc_matric",
                    documentType = DocumentType.CLASS_10_CERTIFICATE,
                    isRequired = false,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 50,
                    maxKb = 300,
                    maxPages = 2,
                    instructions = "Matriculation / Secondary certificate for Date of Birth verification."
                )
            )
        ),

        // 3. BPSC (Bihar Public Service Commission)
        PortalSpec(
            id = "bpsc_cce",
            portalName = "BPSC Online Application Portal",
            examOrServiceName = "Combined Competitive Examination (CCE) / Teacher TRE",
            department = "Bihar Public Service Commission",
            stateOrCentral = "Bihar",
            year = "2025",
            category = "Civil Services",
            officialUrl = "https://onlinebpsc.bihar.gov.in",
            sourceDocumentTitle = "BPSC Online Application Guidelines and Dimensions",
            applicableWorkflow = "BPSC Online Application Portal",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Feb 2025",
            version = "v1.8",
            documents = listOf(
                DocumentRule(
                    id = "bpsc_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 10,
                    maxKb = 25,
                    exactWidthPx = 250,
                    exactHeightPx = 250,
                    targetAspectRatio = 1.0f,
                    requiresWhiteBackground = true,
                    instructions = "Passport photo with white/light background. Size strictly under 25 KB."
                ),
                DocumentRule(
                    id = "bpsc_sign_eng",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 5,
                    maxKb = 15,
                    exactWidthPx = 220,
                    exactHeightPx = 100,
                    requiresWhiteBackground = true,
                    instructions = "Signature in English strictly under 15 KB."
                ),
                DocumentRule(
                    id = "bpsc_sign_hindi",
                    documentType = DocumentType.SIGNATURE_HINDI,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 5,
                    maxKb = 15,
                    exactWidthPx = 220,
                    exactHeightPx = 100,
                    requiresWhiteBackground = true,
                    instructions = "Separate signature in Hindi strictly under 15 KB."
                ),
                DocumentRule(
                    id = "bpsc_cert_pdf",
                    documentType = DocumentType.CASTE_OBC_NCL_STATE,
                    isRequired = false,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 20,
                    maxKb = 100,
                    maxPages = 2,
                    instructions = "Reservation / Domicile / Education certificate PDF under 100 KB."
                )
            )
        ),

        // 4. Railway RRB (NTPC, ALP, Group D)
        PortalSpec(
            id = "rrb_recruitment",
            portalName = "Railway Recruitment Board (RRB Apply)",
            examOrServiceName = "RRB ALP / Technician / NTPC / Group D",
            department = "Ministry of Railways, Govt. of India",
            stateOrCentral = "Central",
            year = "2025",
            category = "Railways",
            officialUrl = "https://rrbapply.gov.in",
            sourceDocumentTitle = "CEN Railway Recruitment Information Brochure - Document Guidelines",
            applicableWorkflow = "Online RRB CEN Application Upload",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Jan 2025",
            version = "v2.0",
            documents = listOf(
                DocumentRule(
                    id = "rrb_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPEG, FileFormat.JPG),
                    minKb = 30,
                    maxKb = 70,
                    exactWidthPx = 320,
                    exactHeightPx = 400,
                    physicalWidthMm = 35f,
                    physicalHeightMm = 45f,
                    targetAspectRatio = 3.5f / 4.5f,
                    requiresWhiteBackground = true,
                    instructions = "Colour photo taken against light/white background, 35mm x 45mm, clear face."
                ),
                DocumentRule(
                    id = "rrb_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPEG, FileFormat.JPG),
                    minKb = 30,
                    maxKb = 70,
                    exactWidthPx = 140,
                    exactHeightPx = 60,
                    requiresWhiteBackground = true,
                    instructions = "Running handwriting signature. Black/blue ink on white paper."
                ),
                DocumentRule(
                    id = "rrb_sc_st_pass",
                    documentType = DocumentType.CASTE_SC_ST,
                    isRequired = false,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 50,
                    maxKb = 500,
                    instructions = "Community certificate for free rail travel pass (SC/ST only)."
                )
            )
        ),

        // 5. IBPS & SBI (Banking)
        PortalSpec(
            id = "ibps_bank",
            portalName = "IBPS Online Portal",
            examOrServiceName = "IBPS PO / Clerk / Specialist Officer / SBI",
            department = "Institute of Banking Personnel Selection",
            stateOrCentral = "Central",
            year = "2025",
            category = "Banking",
            officialUrl = "https://ibps.in",
            sourceDocumentTitle = "IBPS Guidelines for Scanning and Upload of Documents",
            applicableWorkflow = "IBPS CRP Online Application (Photo, Sign, LTI, Handwritten Declaration)",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Feb 2025",
            version = "v2.4",
            documents = listOf(
                DocumentRule(
                    id = "ibps_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 20,
                    maxKb = 50,
                    exactWidthPx = 200,
                    exactHeightPx = 230,
                    physicalWidthMm = 35f,
                    physicalHeightMm = 45f,
                    targetAspectRatio = 200f / 230f,
                    requiresWhiteBackground = true,
                    instructions = "Colour passport photo, 200 x 230 pixels, white background."
                ),
                DocumentRule(
                    id = "ibps_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 10,
                    maxKb = 20,
                    exactWidthPx = 140,
                    exactHeightPx = 60,
                    requiresWhiteBackground = true,
                    instructions = "Signature in black ink. Capital letters strictly not accepted."
                ),
                DocumentRule(
                    id = "ibps_thumb",
                    documentType = DocumentType.THUMB_IMPRESSION_LEFT,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 20,
                    maxKb = 50,
                    exactWidthPx = 240,
                    exactHeightPx = 240,
                    targetAspectRatio = 1.0f,
                    instructions = "Left thumb impression on white paper with black/blue ink."
                ),
                DocumentRule(
                    id = "ibps_declaration",
                    documentType = DocumentType.DECLARATION_HANDWRITTEN,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 50,
                    maxKb = 100,
                    exactWidthPx = 800,
                    exactHeightPx = 400,
                    targetAspectRatio = 2.0f,
                    instructions = "Handwritten declaration in English written by candidate himself/herself."
                )
            )
        ),

        // 6. NTA JEE (Main) / NEET (UG) / CUET
        PortalSpec(
            id = "nta_exams",
            portalName = "National Testing Agency (NTA)",
            examOrServiceName = "NEET (UG) / JEE Main / CUET",
            department = "Ministry of Education, GoI",
            stateOrCentral = "Central",
            year = "2025",
            category = "Entrance",
            officialUrl = "https://nta.ac.in",
            sourceDocumentTitle = "NTA Information Bulletin - Appendix for Upload Specifications",
            applicableWorkflow = "Combined NEET UG / JEE Main / CUET UG Online Form",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Feb 2025",
            version = "v3.1",
            documents = listOf(
                DocumentRule(
                    id = "nta_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 10,
                    maxKb = 200,
                    minWidthPx = 350,
                    maxWidthPx = 1000,
                    minHeightPx = 450,
                    maxHeightPx = 1200,
                    targetAspectRatio = 3.5f / 4.5f,
                    requiresWhiteBackground = true,
                    requiresNameAndDateStrip = true,
                    instructions = "80% face coverage, white background, candidate name and date of photo."
                ),
                DocumentRule(
                    id = "nta_postcard",
                    documentType = DocumentType.POSTCARD_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 10,
                    maxKb = 200,
                    exactWidthPx = 1200,
                    exactHeightPx = 1800,
                    targetAspectRatio = 4.0f / 6.0f,
                    instructions = "4 x 6 inch postcard size photograph for exam hall verification."
                ),
                DocumentRule(
                    id = "nta_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 4,
                    maxKb = 30,
                    exactWidthPx = 280,
                    exactHeightPx = 120,
                    instructions = "Clear signature in black ink on white paper."
                ),
                DocumentRule(
                    id = "nta_category_pdf",
                    documentType = DocumentType.CASTE_OBC_NCL_CENTRAL,
                    isRequired = false,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 50,
                    maxKb = 300,
                    instructions = "Category / PwD / Class 10 certificate in PDF (50 KB to 300 KB)."
                )
            )
        ),

        // 7. Bihar RTPS (Right to Public Services)
        PortalSpec(
            id = "bihar_rtps",
            portalName = "ServicePlus Bihar (RTPS & Other Services)",
            examOrServiceName = "Caste, Income, Domicile (Niwas), EWS & OBC-NCL Certificates",
            department = "General Administration Dept, Govt. of Bihar",
            stateOrCentral = "Bihar",
            year = "2025",
            category = "RTPS / Services",
            officialUrl = "https://serviceonline.bihar.gov.in",
            sourceDocumentTitle = "Bihar RTPS Document Attachment Guidelines",
            applicableWorkflow = "ServicePlus Bihar Citizen Certificate Application",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Feb 2025",
            version = "v1.5",
            documents = listOf(
                DocumentRule(
                    id = "rtps_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 20,
                    maxKb = 350,
                    exactWidthPx = 300,
                    exactHeightPx = 350,
                    requiresWhiteBackground = false,
                    instructions = "Applicant's recent colour passport photo under 350 KB."
                ),
                DocumentRule(
                    id = "rtps_id_proof",
                    documentType = DocumentType.AADHAAR_CARD,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.PDF, FileFormat.JPG),
                    minKb = 50,
                    maxKb = 2000,
                    instructions = "Aadhaar Card, Voter Card, or Ration Card copy under 2 MB."
                ),
                DocumentRule(
                    id = "rtps_land_receipt",
                    documentType = DocumentType.OTHER_SUPPORTING,
                    isRequired = false,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 50,
                    maxKb = 2000,
                    instructions = "Land receipt / Khatiyan / Parent certificate for Caste verification."
                )
            )
        ),

        // 8. Passport Seva Portal
        PortalSpec(
            id = "passport_seva",
            portalName = "Passport Seva Project",
            examOrServiceName = "Fresh / Re-issue Passport & Police Clearance (PCC)",
            department = "Ministry of External Affairs, GoI",
            stateOrCentral = "Central",
            year = "2025",
            category = "Identity",
            officialUrl = "https://passportindia.gov.in",
            sourceDocumentTitle = "Passport Seva Photo Specifications and Document Upload Rules",
            applicableWorkflow = "Passport Seva Online Portal Supporting Document Upload",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Jan 2025",
            version = "v2.2",
            documents = listOf(
                DocumentRule(
                    id = "passport_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 10,
                    maxKb = 300,
                    minWidthPx = 400,
                    maxWidthPx = 1000,
                    minHeightPx = 400,
                    maxHeightPx = 1000,
                    targetAspectRatio = 3.5f / 4.5f,
                    physicalWidthMm = 35f,
                    physicalHeightMm = 45f,
                    requiresWhiteBackground = true,
                    instructions = "Pure white background. 70-80% face zoom, neutral expression."
                ),
                DocumentRule(
                    id = "passport_annexure_pdf",
                    documentType = DocumentType.OTHER_SUPPORTING,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 50,
                    maxKb = 1000,
                    instructions = "Address / Date of birth proof in PDF format under 1 MB."
                )
            )
        ),

        // 9. Sarathi Parivahan (Driving Licence)
        PortalSpec(
            id = "sarathi_parivahan",
            portalName = "Sarathi Parivahan (MoRTH)",
            examOrServiceName = "Learner Licence (LL) & Driving Licence (DL)",
            department = "Ministry of Road Transport and Highways",
            stateOrCentral = "Central",
            year = "2025",
            category = "RTPS / Services",
            officialUrl = "https://sarathi.parivahan.gov.in",
            sourceDocumentTitle = "Parivahan Citizen Portal Upload Parameters (State-specific variations)",
            applicableWorkflow = "Sarathi LL/DL Online Upload (varies by State RTO)",
            verificationStatus = VerificationStatus.NEEDS_REVERIFICATION,
            lastVerifiedDate = "Mid 2024 (Re-verify with state RTO)",
            version = "v1.9",
            documents = listOf(
                DocumentRule(
                    id = "dl_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 10,
                    maxKb = 20,
                    exactWidthPx = 200,
                    exactHeightPx = 230,
                    targetAspectRatio = 200f / 230f,
                    instructions = "Applicant photo strictly between 10 KB and 20 KB."
                ),
                DocumentRule(
                    id = "dl_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 10,
                    maxKb = 20,
                    exactWidthPx = 200,
                    exactHeightPx = 100,
                    instructions = "Candidate signature strictly between 10 KB and 20 KB."
                ),
                DocumentRule(
                    id = "dl_doc_pdf",
                    documentType = DocumentType.AADHAAR_CARD,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.PDF, FileFormat.JPG),
                    minKb = 50,
                    maxKb = 500,
                    instructions = "Age & Address proof document under 500 KB."
                )
            )
        ),

        // 10. Central Teacher Eligibility Test (CTET)
        PortalSpec(
            id = "ctet_exam",
            portalName = "CTET Portal (CBSE)",
            examOrServiceName = "Central Teacher Eligibility Test (CTET)",
            department = "Central Board of Secondary Education",
            stateOrCentral = "Central",
            year = "2025",
            category = "Teacher Recruitment",
            officialUrl = "https://ctet.nic.in",
            sourceDocumentTitle = "CTET Information Bulletin Photo Dimensions",
            applicableWorkflow = "CBSE CTET Online Application Upload",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Jan 2025",
            version = "v1.7",
            documents = listOf(
                DocumentRule(
                    id = "ctet_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 10,
                    maxKb = 100,
                    physicalWidthMm = 35f,
                    physicalHeightMm = 45f,
                    exactWidthPx = 350,
                    exactHeightPx = 450,
                    targetAspectRatio = 3.5f / 4.5f,
                    instructions = "Dimensions 3.5 cm x 4.5 cm. Size 10 KB to 100 KB."
                ),
                DocumentRule(
                    id = "ctet_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 3,
                    maxKb = 30,
                    physicalWidthMm = 35f,
                    physicalHeightMm = 15f,
                    exactWidthPx = 350,
                    exactHeightPx = 150,
                    targetAspectRatio = 3.5f / 1.5f,
                    instructions = "Dimensions 3.5 cm x 1.5 cm. Size 3 KB to 30 KB."
                )
            )
        ),

        // 11. NSDL / Protean PAN Card (TIN-NSDL Agency 300 DPI vs DSC 200 DPI separated workflows)
        PortalSpec(
            id = "nsdl_pan",
            portalName = "NSDL / Protean eGov PAN Portal (300 DPI Agency Scan Preset)",
            examOrServiceName = "New PAN Card / Correction (Legacy 213x213 px @ 300 DPI Agency Scan)",
            department = "Income Tax Department / Protean eGov",
            stateOrCentral = "Central",
            year = "2025-2026",
            category = "Identity",
            officialUrl = "https://tin.tin.proteantech.in",
            sourceDocumentTitle = "Protean (NSDL) Agency Scanning Preset (Under Source Review — see nsdl_pan_dsc for 200 DPI DSC workflow)",
            applicableWorkflow = "Legacy Third-Party / Agency Scanning Workflow (213x213 px @ 300 DPI)",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Mar 2025",
            version = "v3.2",
            documents = listOf(
                DocumentRule(
                    id = "nsdl_pan_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 10,
                    maxKb = 50,
                    exactWidthPx = 213,
                    exactHeightPx = 213,
                    dpi = 300,
                    targetAspectRatio = 1.0f,
                    requiresWhiteBackground = true,
                    instructions = "213 x 213 px at 300 DPI (square agency scan preset), pure white background, under 50 KB."
                ),
                DocumentRule(
                    id = "nsdl_pan_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 5,
                    maxKb = 20,
                    exactWidthPx = 140,
                    exactHeightPx = 60,
                    dpi = 300,
                    targetAspectRatio = 140f / 60f,
                    colorMode = ColorMode.BLACK_AND_WHITE,
                    requiresWhiteBackground = true,
                    instructions = "140 x 60 px at 300 DPI, black ink on pure white background, under 20 KB."
                ),
                DocumentRule(
                    id = "nsdl_pan_doc",
                    documentType = DocumentType.AADHAAR_CARD,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.PDF, FileFormat.JPG),
                    minKb = 20,
                    maxKb = 300,
                    dpi = 200,
                    maxPages = 2,
                    instructions = "Proof of Identity, Address & DOB (Aadhaar front & back) in clear PDF/JPG under 300 KB."
                )
            )
        ),

        // 11B. Protean (TIN-NSDL) DSC-Based Online PAN Application (200 DPI, 3.5x2.5 cm <=20 KB)
        PortalSpec(
            id = "nsdl_pan_dsc",
            portalName = "Protean (TIN-NSDL) DSC Online PAN",
            examOrServiceName = "DSC-Based Online PAN Application (Form 49A / CSF — 200 DPI)",
            department = "Income Tax Department / Protean eGov Technologies",
            stateOrCentral = "Central",
            year = "2025-2026",
            category = "Identity",
            officialUrl = "https://tin.tin.proteantech.in",
            sourceDocumentTitle = "Protean (NSDL) DSC-Based Online PAN Application Guidelines",
            sourceIdentifier = "Protean TIN DSC PAN Guidelines",
            applicableWorkflow = "DSC-Based Online PAN Application (Form 49A / CSF): Photo 200 DPI 3.5x2.5 cm <=20 KB; Sign 200 DPI 2x4.5 cm <=10 KB",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Mar 2025",
            version = "v1.0",
            documents = listOf(
                DocumentRule(
                    id = "nsdl_dsc_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 2,
                    maxKb = 20,
                    exactWidthPx = 197,
                    exactHeightPx = 276,
                    physicalWidthMm = 25f,
                    physicalHeightMm = 35f,
                    dpi = 200,
                    targetAspectRatio = 25f / 35f,
                    requiresWhiteBackground = true,
                    sourceClauseRef = "Protean DSC PAN Photo & Signature Table (200 DPI, 3.5x2.5 cm <=20 KB)",
                    instructions = "3.5 cm (height) x 2.5 cm (width) at 200 DPI (197 x 276 px), colour JPEG <= 20 KB."
                ),
                DocumentRule(
                    id = "nsdl_dsc_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 1,
                    maxKb = 10,
                    exactWidthPx = 354,
                    exactHeightPx = 157,
                    physicalWidthMm = 45f,
                    physicalHeightMm = 20f,
                    dpi = 200,
                    targetAspectRatio = 45f / 20f,
                    requiresWhiteBackground = true,
                    sourceClauseRef = "Protean DSC PAN Photo & Signature Table (200 DPI, 2x4.5 cm <=10 KB)",
                    instructions = "2.0 cm x 4.5 cm at 200 DPI (354 x 157 px), JPEG <= 10 KB."
                ),
                DocumentRule(
                    id = "nsdl_dsc_doc",
                    documentType = DocumentType.AADHAAR_CARD,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 10,
                    maxKb = 300,
                    dpi = 200,
                    maxPages = 2,
                    sourceClauseRef = "Protean DSC PAN Supporting Document Specification (PDF <= 300 KB)",
                    instructions = "Supporting document in PDF format at 200 DPI under 300 KB."
                )
            )
        ),

        // 12. UTI (UTIITSL) PAN Card Portal
        PortalSpec(
            id = "uti_pan",
            portalName = "UTIITSL PAN Online Portal",
            examOrServiceName = "UTI New PAN Application / CSF Correction",
            department = "UTI Infrastructure Technology And Services Limited",
            stateOrCentral = "Central",
            year = "2025-2026",
            category = "Identity",
            officialUrl = "https://www.pan.utiitsl.com",
            sourceDocumentTitle = "UTIITSL PAN Scanning & Upload Specifications (300/600 DPI Digital Scan vs Physical Form 49A 4.5x3.5 cm)",
            applicableWorkflow = "UTIITSL Digital Portal Upload (213x213 px @ 300 DPI photo, 600 DPI B&W signature; physical Form 49A uses 4.5x3.5 cm photos)",
            verificationStatus = VerificationStatus.OFFICIAL_SOURCE_REVIEW,
            lastVerifiedDate = "Mar 2025",
            version = "v3.0",
            documents = listOf(
                DocumentRule(
                    id = "uti_pan_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 10,
                    maxKb = 30,
                    exactWidthPx = 213,
                    exactHeightPx = 213,
                    dpi = 300,
                    targetAspectRatio = 1.0f,
                    requiresWhiteBackground = true,
                    instructions = "Exact 213 x 213 pixels at 300 DPI, colour photo with plain white background, under 30 KB."
                ),
                DocumentRule(
                    id = "uti_pan_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG, FileFormat.JPEG),
                    minKb = 5,
                    maxKb = 20,
                    exactWidthPx = 140,
                    exactHeightPx = 60,
                    dpi = 600,
                    targetAspectRatio = 140f / 60f,
                    colorMode = ColorMode.BLACK_AND_WHITE,
                    requiresWhiteBackground = true,
                    instructions = "Black & White signature at 600 DPI on pure white paper, strictly under 20 KB."
                ),
                DocumentRule(
                    id = "uti_pan_doc",
                    documentType = DocumentType.AADHAAR_CARD,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.PDF),
                    minKb = 20,
                    maxKb = 300,
                    dpi = 200,
                    maxPages = 2,
                    instructions = "Supporting Proof of Identity/Address/DOB in PDF format under 300 KB."
                )
            )
        ),

        // 13. Universal Standard Passport & Form Preset
        PortalSpec(
            id = "universal_passport",
            portalName = "Universal Passport & Standard Form",
            examOrServiceName = "Universal Indian Passport Photo (3.5x4.5cm), Sign & <200KB Doc",
            department = "All Standard Portals & Cyber Cafe",
            stateOrCentral = "Central",
            year = "2025-2026",
            category = "Universal",
            officialUrl = "https://india.gov.in",
            sourceDocumentTitle = "Universal Government Form Specifications (Convenience Preset)",
            applicableWorkflow = "General-purpose 350x450 px (3.5x4.5 cm) photo & 140x60 px signature template",
            verificationStatus = VerificationStatus.USER_SUPPLIED,
            lastVerifiedDate = "Mar 2025",
            version = "v1.0",
            documents = listOf(
                DocumentRule(
                    id = "universal_photo",
                    documentType = DocumentType.PASSPORT_PHOTO,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 20,
                    maxKb = 50,
                    exactWidthPx = 350,
                    exactHeightPx = 450,
                    physicalWidthMm = 35f,
                    physicalHeightMm = 45f,
                    dpi = 300,
                    targetAspectRatio = 3.5f / 4.5f,
                    requiresWhiteBackground = true,
                    instructions = "Standard 3.5cm x 4.5cm (350x450 px, 300 DPI, 20-50 KB) with pure white background."
                ),
                DocumentRule(
                    id = "universal_sign",
                    documentType = DocumentType.SIGNATURE_ENGLISH,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.JPG),
                    minKb = 10,
                    maxKb = 20,
                    exactWidthPx = 140,
                    exactHeightPx = 60,
                    dpi = 300,
                    targetAspectRatio = 140f / 60f,
                    requiresWhiteBackground = true,
                    instructions = "Standard 140x60 px crisp signature on pure white paper, 10-20 KB."
                ),
                DocumentRule(
                    id = "universal_doc",
                    documentType = DocumentType.AADHAAR_CARD,
                    isRequired = true,
                    allowedFormats = listOf(FileFormat.PDF, FileFormat.JPG),
                    minKb = 20,
                    maxKb = 200,
                    dpi = 200,
                    maxPages = 2,
                    instructions = "Clear scanned document or ID card under 200 KB in JPG or PDF."
                )
            )
        )
    )

    private val userCustomPortals = mutableListOf<PortalSpec>()

    /**
     * Ordered list of top quick-select portal IDs shown in the unified studio selector bar.
     */
    val TOP_STUDIO_PORTAL_IDS = listOf(
        "universal_passport",
        "nsdl_pan",
        "uti_pan",
        "ssc_cgl",
        "rrb_recruitment",
        "bpsc_cce",
        "ctet_exam",
        "ibps_bank",
        "upsc_cse"
    )

    fun getTopStudioPortals(): List<PortalSpec> {
        val topOrdered = TOP_STUDIO_PORTAL_IDS.mapNotNull { id -> findPortalById(id) }
        val customSynced = userCustomPortals.filter { custom -> topOrdered.none { it.id == custom.id } }
        return topOrdered + customSynced
    }

    fun getAllPortals(): List<PortalSpec> {
        return OFFICIAL_PORTALS + userCustomPortals
    }

    fun addCustomPortal(portal: PortalSpec) {
        userCustomPortals.removeAll { it.id == portal.id }
        userCustomPortals.add(portal)
    }

    fun findPortalById(id: String): PortalSpec? {
        return getAllPortals().find { it.id == id }
    }

    /**
     * Parses a JSON array of portal presets (from GitHub Raw JSON or local cache) and registers them.
     * Supports ₹0 server-cost dynamic form updates.
     */
    fun parseAndRegisterPortalsFromJson(jsonString: String): List<PortalSpec> {
        val parsedList = mutableListOf<PortalSpec>()
        try {
            val array = org.json.JSONArray(jsonString.trim())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "remote_portal_$i")
                val portalName = obj.optString("portalName", "Remote Portal")
                val examName = obj.optString("examOrServiceName", portalName)
                val department = obj.optString("department", "Government Portal")
                val stateOrCentral = obj.optString("stateOrCentral", "Central")
                val year = obj.optString("year", "2025-2026")
                val category = obj.optString("category", "Recruitment")
                val officialUrl = obj.optString("officialUrl", "https://india.gov.in")

                val photoWidth = obj.optInt("photoWidthPx", 350)
                val photoHeight = obj.optInt("photoHeightPx", 450)
                val photoMinKb = obj.optInt("photoMinKb", 20)
                val photoMaxKb = obj.optInt("photoMaxKb", 50)
                val photoDpi = obj.optInt("photoDpi", 300)
                val requiresStrip = obj.optBoolean("requiresNameAndDateStrip", false)

                val signWidth = obj.optInt("signWidthPx", 140)
                val signHeight = obj.optInt("signHeightPx", 60)
                val signMinKb = obj.optInt("signMinKb", 10)
                val signMaxKb = obj.optInt("signMaxKb", 20)
                val signDpi = obj.optInt("signDpi", 300)

                val docMaxKb = obj.optInt("docMaxKb", 200)

                val rules = listOf(
                    DocumentRule(
                        id = "${id}_photo",
                        documentType = DocumentType.PASSPORT_PHOTO,
                        allowedFormats = listOf(FileFormat.JPG),
                        minKb = photoMinKb,
                        maxKb = photoMaxKb,
                        exactWidthPx = photoWidth,
                        exactHeightPx = photoHeight,
                        dpi = photoDpi,
                        targetAspectRatio = if (photoHeight > 0) photoWidth.toFloat() / photoHeight.toFloat() else 3.5f / 4.5f,
                        requiresWhiteBackground = true,
                        requiresNameAndDateStrip = requiresStrip,
                        instructions = "Photo: ${photoWidth}x${photoHeight} px, $photoDpi DPI, $photoMinKb-$photoMaxKb KB"
                    ),
                    DocumentRule(
                        id = "${id}_sign",
                        documentType = DocumentType.SIGNATURE_ENGLISH,
                        allowedFormats = listOf(FileFormat.JPG),
                        minKb = signMinKb,
                        maxKb = signMaxKb,
                        exactWidthPx = signWidth,
                        exactHeightPx = signHeight,
                        dpi = signDpi,
                        targetAspectRatio = if (signHeight > 0) signWidth.toFloat() / signHeight.toFloat() else 140f / 60f,
                        requiresWhiteBackground = true,
                        instructions = "Signature: ${signWidth}x${signHeight} px, $signDpi DPI, $signMinKb-$signMaxKb KB"
                    ),
                    DocumentRule(
                        id = "${id}_doc",
                        documentType = DocumentType.AADHAAR_CARD,
                        allowedFormats = listOf(FileFormat.PDF, FileFormat.JPG),
                        minKb = 20,
                        maxKb = docMaxKb,
                        dpi = 200,
                        instructions = "Document PDF/JPG under $docMaxKb KB"
                    )
                )

                val isAllowlisted = OfficialDomainAllowlist.isVerifiedOfficialUrl(officialUrl)
                val spec = PortalSpec(
                    id = id,
                    portalName = portalName,
                    examOrServiceName = examName,
                    department = department,
                    stateOrCentral = stateOrCentral,
                    year = year,
                    category = category,
                    officialUrl = officialUrl,
                    sourceDocumentTitle = "GitHub JSON Synced Specification",
                    applicableWorkflow = "Remote JSON Preset Sync",
                    verificationStatus = if (isAllowlisted) {
                        VerificationStatus.OFFICIAL_SOURCE_REVIEW
                    } else {
                        VerificationStatus.USER_SUPPLIED
                    },
                    lastVerifiedDate = "Synced",
                    version = "v1.0-sync",
                    documents = rules
                )
                parsedList.add(spec)
                addCustomPortal(spec)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return parsedList
    }

    /**
     * Built-in fallback sample JSON pack representing newly announced state & police exams
     * so users can test GitHub JSON preset sync even when offline or without a custom URL.
     */
    const val DEFAULT_GITHUB_PRESETS_JSON = """
    [
      {
        "id": "up_police_recruitment",
        "portalName": "UP Police Recruitment (UPPRPB)",
        "examOrServiceName": "UP Police Constable & SI Recruitment",
        "department": "UPPRPB Lucknow",
        "stateOrCentral": "Uttar Pradesh",
        "photoWidthPx": 350,
        "photoHeightPx": 450,
        "photoMinKb": 20,
        "photoMaxKb": 50,
        "photoDpi": 300,
        "requiresNameAndDateStrip": false,
        "signWidthPx": 140,
        "signHeightPx": 60,
        "signMinKb": 5,
        "signMaxKb": 20,
        "signDpi": 300,
        "docMaxKb": 200
      },
      {
        "id": "bihar_police_csbc",
        "portalName": "Bihar Police CSBC / BPSSC",
        "examOrServiceName": "Bihar Police Constable & Daroga Form",
        "department": "CSBC Bihar",
        "stateOrCentral": "Bihar",
        "photoWidthPx": 350,
        "photoHeightPx": 450,
        "photoMinKb": 15,
        "photoMaxKb": 25,
        "photoDpi": 300,
        "requiresNameAndDateStrip": true,
        "signWidthPx": 220,
        "signHeightPx": 100,
        "signMinKb": 10,
        "signMaxKb": 25,
        "signDpi": 300,
        "docMaxKb": 200
      }
    ]
    """
}

