package com.example.data.portal

import com.example.data.model.PortalSpec
import com.example.data.model.VerificationStatus
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Auditable governance validator for official government examination and citizen service portals (Phase 0.4C).
 *
 * Strict Governance Rules:
 * 1. An `https://` URL or generic `.gov.in` / `.nic.in` suffix is NEVER sufficient for
 *    [VerificationStatus.OFFICIALLY_VERIFIED]. Only hosts matching [VERIFIED_OFFICIAL_DOMAINS]
 *    return `isVerifiedOfficialUrl == true`.
 * 2. A preset retains `effectiveStatus == VerificationStatus.OFFICIALLY_VERIFIED` ONLY when:
 *    - `isVerifiedOfficialUrl(portal.officialUrl)` is `true`,
 *    - `portal.sourceDocumentTitle.isNotBlank()`,
 *    - `!portal.sourceIdentifier.isNullOrBlank()` (or `!portal.notificationNumber.isNullOrBlank()`),
 *    - `portal.applicableWorkflow.isNotBlank()`,
 *    - `daysSinceLastCheck != null && daysSinceLastCheck in 0..INTERNAL_REVIEW_FRESHNESS_DAYS`, AND
 *    - Every [com.example.data.model.DocumentRule] in `portal.documents` has
 *      `!rule.sourceClauseRef.isNullOrBlank() && rule.isRuleIndividuallyVerified`.
 * 3. Whenever any provenance field is missing (`null`/blank), unrecorded, or stale (`> 180` days),
 *    `evaluatePortalProvenance()` downgrades `effectiveStatus` to
 *    [VerificationStatus.OFFICIAL_SOURCE_REVIEW] (or [VerificationStatus.UNVERIFIED_RULE] if the
 *    domain is not in [VERIFIED_OFFICIAL_DOMAINS]), without altering or deleting the portal's
 *    underlying [com.example.data.model.DocumentRule] parameters.
 */
object OfficialDomainAllowlist {

    const val INTERNAL_REVIEW_FRESHNESS_DAYS = 180L

    val VERIFIED_OFFICIAL_DOMAINS: Set<String> = setOf(
        "upsconline.nic.in",
        "upsc.gov.in",
        "ssc.gov.in",
        "ssc.nic.in",
        "onlinebpsc.bihar.gov.in",
        "bpsc.bih.nic.in",
        "bpsc.bihar.gov.in",
        "rrbapply.gov.in",
        "indianrailways.gov.in",
        "ibps.in",
        "sbi.co.in",
        "rbi.org.in",
        "ctet.nic.in",
        "nta.ac.in",
        "exams.nta.ac.in",
        "jeemain.nta.nic.in",
        "neet.nta.nic.in",
        "serviceonline.bihar.gov.in",
        "edistrict.up.gov.in",
        "uppsc.up.nic.in",
        "uppbpb.gov.in",
        "csbc.bih.nic.in",
        "bpssc.bih.nic.in",
        "passportindia.gov.in",
        "tin.tin.proteantech.in",
        "proteantech.in",
        "pan.utiitsl.com",
        "utiitsl.com",
        "uidai.gov.in",
        "voters.eci.gov.in",
        "parivahan.gov.in"
    )

    data class PortalProvenanceReport(
        val portalId: String,
        val isOfficialDomainVerified: Boolean,
        val extractedHost: String,
        val hasRequiredProvenanceMetadata: Boolean,
        val allRulesIndividuallyVerified: Boolean = false,
        val daysSinceLastCheck: Long?,
        val isInternalReviewDue: Boolean,
        val effectiveStatus: VerificationStatus,
        val provenanceSummary: String,
        val isOfficialDomain: Boolean = isOfficialDomainVerified,
        val reviewNote: String? = if (isInternalReviewDue || !hasRequiredProvenanceMetadata) provenanceSummary else null
    )

