#!/usr/bin/env pwsh
# rebrand.ps1 — replaceable-token rebrand for YL-Private-Agents (Windows entry point).
#
# Thin launcher: the token table and replacement order live in rebrand.py, so
# this file and rebrand.sh can never drift apart. See AGENTS.md §4.
#
# Usage (from repo root, PowerShell):
#   .\scripts\rebrand.ps1 `
#     -OldPackage com.androidharness.app `
#     -NewPackage com.ylprivate.agents `
#     -OldName AndroidHarness `
#     -NewName "YL Private Agents" `
#     -OldTheme Theme.AndroidHarness `
#     -NewTheme Theme.YLPrivateAgents `
#     -OldScheme androidharness `
#     -NewScheme ylprivateagents
#
# Add -DryRun to report what would change without writing.

param(
    [string]$OldPackage = "com.androidharness.app",
    [string]$NewPackage = "com.ylprivate.agents",
    [string]$OldName = "AndroidHarness",
    [string]$NewName = "YL Private Agents",
    [string]$OldTheme = "Theme.AndroidHarness",
    [string]$NewTheme = "Theme.YLPrivateAgents",
    [string]$OldScheme = "androidharness",
    [string]$NewScheme = "ylprivateagents",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$script = Join-Path $PSScriptRoot "rebrand.py"

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    throw "python is required to run rebrand.py (install Python 3 or run rebrand.sh with python3)."
}

$argv = @(
    $script,
    "--root", $root,
    "--old-package", $OldPackage,
    "--new-package", $NewPackage,
    "--old-name", $OldName,
    "--new-name", $NewName,
    "--old-theme", $OldTheme,
    "--new-theme", $NewTheme,
    "--old-scheme", $OldScheme,
    "--new-scheme", $NewScheme
)
if ($DryRun) { $argv += "--dry-run" }

& $python.Source @argv
exit $LASTEXITCODE
