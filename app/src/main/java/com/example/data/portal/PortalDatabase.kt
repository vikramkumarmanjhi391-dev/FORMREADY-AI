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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
            sourceDocumentTitle = "Parivahan Citizen Portal Upload Parameters",
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
            lastVerifiedDate = "Jan 2025",
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
            verificationStatus = VerificationStatus.OFFICIALLY_VERIFIED,
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
        )
    )

    private val userCustomPortals = mutableListOf<PortalSpec>()

    fun getAllPortals(): List<PortalSpec> {
        return OFFICIAL_PORTALS + userCustomPortals
    }

    fun addCustomPortal(portal: PortalSpec) {
        userCustomPortals.add(portal)
    }

    fun findPortalById(id: String): PortalSpec? {
        return getAllPortals().find { it.id == id }
    }
}
