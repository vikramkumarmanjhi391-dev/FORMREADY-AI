package com.example.data.model

import android.net.Uri

enum class DocumentType(val displayName: String, val category: String) {
    PASSPORT_PHOTO("Passport Photograph", "Photo"),
    POSTCARD_PHOTO("Postcard Size Photograph (4x6)", "Photo"),
    SIGNATURE_ENGLISH("Signature (English)", "Signature"),
    SIGNATURE_HINDI("Signature (Hindi)", "Signature"),
    THUMB_IMPRESSION_LEFT("Left Thumb Impression (LTI)", "Signature"),
    THUMB_IMPRESSION_RIGHT("Right Thumb Impression (RTI)", "Signature"),
    DECLARATION_HANDWRITTEN("Handwritten Declaration", "Signature"),
    AADHAAR_CARD("Aadhaar Card (Front & Back)", "Identity"),
    PAN_CARD("PAN Card", "Identity"),
    VOTER_ID("Voter ID Card", "Identity"),
    DRIVING_LICENCE("Driving Licence", "Identity"),
    PASSPORT_DOC("Passport Booklet Copy", "Identity"),
    CLASS_10_MARKSHEET("Class 10 (Matric) Marksheet", "Education"),
    CLASS_10_CERTIFICATE("Class 10 Passing Certificate", "Education"),
    CLASS_12_MARKSHEET("Class 12 (Intermediate) Marksheet", "Education"),
    CLASS_12_CERTIFICATE("Class 12 Passing Certificate", "Education"),
    GRADUATION_DEGREE("Graduation Degree / Marksheet", "Education"),
    POST_GRADUATION("Post-Graduation Degree", "Education"),
    CASTE_SC_ST("SC / ST Caste Certificate", "Reservation"),
    CASTE_OBC_NCL_CENTRAL("OBC Non-Creamy Layer (Central Format)", "Reservation"),
    CASTE_OBC_NCL_STATE("OBC / BC / EBC (State Format)", "Reservation"),
    EWS_CERTIFICATE("Economically Weaker Section (EWS)", "Reservation"),
    DOMICILE_RESIDENCE("Domicile / Permanent Residence Certificate", "Civil"),
    INCOME_CERTIFICATE("Income Certificate", "Civil"),
    PWD_CERTIFICATE("PwD / Disability / UDID Certificate", "Disability"),
    EXPERIENCE_CERTIFICATE("Work Experience Certificate", "Employment"),
    NOC_CERTIFICATE("No Objection Certificate (NOC)", "Employment"),
    BANK_STATEMENT("Bank Account Statement", "Bank"),
    BANK_PASSBOOK("Bank Passbook Copy", "Bank"),
    BANK_CHEQUE("Cancelled Cheque", "Bank"),
    INSURANCE_POLICY("Insurance Policy Document", "Insurance"),
    INSURANCE_RECEIPT("Insurance Premium Receipt", "Insurance"),
    VEHICLE_RC("Vehicle Registration (RC Book)", "Vehicle"),
    VEHICLE_PUC("Pollution Under Control (PUC)", "Vehicle"),
    VEHICLE_INSURANCE("Motor Insurance Policy", "Vehicle"),
    OTHER_SUPPORTING("Other Supporting Document", "General")
}

enum class FileFormat(val extension: String, val mimeType: String) {
    JPG("jpg", "image/jpeg"),
    JPEG("jpeg", "image/jpeg"),
    PNG("png", "image/png"),
    PDF("pdf", "application/pdf"),
    JPG_OR_PDF("jpg/pdf", "image/jpeg,application/pdf")
}

enum class ColorMode(val label: String) {
    COLOUR("Colour"),
    GRAYSCALE("Grayscale"),
    BLACK_AND_WHITE("Black & White (Monochrome)"),
    ANY("Any")
}

enum class VerificationStatus(val label: String, val isOfficiallyVerified: Boolean) {
    OFFICIALLY_VERIFIED("Officially Verified", true),
    OFFICIAL_SOURCE_REVIEW("Official Source Found (Review)", false),
    USER_SUPPLIED("User Supplied", false),
    UNVERIFIED_RULE("Unverified Rule", false),
    NEEDS_REVERIFICATION("Needs Re-verification", false),
    OUTDATED("Outdated / Archive", false)
}

