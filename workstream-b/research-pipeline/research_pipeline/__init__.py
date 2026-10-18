"""Workstream B research pipeline: ingests consent-filtered research exports (slice S8).

Reads only what the application publishes to the drop zone (ADR-001, ADR-010): re-keyed feature
records and a manifest. Never receives audio, identities or application IDs, and has no route back
into the application. No model training happens here; the summary is descriptive and non-clinical.
"""

__version__ = "0.1.0"
