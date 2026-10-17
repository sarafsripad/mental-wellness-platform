"""Behaviour tests for the hook scripts, run in throwaway git repositories.

Fake secrets are assembled at runtime so this file never contains a string the
secret scanner would block.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from conftest import ROOT

FAKE_AWS_KEY = "AKIA" + "ABCDEFGHIJKLMNOP"


# Put the interpreter running the tests first on PATH, so scripts that call
# `python3` get the same dependencies (jsonschema, PyYAML, ...).
BASE_ENV = {**os.environ,
            "PATH": str(Path(sys.executable).parent) + os.pathsep + os.environ.get("PATH", "")}


def _run(cmd, cwd, env=None):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, env=env or BASE_ENV)


@pytest.fixture()
def repo(tmp_path):
    """A git repo containing copies of scripts/ and contracts/, with one commit."""
    shutil.copytree(ROOT / "scripts", tmp_path / "scripts",
                    ignore=shutil.ignore_patterns("__pycache__"))
    shutil.copytree(ROOT / "contracts", tmp_path / "contracts")
    (tmp_path / "README.md").write_text("synthetic test repo\n")
    for cmd in (["git", "init", "-q"],
                ["git", "config", "user.email", "test@example.invalid"],
                ["git", "config", "user.name", "test"],
                ["git", "add", "-A"],
                ["git", "commit", "-q", "-m", "base"]):
        assert _run(cmd, tmp_path).returncode == 0
    return tmp_path


def _secrets(repo, **env):
    return _run(["bash", "scripts/check-secrets.sh"], repo, env={**BASE_ENV, **env})


def test_clean_repo_passes(repo):
    assert _secrets(repo).returncode == 0


def test_secret_in_untracked_file_blocks(repo):
    (repo / "notes.txt").write_text(f'key = "{FAKE_AWS_KEY}"\n')
    result = _secrets(repo)
    assert result.returncode == 2
    assert "AWS Access Key" in result.stderr


def test_secret_in_tracked_change_blocks(repo):
    with open(repo / "README.md", "a") as fh:
        fh.write(f"{FAKE_AWS_KEY}\n")
    assert _secrets(repo).returncode == 2


def test_secret_late_in_large_diff_is_found(repo):
    """Regression: piping into grep -q under pipefail used to drop matches."""
    big = "".join(f"line {i} with ordinary content\n" for i in range(50000))
    (repo / "big.txt").write_text(big + f"{FAKE_AWS_KEY}\n")
    assert _secrets(repo).returncode == 2


def test_committed_secret_found_with_scan_base(repo):
    base = _run(["git", "rev-parse", "HEAD"], repo).stdout.strip()
    (repo / "config.txt").write_text(f"{FAKE_AWS_KEY}\n")
    _run(["git", "add", "config.txt"], repo)
    _run(["git", "commit", "-q", "-m", "add config"], repo)
    assert _secrets(repo).returncode == 0          # clean working tree: nothing to scan
    assert _secrets(repo, SCAN_BASE=base).returncode == 2


def test_unknown_scan_base_fails_closed(repo):
    assert _secrets(repo, SCAN_BASE="0" * 40).returncode == 2


def test_warning_patterns_do_not_block(repo):
    (repo / "notes.txt").write_text("pass" + 'word = "synthetic-test-value"\n')
    result = _secrets(repo)
    assert result.returncode == 0
    assert "WARN" in result.stdout


def test_contract_validation_passes_on_repo_copy(repo):
    result = _run(["bash", "scripts/validate-contracts.sh"], repo)
    assert result.returncode == 0, result.stdout + result.stderr


def test_contract_validation_blocks_invalid_json(repo):
    (repo / "contracts" / "broken.json").write_text("{not json")
    result = _run(["bash", "scripts/validate-contracts.sh"], repo)
    assert result.returncode == 2
    assert "broken.json" in result.stderr


def test_contract_validation_blocks_invalid_example(repo):
    example = repo / "contracts" / "voice-features" / "examples" / "valid" / "complete-pass.json"
    example.write_text(example.read_text().replace('"status": "PASS"', '"status": "FAIL"'))
    result = _run(["bash", "scripts/validate-contracts.sh"], repo)
    assert result.returncode == 2
