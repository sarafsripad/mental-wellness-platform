---
name: springboot-microservices
description: Build and maintain Spring Boot microservices for the Workstream A backend — consent service, session ingestion, feature routing, and clinician review API.
---

# Spring Boot Microservices Skill

## Purpose

Implement and evolve the backend microservices for Workstream A. Services include consent management, audio session ingestion, feature-routing gateway, and the clinician review API.

## Prerequisites

- Read existing service source in `workstream-a/services/` before adding or modifying.
- Understand the contracts in `contracts/` — APIs must conform to defined schemas.
- All endpoints that accept or return participant data must enforce consent validation.

## Workflow

1. **Identify the module boundary.** Each module owns one domain (consent, voice-session, processing, research-export, audit, …; see `docs/architecture/solution-architecture.md`). Do not reach into another module's tables.
2. **Define the API.** The contract is `contracts/openapi/wellness-api.v1.yaml` (or the internal worker API). Update it and pass `scripts/validate-examples.py` before implementing.
3. **Implement the consent gate.** Any endpoint that creates or reads participant data must validate the `consent_event_id` against the consent service before proceeding.
4. **Implement the data layer.** Use Spring Data JPA for relational data. Use Flyway for schema migrations — never modify the database outside of a migration script.
5. **Implement error handling.** Return structured error responses. Never leak internal exceptions, stack traces, or PII in error messages.
6. **Write integration tests.** Use Testcontainers to spin up a real database. Do not mock the database for integration tests.
7. **Document configuration.** All environment-specific configuration must use Spring Boot externalized configuration. No hard-coded secrets, hostnames, or passwords.

## Constraints

- Per ADR-004 the backend is a modular monolith (`wellness-api`). Modules must not share schemas or read each other's tables; they interact only through published module APIs or in-process events (enforced by Spring Modulith/ArchUnit tests). Identity data lives in a separate database (`identity_db`).
- Database migrations are forward-only. No destructive Flyway scripts without explicit human approval.
- Services must emit structured logs (JSON) without including participant data in log fields.
- The research-export module must not pass direct identifiers to Workstream B — only re-keyed pseudonymous IDs (ADR-010).
- Clinician review API requires role-based access control. Document roles in the OpenAPI spec.

## Expected Outputs

- Service source files in `workstream-a/services/<service-name>/`.
- OpenAPI 3.x spec.
- Flyway migration scripts.
- Testcontainers-based integration tests.

## Acceptance Criteria

- [ ] Consent gate implemented and tested on all participant-data endpoints.
- [ ] OpenAPI spec present and matches implementation.
- [ ] Flyway migrations run cleanly from a blank schema.
- [ ] No PII in logs — verified by searching log output in tests.
- [ ] Integration tests pass against a real (Testcontainers) database.
- [ ] No hard-coded credentials in source.
