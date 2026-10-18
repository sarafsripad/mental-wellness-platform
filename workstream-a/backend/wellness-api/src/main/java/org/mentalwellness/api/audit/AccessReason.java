package org.mentalwellness.api.audit;

/** Reason codes for ADMIN access ({@code X-Access-Reason} in the v1 contract). Codes only, no free text. */
public enum AccessReason {

	SUPPORT_TICKET, DATA_SUBJECT_REQUEST, AUDIT, INCIDENT_RESPONSE

}
