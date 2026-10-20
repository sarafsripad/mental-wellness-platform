# Partner Programme Onboarding

**Status (2026-10-20):** no real partner is connected. The platform talks only to the local **SIMULATED** partner (`deploy/local/partner-simulator`). This page lists what must exist before any real partner is switched on (ADR-015, CLAUDE.md rule 7). The software enforces part of it: a partner with `mode: REAL` refuses to start without a recorded agreement and approver, and while the environment isn't approved for participant data.

## Every partner

| # | Needed | From | Notes |
|---|---|---|---|
| 1 | Signed data-sharing agreement naming the purpose, the data items, retention, onward sharing and breach duties | Programme + partner + legal | Recorded as `agreement-ref` |
| 2 | The partner's technical specification: endpoint, authentication (mTLS or OAuth), payload format, status codes, environments | Partner | Replaces the simulator's HMAC signature if different |
| 3 | Consent wording that names the partner, approved and translated, **and code that ties partners to the form** | Ethics committee / programme; engineering | Today `EXTERNAL_REFERRAL_SHARING` is one generic scope: the code accepts any enabled partner once it's granted, and form 0.2.0 says "for example Tele-MANAS or eSanjeevani". Before any REAL partner, each consent form version must list its allowed partners, checked when a referral is created. |
| 4 | Mapping of decisions and timeframes to the partner's own triage or priority codes | Clinical lead + partner | Today REFER becomes `routine`, URGENT_REFER becomes `urgent` |
| 5 | Security review of the connection, plus credentials in a secrets manager (not `.env`) | Security reviewer | |
| 6 | Decision on contact details. Today referrals carry no name or phone, and the ASHA worker does a warm handoff. | Privacy lead + programme | Sending contact details needs new identity fields and an audited release path (ADR-013/015) |
| 7 | Production environment approved for participant data (consent-and-data-lifecycle.md §14) | Legal, ethics, privacy | The `allowed-origins` setting includes `PARTICIPANT` only then |
| 8 | Named approver for switching the partner on | Programme | Recorded as `approved-by`. Rule 6: no automatic go-live. |
| 9 | Monitoring and incident contacts on both sides; CERT-In and DPDP breach process | Programme + partner | |

## By partner

**Tele-MANAS** (MoHFW / NIMHANS, helpline 14416)
- The helpline is already shown to workers and clinicians as a support contact. That's configuration only, and marked pending verification until the clinical lead verifies it.
- **Data exchange:** there is no public API. It needs an agreement with the programme and its specification. The likely flow is a warm handoff: the worker calls with the person and quotes the handoff code. An electronic referral is optional and depends on what the programme offers.

**eSanjeevani** (teleconsultation)
- There is no public API. Booking a teleconsultation needs an agreement and a specification. The referral bundle's timeframe can map to an appointment window.

**ABDM / ABHA** (Ayushman Bharat Digital Mission)
- Register on the ABDM sandbox, choose the roles (HIP and/or HIU), complete certification, and use ABDM's consent manager flow for sharing.
- **ABHA capture is a new identifier.** It goes in the identity module, never in `app_db`, and needs privacy-lead approval.
- The referral bundle is already FHIR R4 (Patient + ServiceRequest). It must be aligned with the NRCeS/ABDM profiles before certification.

**State HMIS / NHM**
- Obtain the state's reporting format and its channel (upload, API or portal).
- The platform's neutral monthly aggregate report (`contracts/integration/hmis-aggregate-report.schema.json`, counts only, 1–4 shown as `<5`) gets mapped to that format.
- Agree the statistical disclosure rules, including complementary suppression and minimum geography. The platform holds no locality today.

## Turning on a partner (once 1–9 exist)

1. Add the partner's configuration with `mode: REAL`, `enabled: false`, `agreement-ref` and `approved-by`.
2. Deploy to a non-production environment against the partner's test system, and run the referral e2e suite there.
3. A security-reviewer pass and the named approval, then set `enabled: true`. The dispatcher kill switch (`wellness.integration.dispatch.enabled`) stays available to stop all sending immediately.
