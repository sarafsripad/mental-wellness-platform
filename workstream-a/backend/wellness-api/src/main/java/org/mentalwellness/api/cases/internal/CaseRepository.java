package org.mentalwellness.api.cases.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CaseRepository {

	private static final String CASE_COLUMNS = """
			c.case_id, c.participant_ref, c.consent_event_id, c.language_tag, c.data_origin, c.age_band, c.gender,
			c.opened_at, c.opened_by_subject, c.urgent_review_flag, c.follow_up_requested, c.completed_at, c.created_at,
			EXISTS (SELECT 1 FROM cases.observation_set o WHERE o.case_id = c.case_id),
			EXISTS (SELECT 1 FROM cases.questionnaire_response q WHERE q.case_id = c.case_id),
			(SELECT r.decision FROM cases.case_review r WHERE r.case_id = c.case_id ORDER BY r.seq DESC LIMIT 1),
			(SELECT r.follow_up_within FROM cases.case_review r WHERE r.case_id = c.case_id ORDER BY r.seq DESC LIMIT 1),
			(SELECT r.reviewed_at FROM cases.case_review r WHERE r.case_id = c.case_id ORDER BY r.seq DESC LIMIT 1),
			(SELECT r.review_id FROM cases.case_review r WHERE r.case_id = c.case_id ORDER BY r.seq DESC LIMIT 1)
			""";

	private static final String REVIEW_SELECT = """
			SELECT review_id, case_id, decision, follow_up_within, reviewer_subject, reviewed_at, created_at
			FROM cases.case_review""";

	private final JdbcClient jdbc;

	CaseRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record CaseRow(String caseId, String participantRef, String consentEventId, String languageTag, String dataOrigin,
			String ageBand, String gender, Instant openedAt, String openedBySubject, boolean urgentReviewFlag,
			Boolean followUpRequested, Instant completedAt, Instant createdAt, boolean observationsSubmitted,
			boolean questionnaireSubmitted, String reviewDecision, String reviewFollowUpWithin, Instant reviewedAt,
			String reviewId) {

		boolean completed() {
			return this.completedAt != null;
		}

		/** Flagged by the safety question or by the worker: a trained person must follow up. */
		boolean needsReview() {
			return this.urgentReviewFlag || Boolean.TRUE.equals(this.followUpRequested);
		}

	}

	record ReviewRow(String reviewId, String caseId, String decision, String followUpWithin, String reviewerSubject,
			Instant reviewedAt, Instant createdAt) {
	}

	// --- cases ---------------------------------------------------------------------

	boolean caseExists(String caseId) {
		return this.jdbc.sql("SELECT count(*) FROM cases.assessment_case WHERE case_id = ?").param(caseId).query(Integer.class).single() > 0;
	}

	void insertCase(CaseApi.CaseCreate c, CaseApi.Gender gender, String subject) {
		this.jdbc.sql("""
				INSERT INTO cases.assessment_case (case_id, participant_ref, consent_event_id, language_tag, data_origin,
				  age_band, gender, opened_at, opened_by_subject)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(c.caseId(), c.participantRef(), c.consentEventId(), c.languageTag(), c.dataOrigin().name(),
					c.ageBand().name(), gender.name(), Timestamp.from(c.openedAtUtc()), subject)
			.update();
	}

	Optional<CaseRow> findCase(String caseId) {
		return this.jdbc.sql("SELECT " + CASE_COLUMNS + " FROM cases.assessment_case c WHERE c.case_id = ?")
			.param(caseId)
			.query(CaseRepository::caseRow)
			.optional();
	}

	/** Serialises submissions for one case (row lock until the transaction ends). */
	void lockCase(String caseId) {
		this.jdbc.sql("SELECT case_id FROM cases.assessment_case WHERE case_id = ? FOR UPDATE").param(caseId).query(String.class).single();
	}

	List<CaseRow> forParticipant(String participantRef) {
		return this.jdbc.sql("SELECT " + CASE_COLUMNS + " FROM cases.assessment_case c WHERE c.participant_ref = ? ORDER BY c.opened_at, c.case_id")
			.param(participantRef)
			.query(CaseRepository::caseRow)
			.list();
	}

	List<CaseRow> casesOpenedBy(String subject, boolean followUpOnly, int limit) {
		return this.jdbc.sql("SELECT " + CASE_COLUMNS + """
				 FROM cases.assessment_case c
				WHERE c.opened_by_subject = :subject
				  AND (NOT :followUpOnly OR c.urgent_review_flag OR c.follow_up_requested)
				ORDER BY c.created_at DESC, c.case_id
				LIMIT :limit
				""")
			.param("subject", subject)
			.param("followUpOnly", followUpOnly)
			.param("limit", limit)
			.query(CaseRepository::caseRow)
			.list();
	}

	void raiseUrgentReviewFlag(String caseId) {
		this.jdbc.sql("UPDATE cases.assessment_case SET urgent_review_flag = true WHERE case_id = ?").param(caseId).update();
	}

	void complete(String caseId, Instant completedAt, boolean followUpRequested) {
		this.jdbc.sql("UPDATE cases.assessment_case SET completed_at = ?, follow_up_requested = ? WHERE case_id = ?")
			.params(Timestamp.from(completedAt), followUpRequested, caseId)
			.update();
	}

	/** Cases needing review: urgent first, then the oldest. Consent is filtered by the caller. */
	List<CaseRow> reviewQueue(CaseApi.QueueFilter filter, int limit) {
		return this.jdbc.sql("SELECT " + CASE_COLUMNS + """
				 FROM cases.assessment_case c
				WHERE (c.urgent_review_flag OR c.follow_up_requested)
				  AND (:filter = 'ALL'
				       OR (:filter = 'REVIEWED') = EXISTS (SELECT 1 FROM cases.case_review r WHERE r.case_id = c.case_id))
				ORDER BY c.urgent_review_flag DESC, c.created_at, c.case_id
				LIMIT :limit
				""")
			.param("filter", filter.name())
			.param("limit", limit)
			.query(CaseRepository::caseRow)
			.list();
	}

	// --- reviews ---------------------------------------------------------------------

	boolean reviewExists(String reviewId) {
		return this.jdbc.sql("SELECT count(*) FROM cases.case_review WHERE review_id = ?").param(reviewId).query(Integer.class).single() > 0;
	}

	void insertReview(String caseId, CaseApi.ClinicianReviewCreate r, String subject) {
		this.jdbc.sql("""
				INSERT INTO cases.case_review (review_id, case_id, decision, follow_up_within, reviewed_at, reviewer_subject)
				VALUES (?, ?, ?, ?, ?, ?)
				""")
			.params(r.reviewId(), caseId, r.decision().name(), r.followUpWithin().name(), Timestamp.from(r.reviewedAtUtc()),
					subject)
			.update();
	}

	Optional<ReviewRow> findReview(String reviewId) {
		return this.jdbc.sql(REVIEW_SELECT + " WHERE review_id = ?").param(reviewId).query(CaseRepository::reviewRow).optional();
	}

	List<ReviewRow> reviews(String caseId) {
		return this.jdbc.sql(REVIEW_SELECT + " WHERE case_id = ? ORDER BY seq").param(caseId).query(CaseRepository::reviewRow).list();
	}

	List<CaseApi.Observation> observations(String caseId) {
		return this.jdbc.sql("""
				SELECT o.code, o.value_code FROM cases.observation o JOIN cases.observation_set s USING (observation_set_id)
				WHERE s.case_id = ? ORDER BY o.code, o.value_code
				""")
			.param(caseId)
			.query((rs, n) -> new CaseApi.Observation(CaseApi.ObservationCode.valueOf(rs.getString(1)), rs.getString(2)))
			.list();
	}

	Optional<CaseApi.Questionnaire> questionnaire(String caseId, boolean validated) {
		record Head(String responseId, String instrumentId, String instrumentVersion) {
		}
		return this.jdbc.sql("SELECT response_id, instrument_id, instrument_version FROM cases.questionnaire_response WHERE case_id = ?")
			.param(caseId)
			.query(Head.class)
			.optional()
			.map(h -> new CaseApi.Questionnaire(h.instrumentId(), h.instrumentVersion(), validated,
					this.jdbc.sql("SELECT item_id, answer_code FROM cases.questionnaire_answer WHERE response_id = ? ORDER BY item_id")
						.param(h.responseId())
						.query((rs, n) -> new CaseApi.Answer(rs.getString(1), rs.getString(2)))
						.list()));
	}

	// --- observations and questionnaire answers ------------------------------------

	boolean observationSetExists(String observationSetId) {
		return this.jdbc.sql("SELECT count(*) FROM cases.observation_set WHERE observation_set_id = ?")
			.param(observationSetId)
			.query(Integer.class)
			.single() > 0;
	}

	void insertObservations(String caseId, CaseApi.ObservationSetCreate set) {
		this.jdbc.sql("""
				INSERT INTO cases.observation_set (observation_set_id, case_id, catalogue_id, catalogue_version, observed_at)
				VALUES (?, ?, ?, ?, ?)
				""")
			.params(set.observationSetId(), caseId, set.catalogue().catalogueId(), set.catalogue().catalogueVersion(),
					Timestamp.from(set.observedAtUtc()))
			.update();
		for (CaseApi.Observation o : set.observations()) {
			this.jdbc.sql("INSERT INTO cases.observation (observation_set_id, code, value_code) VALUES (?, ?, ?)")
				.params(set.observationSetId(), o.code().name(), o.valueCode())
				.update();
		}
	}

	boolean responseExists(String responseId) {
		return this.jdbc.sql("SELECT count(*) FROM cases.questionnaire_response WHERE response_id = ?")
			.param(responseId)
			.query(Integer.class)
			.single() > 0;
	}

	void insertResponse(String caseId, CaseApi.QuestionnaireResponseCreate r) {
		this.jdbc.sql("""
				INSERT INTO cases.questionnaire_response (response_id, case_id, instrument_id, instrument_version,
				  language_tag, answered_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""")
			.params(r.responseId(), caseId, r.instrument().instrumentId(), r.instrument().instrumentVersion(),
					r.languageTag(), Timestamp.from(r.answeredAtUtc()))
			.update();
		for (CaseApi.Answer a : r.items()) {
			this.jdbc.sql("INSERT INTO cases.questionnaire_answer (response_id, item_id, answer_code) VALUES (?, ?, ?)")
				.params(r.responseId(), a.itemId(), a.answerCode())
				.update();
		}
	}

	private static CaseRow caseRow(ResultSet rs, int n) throws SQLException {
		boolean followUpNull = rs.getObject(11) == null;
		return new CaseRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getString(6), rs.getString(7), instant(rs, 8), rs.getString(9), rs.getBoolean(10),
				followUpNull ? null : rs.getBoolean(11), instant(rs, 12), instant(rs, 13), rs.getBoolean(14),
				rs.getBoolean(15), rs.getString(16), rs.getString(17), instant(rs, 18), rs.getString(19));
	}

	private static ReviewRow reviewRow(ResultSet rs, int n) throws SQLException {
		return new ReviewRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				instant(rs, 6), instant(rs, 7));
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

}
