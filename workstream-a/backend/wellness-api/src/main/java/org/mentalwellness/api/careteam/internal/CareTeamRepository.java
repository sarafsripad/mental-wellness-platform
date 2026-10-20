package org.mentalwellness.api.careteam.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CareTeamRepository {

	private final JdbcClient jdbc;

	CareTeamRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record FacilityRow(String facilityCode, String kind, String facilityLabel, boolean active) {
	}

	record MembershipRow(String facilityCode, String facilityLabel, String kind, String careRole) {
	}

	record CareTeamRow(String facilityCode, String facilityLabel, String kind, String reasonCode, Instant addedAt) {
	}

	// --- facilities and staff ----------------------------------------------------------

	void upsertFacility(String code, String kind, String label, boolean active) {
		this.jdbc.sql("""
				INSERT INTO careteam.facility (facility_code, kind, facility_label, active) VALUES (?, ?, ?, ?)
				ON CONFLICT (facility_code) DO UPDATE SET kind = EXCLUDED.kind, facility_label = EXCLUDED.facility_label,
				  active = EXCLUDED.active, updated_at = now()
				""").params(code, kind, label, active).update();
	}

	Optional<FacilityRow> findFacility(String code) {
		return this.jdbc.sql("SELECT facility_code, kind, facility_label, active FROM careteam.facility WHERE facility_code = ?")
			.param(code)
			.query(FacilityRow.class)
			.optional();
	}

	void upsertMembership(String subject, String facility, String role, boolean active, String by) {
		this.jdbc.sql("""
				INSERT INTO careteam.staff_membership (subject, facility_code, care_role, active, updated_by_subject)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (subject, facility_code) DO UPDATE SET care_role = EXCLUDED.care_role, active = EXCLUDED.active,
				  updated_by_subject = EXCLUDED.updated_by_subject, updated_at = now()
				""").params(subject, facility, role, active, by).update();
	}

	List<MembershipRow> memberships(String subject) {
		return this.jdbc.sql("""
				SELECT f.facility_code, f.facility_label, f.kind, m.care_role
				FROM careteam.staff_membership m JOIN careteam.facility f USING (facility_code)
				WHERE m.subject = ? AND m.active AND f.active ORDER BY f.facility_code
				""").param(subject).query(MembershipRow.class).list();
	}

	// --- care teams ----------------------------------------------------------------------

	void addToCareTeam(String participantRef, String facility, String reason, String by) {
		this.jdbc.sql("""
				INSERT INTO careteam.participant_care_team (participant_ref, facility_code, reason_code, added_by_subject)
				VALUES (?, ?, ?, ?) ON CONFLICT (participant_ref, facility_code) DO NOTHING
				""").params(participantRef, facility, reason, by).update();
	}

	List<CareTeamRow> careTeam(String participantRef) {
		return this.jdbc.sql("""
				SELECT f.facility_code, f.facility_label, f.kind, c.reason_code, c.added_at
				FROM careteam.participant_care_team c JOIN careteam.facility f USING (facility_code)
				WHERE c.participant_ref = ? ORDER BY c.added_at, f.facility_code
				""").param(participantRef)
			.query((rs, n) -> new CareTeamRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
					rs.getTimestamp(5).toInstant()))
			.list();
	}

	/** Active membership in one of {@code roles}, at an active facility on the participant's care team. */
	boolean staffAccess(String subject, String participantRef, Set<String> roles) {
		return this.jdbc.sql("""
				SELECT count(*) FROM careteam.staff_membership m
				JOIN careteam.facility f ON f.facility_code = m.facility_code AND f.active
				JOIN careteam.participant_care_team c ON c.facility_code = m.facility_code AND c.participant_ref = :ppt
				WHERE m.subject = :subject AND m.active AND m.care_role = ANY(:roles)
				""")
			.param("ppt", participantRef)
			.param("subject", subject)
			.param("roles", roles.toArray(String[]::new))
			.query(Integer.class)
			.single() > 0;
	}

	boolean worksAt(String subject, String facility, Set<String> roles) {
		return this.jdbc.sql("""
				SELECT count(*) FROM careteam.staff_membership m
				JOIN careteam.facility f ON f.facility_code = m.facility_code AND f.active
				WHERE m.subject = :subject AND m.facility_code = :facility AND m.active AND m.care_role = ANY(:roles)
				""")
			.param("subject", subject)
			.param("facility", facility)
			.param("roles", roles.toArray(String[]::new))
			.query(Integer.class)
			.single() > 0;
	}

	static Timestamp ts(Instant i) {
		return Timestamp.from(i);
	}

}