    fun extractHost(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: ""
            if (scheme != "https") return ""
            (uri.host ?: "").lowercase(Locale.US).removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Checks whether [url] uses HTTPS and belongs to an explicitly allowlisted official domain in
     * [VERIFIED_OFFICIAL_DOMAINS]. Wildcard `.gov.in` / `.nic.in` suffixes are strictly rejected.
     */
    fun isVerifiedOfficialUrl(url: String): Boolean {
        val host = extractHost(url)
        if (host.isEmpty()) return false
        if (host == "custom.gov.in" || host.startsWith("example.") || host.contains("localhost")) {
            return false
        }
        return VERIFIED_OFFICIAL_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Parses `lastVerifiedDate` strings such as `"2025-02-15"`, `"Feb 2025"`, or `"15 Feb 2025"`.
     */
    fun parseVerifiedDateEpochMs(dateStr: String): Long? {
        val cleaned = dateStr.trim()
        if (cleaned.isEmpty() || cleaned.equals("Today", ignoreCase = true) || cleaned.equals("Synced", ignoreCase = true)) {
            return null
        }
        val patterns = listOf(
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "dd MMM yyyy",
            "MMM yyyy",
            "MMMM yyyy"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val parsed = sdf.parse(cleaned)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Evaluates a [PortalSpec]'s source evidence and returns a conservative [PortalProvenanceReport].
     */
    fun evaluatePortalProvenance(
        portal: PortalSpec,
        referenceEpochMs: Long = System.currentTimeMillis(),
        nowEpochMs: Long = referenceEpochMs
    ): PortalProvenanceReport {
        val effectiveNowMs = if (nowEpochMs != referenceEpochMs) nowEpochMs else referenceEpochMs
        val host = extractHost(portal.officialUrl)
        val domainVerified = isVerifiedOfficialUrl(portal.officialUrl)

        val effectiveSourceId = portal.sourceIdentifier?.takeIf { it.isNotBlank() }
            ?: portal.notificationNumber?.takeIf { it.isNotBlank() }

        val hasPortalMetadata = portal.sourceDocumentTitle.isNotBlank() &&
            !portal.sourceDocumentTitle.contains("User Configured", ignoreCase = true) &&
            !portal.sourceDocumentTitle.contains("GitHub JSON", ignoreCase = true) &&
            !effectiveSourceId.isNullOrBlank() &&
            !effectiveSourceId.equals("UNRECORDED", ignoreCase = true) &&
            !effectiveSourceId.equals("None", ignoreCase = true) &&
            portal.applicableWorkflow.isNotBlank() &&
            portal.lastVerifiedDate.isNotBlank()

        val allRulesVerified = portal.documents.isNotEmpty() && portal.documents.all { rule ->
            !rule.sourceClauseRef.isNullOrBlank() &&
                !rule.sourceClauseRef.equals("UNRECORDED", ignoreCase = true) &&
                rule.isRuleIndividuallyVerified
        }

        val verifiedEpoch = parseVerifiedDateEpochMs(portal.lastVerifiedDate)
        val daysElapsed = if (verifiedEpoch != null && effectiveNowMs >= verifiedEpoch) {
            TimeUnit.MILLISECONDS.toDays(effectiveNowMs - verifiedEpoch)
        } else {
            null
        }
        val reviewDue = daysElapsed == null || daysElapsed > INTERNAL_REVIEW_FRESHNESS_DAYS
        val hasRequiredProvenance = hasPortalMetadata && allRulesVerified && daysElapsed != null

        val effectiveStatus = when (portal.verificationStatus) {
            VerificationStatus.USER_SUPPLIED,
            VerificationStatus.NEEDS_REVERIFICATION,
            VerificationStatus.UNVERIFIED_RULE,
            VerificationStatus.OUTDATED -> portal.verificationStatus
            VerificationStatus.OFFICIAL_SOURCE_REVIEW -> {
                if (domainVerified) VerificationStatus.OFFICIAL_SOURCE_REVIEW else VerificationStatus.UNVERIFIED_RULE
            }
            VerificationStatus.OFFICIALLY_VERIFIED -> {
                when {
                    !domainVerified -> VerificationStatus.UNVERIFIED_RULE
                    hasRequiredProvenance && !reviewDue -> VerificationStatus.OFFICIALLY_VERIFIED
                    else -> VerificationStatus.OFFICIAL_SOURCE_REVIEW
                }
            }
        }

        val summary = buildString {
            append(portal.sourceDocumentTitle.ifBlank { "Unspecified Source" })
            if (!effectiveSourceId.isNullOrBlank()) {
                append(" ($effectiveSourceId)")
            } else {
                append(" (Source ID unrecorded)")
            }
            if (portal.applicableWorkflow.isNotBlank()) {
                append(" • Workflow: ${portal.applicableWorkflow}")
            }
            if (host.isNotBlank()) {
                append(" • $host")
            }
            append(" • Checked: ${portal.lastVerifiedDate.ifBlank { "Unrecorded" }}")
            if (reviewDue || !hasRequiredProvenance) {
                append(" • Official Source Review Required")
            }
        }

        return PortalProvenanceReport(
            portalId = portal.id,
            isOfficialDomainVerified = domainVerified,
            extractedHost = host,
            hasRequiredProvenanceMetadata = hasRequiredProvenance,
            allRulesIndividuallyVerified = allRulesVerified,
            daysSinceLastCheck = daysElapsed,
            isInternalReviewDue = reviewDue,
            effectiveStatus = effectiveStatus,
            provenanceSummary = summary
        )
    }
}
