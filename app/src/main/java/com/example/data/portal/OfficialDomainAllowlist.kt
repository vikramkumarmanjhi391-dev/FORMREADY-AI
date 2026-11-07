package com.example.data.portal

import com.example.data.model.PortalSpec
import com.example.data.model.VerificationStatus
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Auditable governance validator for official government examination and citizen service portals.
 *
 * Key rules:
 * 1. An `https://` URL alone is NEVER sufficient for [VerificationStatus.OFFICIALLY_VERIFIED].
 * 2. The hostname must match an explicitly audited government/statutory agency domain in
 *    [VERIFIED_OFFICIAL_DOMAINS] or a verified `.gov.in` / `.nic.in` domain with non-blank
 *    official notification provenance.
 * 3. The 180-day freshness check (`isReviewDue`) is strictly an internal maintenance review policy,
 *    NOT a claim that official government rules automatically expire after 180 days. It never
 *    invalidates or alters the portal's actual [com.example.data.model.DocumentRule] values.
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
        val daysSinceLastCheck: Long?,
        val isInternalReviewDue: Boolean,
        val effectiveStatus: VerificationStatus,
        val provenanceSummary: String,
        val isOfficialDomain: Boolean = isOfficialDomainVerified,
        val reviewNote: String? = if (isInternalReviewDue) provenanceSummary else null
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
     * Checks whether [url] uses HTTPS and belongs to an auditable official government or
     * statutory examination authority domain.
     */
    fun isVerifiedOfficialUrl(url: String): Boolean {
        val host = extractHost(url)
        if (host.isEmpty()) return false
        if (host == "custom.gov.in" || host.startsWith("example.") || host.contains("localhost")) {
            return false
        }
        if (VERIFIED_OFFICIAL_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return true
        }
        return host.endsWith(".gov.in") || host.endsWith(".nic.in")
    }

    /**
     * Parses `lastVerifiedDate` strings such as `"2025-02-15"`, `"Feb 2025"`, or `"15 Feb 2025"`.
     */
    fun parseVerifiedDateEpochMs(dateStr: String): Long? {
        val cleaned = dateStr.trim()
        if (cleaned.isEmpty() || cleaned.equals("Today", ignoreCase = true)) return null
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
     * Evaluates a [PortalSpec]'s source evidence and returns a truthful [PortalProvenanceReport].
     *
     * - Only portals with a verified official domain, non-blank source document title, non-blank
     *   verification date, and `verificationStatus == OFFICIALLY_VERIFIED` retain the green
     *   [VerificationStatus.OFFICIALLY_VERIFIED] badge.
     * - If `daysSinceLastCheck > INTERNAL_REVIEW_FRESHNESS_DAYS`, `isInternalReviewDue` is set to
     *   `true` as an internal review note without invalidating the portal's document rules.
     */
    fun evaluatePortalProvenance(
        portal: PortalSpec,
        referenceEpochMs: Long = System.currentTimeMillis(),
        nowEpochMs: Long = referenceEpochMs
    ): PortalProvenanceReport {
        val effectiveNowMs = if (nowEpochMs != referenceEpochMs) nowEpochMs else referenceEpochMs
        val host = extractHost(portal.officialUrl)
        val domainVerified = isVerifiedOfficialUrl(portal.officialUrl)
        val hasMetadata = portal.sourceDocumentTitle.isNotBlank() &&
            !portal.sourceDocumentTitle.contains("User Configured", ignoreCase = true) &&
            portal.lastVerifiedDate.isNotBlank()

        val verifiedEpoch = parseVerifiedDateEpochMs(portal.lastVerifiedDate)
        val daysElapsed = if (verifiedEpoch != null && effectiveNowMs >= verifiedEpoch) {
            TimeUnit.MILLISECONDS.toDays(effectiveNowMs - verifiedEpoch)
        } else {
            null
        }
        val reviewDue = daysElapsed == null || daysElapsed > INTERNAL_REVIEW_FRESHNESS_DAYS

        val effectiveStatus = when {
            portal.verificationStatus == VerificationStatus.OFFICIALLY_VERIFIED -> {
                if (domainVerified && hasMetadata) {
                    VerificationStatus.OFFICIALLY_VERIFIED
                } else if (domainVerified) {
                    VerificationStatus.OFFICIAL_SOURCE_REVIEW
                } else {
                    VerificationStatus.UNVERIFIED_RULE
                }
            }
            else -> portal.verificationStatus
        }

        val summary = buildString {
            append(portal.sourceDocumentTitle.ifBlank { "Unspecified Source" })
            if (!portal.notificationNumber.isNullOrBlank()) {
                append(" (${portal.notificationNumber})")
            }
            if (host.isNotBlank()) {
                append(" • $host")
            }
            append(" • Checked: ${portal.lastVerifiedDate.ifBlank { "Unrecorded" }}")
            if (reviewDue && effectiveStatus == VerificationStatus.OFFICIALLY_VERIFIED) {
                append(" • Periodic Notification Review Recommended")
            }
        }

        return PortalProvenanceReport(
            portalId = portal.id,
            isOfficialDomainVerified = domainVerified,
            extractedHost = host,
            hasRequiredProvenanceMetadata = hasMetadata,
            daysSinceLastCheck = daysElapsed,
            isInternalReviewDue = reviewDue,
            effectiveStatus = effectiveStatus,
            provenanceSummary = summary
        )
    }
}
