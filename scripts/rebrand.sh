#!/usr/bin/env bash
# rebrand.sh — replaceable-token rebrand for YL-Private-Agents (Linux/macOS/Git Bash entry point).
#
# Thin launcher: the token table and replacement order live in rebrand.py, so
# this file and rebrand.ps1 can never drift apart. See AGENTS.md §4.
#
# Usage (from repo root):
#   ./scripts/rebrand.sh \
#     --old-package com.androidharness.app \
#     --new-package com.ylprivate.agents \
#     --old-name AndroidHarness \
#     --new-name "YL Private Agents" \
#     --old-theme Theme.AndroidHarness \
#     --new-theme Theme.YLPrivateAgents \
#     --old-scheme androidharness \
#     --new-scheme ylprivateagents
#
# Add --dry-run to report what would change without writing.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$script_dir/.." && pwd)"

if command -v python3 >/dev/null 2>&1; then
    python=python3
elif command -v python >/dev/null 2>&1; then
    python=python
else
    echo "python3 is required to run rebrand.py" >&2
    exit 1
fi

exec "$python" "$script_dir/rebrand.py" --root "$root" "$@"
