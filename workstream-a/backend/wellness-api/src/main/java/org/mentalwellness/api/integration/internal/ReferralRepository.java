package org.mentalwellness.api.integration.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReferralRepository {

	private static final String COLUMNS = """
			referral_id, case_id, participant_ref, consent_event_id, partner_id, review_id, decision, follow_up_within,
			handoff_code, status, status_at, partner_reference, created_at""";

	private final JdbcClient jdbc;

	ReferralRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record ReferralRow(String referralId, String caseId, String participantRef, String consentEventId, String partnerId,
			String reviewId, String decision, String followUpWithin, String handoffCode, String status, Instant statusAt,
			String partnerReference, Instant createdAt) {
	}

	record MessageRow(String messageId, String referralId, String partnerId, String kind, String payload, int attempts,
			String consentEventId) {
	}

	// --- referrals ---------------------------------------------------------------------

	boolean referralExists(String referralId) {
		return this.jdbc.sql("SELECT count(*) FROM integration.referral WHERE referral_id = ?").param(referralId).query(Integer.class).single() > 0;
	}

	boolean liveReferralExists(String caseId, String partnerId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM integration.referral WHERE case_id = ? AND partner_id = ?
				  AND status NOT IN ('CLOSED', 'DECLINED', 'DELIVERY_FAILED', 'REVOKED')
				""").params(caseId, partnerId).query(Integer.class).single() > 0;
	}

	boolean handoffCodeExists(String code) {
		return this.jdbc.sql("SELECT count(*) FROM integration.referral WHERE handoff_code = ?").param(code).query(Integer.class).single() > 0;
	}

	void insertReferral(ReferralRow r, String dataOrigin, String subject) {
		this.jdbc.sql("""
				INSERT INTO integration.referral (referral_id, case_id, participant_ref, consent_event_id, partner_id, review_id,
				  decision, follow_up_within, handoff_code, data_origin, created_by_subject)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(r.referralId(), r.caseId(), r.participantRef(), r.consentEventId(), r.partnerId(), r.reviewId(), r.decision(),
					r.followUpWithin(), r.handoffCode(), dataOrigin, subject)
			.update();
	}

	Optional<ReferralRow> find(String referralId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM integration.referral WHERE referral_id = ?")
			.param(referralId)
			.query(ReferralRepository::referral)
			.optional();
	}

	Optional<ReferralRow> lock(String referralId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM integration.referral WHERE referral_id = ? FOR UPDATE")
			.param(referralId)
			.query(ReferralRepository::referral)
			.optional();
	}

	List<ReferralRow> forCase(String caseId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM integration.referral WHERE case_id = ? ORDER BY created_at, referral_id")
			.param(caseId)
			.query(ReferralRepository::referral)
			.list();
	}

	List<ReferralRow> liveForConsentEvent(String consentEventId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM integration.referral WHERE consent_event_id = ? AND status NOT IN ('DELIVERY_FAILED', 'REVOKED')
				FOR UPDATE
				""").param(consentEventId).query(ReferralRepository::referral).list();
	}

	void setStatus(String referralId, String status, Instant at) {
		this.jdbc.sql("UPDATE integration.referral SET status = ?, status_at = ? WHERE referral_id = ?")
			.params(status, Timestamp.from(at), referralId)
			.update();
	}

	void setPartnerReference(String referralId, String partnerReference) {
		this.jdbc.sql("UPDATE integration.referral SET partner_reference = ? WHERE referral_id = ? AND partner_reference IS NULL")
			.params(partnerReference, referralId)
			.update();
	}

	// --- outbox ------------------------------------------------------------------------

	void enqueue(String messageId, String referralId, String partnerId, String kind, String payload) {
		this.jdbc.sql("""
				INSERT INTO integration.outbound_message (message_id, referral_id, partner_id, kind, payload)
				VALUES (?, ?, ?, ?, ?)
				""").params(messageId, referralId, partnerId, kind, payload).update();
	}

	/**
	 * Cancels messages that haven't started sending. A message already SENDING is left alone: it
	 * may have reached the partner, so the caller treats it as possibly delivered.
	 */
	int cancelPending(String referralId) {
		return this.jdbc.sql("""
				UPDATE integration.outbound_message SET status = 'CANCELLED', lease_expires_at = NULL
				WHERE referral_id = ? AND status = 'PENDING'
				""").param(referralId).update();
	}

	/** True if the partner has, or may have, received this kind of message (delivered, or a send in flight). */
	boolean possiblyDelivered(String referralId, String kind) {
		return this.jdbc.sql("""
				SELECT count(*) FROM integration.outbound_message WHERE referral_id = ? AND kind = ? AND status IN ('DELIVERED', 'SENDING')
				""").params(referralId, kind).query(Integer.class).single() > 0;
	}

	void cancel(String messageId, String reason) {
		this.jdbc.sql("UPDATE integration.outbound_message SET status = 'CANCELLED', lease_expires_at = NULL, last_error = ? WHERE message_id = ?")
			.params(reason, messageId).update();
	}

	/** Due messages (pending, or a lease that expired), locked; other dispatchers skip them (ADR-007). */
	List<MessageRow> claimDue(int limit) {
		return this.jdbc.sql("""
				SELECT m.message_id, m.referral_id, m.partner_id, m.kind, m.payload, m.attempts, r.consent_event_id
				FROM integration.outbound_message m JOIN integration.referral r USING (referral_id)
				WHERE (m.status = 'PENDING' AND m.next_attempt_at <= now()) OR (m.status = 'SENDING' AND m.lease_expires_at < now())
				ORDER BY m.next_attempt_at, m.message_id LIMIT ? FOR UPDATE OF m SKIP LOCKED
				""").param(limit).query((rs, n) -> new MessageRow(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7))).list();
	}

	void lease(String messageId, Instant until) {
		this.jdbc.sql("UPDATE integration.outbound_message SET status = 'SENDING', attempts = attempts + 1, lease_expires_at = ? WHERE message_id = ?")
			.params(Timestamp.from(until), messageId).update();
	}

	/** Marks a message delivered unless it was cancelled meanwhile; true if it was still ours. */
	boolean markDelivered(String messageId) {
		return this.jdbc.sql("""
				UPDATE integration.outbound_message SET status = 'DELIVERED', delivered_at = now(), lease_expires_at = NULL,
				  last_error = NULL WHERE message_id = ? AND status = 'SENDING'
				""").param(messageId).update() == 1;
	}

	void retryLater(String messageId, Instant at, String error) {
		this.jdbc.sql("""
				UPDATE integration.outbound_message SET status = 'PENDING', next_attempt_at = ?, lease_expires_at = NULL,
				  last_error = ? WHERE message_id = ? AND status = 'SENDING'
				""").params(Timestamp.from(at), error, messageId).update();
	}

	boolean markFailed(String messageId, String error) {
		return this.jdbc.sql("""
				UPDATE integration.outbound_message SET status = 'FAILED', lease_expires_at = NULL, last_error = ?
				WHERE message_id = ? AND status = 'SENDING'
				""").params(error, messageId).update() == 1;
	}

	// --- inbound events ----------------------------------------------------------------

	record InboundRow(String referralId, String requestHash) {
	}

	Optional<InboundRow> findEvent(String partnerId, String eventId) {
		return this.jdbc.sql("SELECT referral_id, request_hash FROM integration.inbound_event WHERE partner_id = ? AND event_id = ?")
			.params(partnerId, eventId)
			.query(InboundRow.class)
			.optional();
	}

	Optional<Instant> latestEventAt(String referralId) {
		return this.jdbc.sql("""
				SELECT occurred_at FROM integration.inbound_event WHERE referral_id = ? AND applied
				ORDER BY occurred_at DESC LIMIT 1
				""")
			.param(referralId)
			.query((rs, n) -> instant(rs, 1))
			.optional();
	}

	void insertEvent(String partnerId, String eventId, String referralId, String status, Instant occurredAt, String hash,
			boolean applied) {
		this.jdbc.sql("""
				INSERT INTO integration.inbound_event (partner_id, event_id, referral_id, status, occurred_at, request_hash, applied)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""").params(partnerId, eventId, referralId, status, Timestamp.from(occurredAt), hash, applied).update();
	}

	// --- reporting (aggregate) ---------------------------------------------------------

	record ReferralCounts(long created, long delivered, long contactMade, long revoked) {
	}

	ReferralCounts monthly(Instant from, Instant to, String dataOrigin) {
		return this.jdbc.sql("""
				SELECT count(*),
				  count(*) FILTER (WHERE status NOT IN ('QUEUED', 'DELIVERY_FAILED')),
				  count(*) FILTER (WHERE status IN ('CONTACTED', 'APPOINTMENT_BOOKED', 'CLOSED')),
				  count(*) FILTER (WHERE status = 'REVOKED')
				FROM integration.referral WHERE created_at >= ? AND created_at < ? AND data_origin = ?
				""").params(Timestamp.from(from), Timestamp.from(to), dataOrigin)
			.query((rs, n) -> new ReferralCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)))
			.single();
	}

	// --- helpers -----------------------------------------------------------------------

	private static ReferralRow referral(ResultSet rs, int n) throws SQLException {
		return new ReferralRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), instant(rs, 11),
				rs.getString(12), instant(rs, 13));
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

}
