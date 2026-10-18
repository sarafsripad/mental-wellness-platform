"""CLI: python -m research_pipeline ingest|summary --dropzone DIR --store DIR"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .ingest import ExportRejected, ingest, newest_manifest
from .summary import summarise


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="research_pipeline")
    p.add_argument("command", choices=["ingest", "summary"])
    p.add_argument("--dropzone", type=Path, help="directory the application exports to")
    p.add_argument("--store", type=Path, required=True, help="local research store directory")
    a = p.parse_args(argv)
    if a.command == "ingest":
        manifest = newest_manifest(a.dropzone)
        if manifest is None:
            print("no exports found")
            return 0
        try:
            result = ingest(manifest, a.store)
        except ExportRejected as ex:
            print(f"export rejected: {ex}", file=sys.stderr)
            return 2
        print(json.dumps(result.__dict__))
        return 0
    print(json.dumps(summarise(a.store), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
