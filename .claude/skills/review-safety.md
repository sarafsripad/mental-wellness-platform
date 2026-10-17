---
name: review-safety
description: Review a proposed change for privacy, security, and clinical-safety implications. Produces a structured safety assessment with blocking issues and recommendations.
---

# /review-safety — Safety Review

## What this skill does

Conducts a structured review of a proposed or implemented change across three dimensions: data privacy, application security, and clinical safety. Produces a written assessment with blocking issues and recommendations.

## Usage

Invoke with `/review-safety` followed by the scope. Examples:

- `/review-safety review the consent service changes in the last diff`
- `/review-safety assess the proposed model deployment`
- `/review-safety check the new audio upload endpoint for security issues`

## Workflow

When this skill is invoked:

1. **Identify the scope.** What files, services, or data flows are in scope? Read the relevant source code, contracts, and documentation.

2. **Privacy review:**
   - Is any direct identifier at risk of exposure?
   - Is pseudonymisation enforced at the collection boundary?
   - Are all consent references resolvable — no assumed consent?
   - Is audio data encrypted at rest and in transit?
   - Are there any new data collection points not covered by an existing consent form version?
   - Is there a withdrawal/deletion path for the new data?

3. **Security review:**
   - Are there any injection vulnerabilities (SQL, shell, command)?
   - Are credentials, tokens, or API keys handled securely (no hardcoding, no logging)?
   - Is input validated at system boundaries?
   - Are there any new external dependencies with known vulnerabilities?
   - Are authentication and authorisation enforced on all new endpoints?
   - Could the change be used to bypass an existing permission check?

4. **Clinical safety review:**
   - Does any output from this change risk being interpreted as a clinical diagnosis?
   - Are result limitations and uncertainty communicated to the end user?
   - Does any model output bypass the required human review gate?
   - Does the change touch the model release path? If so, is human approval still required?
   - Are there any new label fields that could be mistaken for validated clinical results?

5. **Produce the assessment.** Write findings in three sections: Privacy, Security, Clinical Safety. For each finding: severity (BLOCKING / WARNING / INFO), description, affected file/line, and recommendation.

## Severity Definitions

- **BLOCKING** — Must be resolved before merging. Examples: consent bypass, hardcoded secret, clinical label presented as diagnosis, direct identifier in feature store.
- **WARNING** — Should be resolved before merging. Examples: missing input validation, unencrypted local storage, missing limitations statement.
- **INFO** — Worth tracking but not blocking. Examples: minor logging verbosity, suggested hardening.

## Expected Outputs

- Safety assessment report (console output or saved to `docs/privacy/reviews/`).
- List of all BLOCKING and WARNING findings with file references.
- Explicit statement of what was NOT reviewed (to set expectations honestly).

## Acceptance Criteria

- [ ] All three review dimensions covered.
- [ ] Every BLOCKING finding has a specific file/line reference.
- [ ] Out-of-scope items are listed.
- [ ] Report does not fabricate issues — only report what is actually observed in the code.