enum class PreparationStatus(val label: String) {
    SUCCESS_PREPARED("Successfully Prepared"),
    SIZE_EXCEEDED("File Size Exceeded"),
    SIZE_BELOW_MIN("File Size Too Small"),
    DIMENSIONS_MISMATCH("Dimensions Mismatch"),
    BACKGROUND_NOT_WHITE("Background Not White"),
    DPI_MISMATCH("DPI Metadata Mismatch"),
    CONFLICT_LIMITS("Requirements Conflict"),
    FAILED("Processing Failed")
}

data class DocumentRule(
    val id: String,
    val documentType: DocumentType,
    val isRequired: Boolean = true,
    val allowedFormats: List<FileFormat> = listOf(FileFormat.JPG),
    val minKb: Int = 10,
    val maxKb: Int = 50,
    val exactWidthPx: Int? = null,
    val exactHeightPx: Int? = null,
    val minWidthPx: Int? = null,
    val maxWidthPx: Int? = null,
    val minHeightPx: Int? = null,
    val maxHeightPx: Int? = null,
    val targetAspectRatio: Float? = null, // e.g. 3.5f / 4.5f
    val physicalWidthMm: Float? = null,
    val physicalHeightMm: Float? = null,
    val dpi: Int? = null,
    val maxPages: Int = 1,
    val colorMode: ColorMode = ColorMode.COLOUR,
    val requiresWhiteBackground: Boolean = false,
    val requiresNameAndDateStrip: Boolean = false,
    val instructions: String = ""
)

data class PortalSpec(
    val id: String,
    val portalName: String,
    val examOrServiceName: String,
    val department: String,
    val stateOrCentral: String, // "Central", "Bihar", "Uttar Pradesh", etc.
    val year: String = "2025-2026",
    val category: String, // "Recruitment", "Civil Services", "Banking", "Railways", "Entrance", "RTPS / Services"
    val officialUrl: String,
    val sourceDocumentTitle: String,
    val verificationStatus: VerificationStatus,
    val lastVerifiedDate: String,
    val version: String = "v1.4",
    val documents: List<DocumentRule>
)

data class ProcessedFileResult(
    val ruleId: String,
    val documentType: DocumentType,
    val originalFileName: String,
    val processedFilePath: String,
    val fileFormat: FileFormat,
    val fileSizeBytes: Long,
    val fileSizeKb: Double,
    val widthPx: Int,
    val heightPx: Int,
    val pageCount: Int = 1,
    val isCompliant: Boolean,
    val validationMessages: List<String>,
    val status: PreparationStatus = if (isCompliant) PreparationStatus.SUCCESS_PREPARED else PreparationStatus.SIZE_EXCEEDED,
    val aspectRatio: Float = if (heightPx > 0) widthPx.toFloat() / heightPx.toFloat() else 1.0f,
    val hasConflict: Boolean = false,
    val conflictReason: String? = null,
    val dpi: Int = 300,
    val wasPaddedToMinKb: Boolean = false,
    val hasWhiteBackground: Boolean = false,
    val backgroundVerificationState: String = "UNVERIFIED",
    val nearWhitePixelRatio: Float = 0f,
    val exactWhitePixelRatio: Float = 0f,
    val backgroundMeanLuminance: Int = 0
)

data class ValidationIssue(
    val severity: IssueSeverity,
    val message: String
)

enum class IssueSeverity {
    ERROR,
    WARNING,
    INFO
}

data class DocumentValidationSummary(
    val isValid: Boolean,
    val issues: List<ValidationIssue>,
    val complianceScore: Int // 0 to 100
)

data class LockerItem(
    val id: String,
    val title: String,
    val documentType: DocumentType,
    val filePath: String,
    val uriString: String? = null,
    val fileFormat: FileFormat,
    val fileSizeKb: Double,
    val dateAdded: Long = System.currentTimeMillis(),
    val notes: String = "",
    val isMasked: Boolean = false
)
