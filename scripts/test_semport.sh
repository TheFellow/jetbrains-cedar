#!/usr/bin/env bash
# Ledger integrity: unit tests, evidence verification, and (with --base REV) append-only history.
set -euo pipefail
cd "$(dirname "$0")/.."
export PYTHONDONTWRITEBYTECODE=1

mise exec -- python3 -m unittest discover -s semport -p 'test_*.py' -v
mise exec -- python3 semport/ledger.py verify --repository .
mise exec -- python3 scripts/semport_guard.py "$@"
