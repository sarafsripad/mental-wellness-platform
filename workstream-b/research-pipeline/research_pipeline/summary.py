"""Descriptive, non-clinical summary of the research store.

Counts only: no feature values, no model output, no clinical interpretation. Synthetic or test
data can validate software but says nothing about clinical performance
(docs/research/model-validation-plan.md, ground rule 2).
"""
from __future__ import annotations

import json
import sqlite3
from collections import Counter
from pathlib import Path


def summarise(store_dir: Path) -> dict:
    db = sqlite3.connect(str(store_dir / "research-store.sqlite"))
    try:
        rows = db.execute("SELECT split_group, training_permitted, record_json FROM record").fetchall()
        exports = db.execute("SELECT count(*), max(export_id) FROM export").fetchone()
    finally:
        db.close()
    quality, languages, origins, completeness = Counter(), Counter(), Counter(), Counter()
    for _, _, raw in rows:
        r = json.loads(raw)
        quality[r["quality"]["status"]] += 1
        languages[r["language_tag"]] += 1
        origins[r["provenance"]["data_origin"]] += 1
        completeness["valid_features"] += sum(f["status"] == "VALID" for f in r["features"])
        completeness["all_features"] += len(r["features"])
    return {
        "exports_ingested": exports[0],
        "latest_export_id": exports[1],
        "records": len(rows),
        "split_groups": len({g for g, _, _ in rows}),
        "training_permitted_records": sum(t for _, t, _ in rows),
        "quality_status": dict(sorted(quality.items())),
        "language_tag": dict(sorted(languages.items())),
        "data_origin": dict(sorted(origins.items())),
        "feature_completeness": round(completeness["valid_features"] / completeness["all_features"], 4)
        if completeness["all_features"] else None,
        "note": "Descriptive counts for software validation only. Not evidence of clinical performance.",
    }
