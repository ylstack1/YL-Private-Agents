#!/usr/bin/env python3
"""Rebrand this fork's replaceable tokens.

Single source of truth for the token table. ``rebrand.ps1`` and
``rebrand.sh`` are thin launchers around this file, so the two entry
points AGENTS.md names can never drift apart.

Token order matters:

1. Theme first. ``Theme.AndroidHarness`` contains ``AndroidHarness``, so a
   display-name pass that runs first would corrupt the style name into
   ``Theme.YL Private Agents`` (spaces, and no longer a valid style).
2. Full package, then its root, so ``com.androidharness.app`` becomes
   ``com.ylprivate.agents`` while ``com.androidharness.gpu`` still becomes
   ``com.ylprivate.gpu``.
3. Slash and underscore forms of the same package, because the native code
   encodes it in JNI symbols (``Java_com_androidharness_app_...``) and in
   ``FindClass("com/androidharness/...")``. Miss these and local models and
   ADB pairing fail at runtime with UnsatisfiedLinkError.
4. Scheme, matched only in the two shapes the app actually uses, so the
   ``androidharness-environment`` skill id and the
   ``/data/local/tmp/androidharness`` runtime path survive untouched.
5. Display name last, with an identifier-safe pass (``AndroidHarnessFoo``
   keeps no space) and the upstream repo slug ``Sanuu7/AndroidHarness``
   protected: the update checker validates that exact path prefix.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
from pathlib import Path

TEXT_SUFFIXES = {
    ".kt", ".java", ".xml", ".kts", ".gradle", ".pro", ".json",
    ".md", ".cpp", ".h", ".hpp", ".c", ".cmake", ".aidl",
}

SKIP_DIRS = {
    ".git", ".gradle", ".idea", ".kotlin", ".cxx", "build", "node_modules",
    # Upstream documentation and CI are kept as-is; they describe the
    # upstream project and are rebranded by hand if ever needed.
    "docs", "repowiki", ".github", "scripts",
}

SKIP_FILES = {
    "README.md", "CHANGELOG.md", "FEATURES.md", "LICENSE", "CONTRIBUTING.md",
    "SECURITY.md", "PRIVACY.md", "CODE_OF_CONDUCT.md", "AGENTS.md",
    ".gitignore", "local.properties",
}

# Upstream repo slug: the update checker matches this path prefix, so it must
# survive every pass.
UPSTREAM_SLUG = "Sanuu7/AndroidHarness"


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Rebrand replaceable tokens in this fork.")
    p.add_argument("--old-package", default="com.androidharness.app")
    p.add_argument("--new-package", default="com.ylprivate.agents")
    p.add_argument("--old-name", default="AndroidHarness")
    p.add_argument("--new-name", default="YL Private Agents")
    p.add_argument("--old-theme", default="Theme.AndroidHarness")
    p.add_argument("--new-theme", default="Theme.YLPrivateAgents")
    p.add_argument("--old-scheme", default="androidharness")
    p.add_argument("--new-scheme", default="ylprivateagents")
    p.add_argument("--root", default=".")
    p.add_argument("--dry-run", action="store_true")
    return p.parse_args(argv)


def build_replacements(args: argparse.Namespace) -> list[tuple[str, re.Pattern[str], str]]:
    """Ordered (label, pattern, replacement) passes."""
    old_pkg, new_pkg = args.old_package, args.new_package
    old_root = old_pkg.rsplit(".", 1)[0]
    new_root = new_pkg.rsplit(".", 1)[0]
    has_root = "." in old_pkg

    passes: list[tuple[str, re.Pattern[str], str]] = [
        ("theme", re.compile(re.escape(args.old_theme)), args.new_theme),
        ("package", re.compile(re.escape(old_pkg)), new_pkg),
    ]
    if has_root:
        passes += [
            ("package-root", re.compile(re.escape(old_root)), new_root),
            ("package-path", re.compile(re.escape(old_pkg.replace(".", "/"))), new_pkg.replace(".", "/")),
            ("package-symbol", re.compile(re.escape(old_pkg.replace(".", "_"))), new_pkg.replace(".", "_")),
            ("package-root-path", re.compile(re.escape(old_root.replace(".", "/"))), new_root.replace(".", "/")),
            ("package-root-symbol", re.compile(re.escape(old_root.replace(".", "_"))), new_root.replace(".", "_")),
        ]

    old_scheme, new_scheme = args.old_scheme, args.new_scheme
    passes += [
        # Only the two shapes the app uses: the MCP redirect URI and the
        # manifest intent filter. A blanket scheme pass would rename the
        # androidharness-environment skill and the /data/local/tmp path.
        ("scheme-uri", re.compile(re.escape(f"{old_scheme}://")), f"{new_scheme}://"),
        ("scheme-attr", re.compile(re.escape(f'android:scheme="{old_scheme}"')), f'android:scheme="{new_scheme}"'),
        # Runtime intent checks compare the parsed scheme to a bare literal.
        ("scheme-compare", re.compile(r"(\.scheme\s*[!=]=\s*)\"" + re.escape(old_scheme) + r"\""), rf'\1"{new_scheme}"'),
        # URL-encoded redirect_uri (%3A%2F%2F is ://), as asserted in tests.
        ("scheme-encoded", re.compile(re.escape(f"{old_scheme}%3A%2F%2F")), f"{new_scheme}%3A%2F%2F"),
        # Identifier-safe: AndroidHarnessCompatTest -> YLPrivateAgentsCompatTest.
        ("name-ident", re.compile(r"(?<!Sanuu7/)" + re.escape(args.old_name) + r"(?=[A-Za-z0-9_])"), args.new_name.replace(" ", "")),
        # HTTP product token: a User-Agent cannot contain a space.
        ("name-token", re.compile(r"(?<!Sanuu7/)" + re.escape(args.old_name) + r"(?=/)"), args.new_name.replace(" ", "")),
        ("name", re.compile(r"(?<!Sanuu7/)" + re.escape(args.old_name)), args.new_name),
    ]
    return passes


def read_text(path: Path) -> tuple[str, bool]:
    raw = path.read_bytes()
    bom = raw.startswith(b"\xef\xbb\xbf")
    text = raw.decode("utf-8-sig")
    return text, bom


def write_text(path: Path, text: str, bom: bool) -> None:
    data = text.encode("utf-8")
    if bom:
        data = b"\xef\xbb\xbf" + data
    path.write_bytes(data)


def iter_files(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name in SKIP_FILES:
                continue
            path = Path(dirpath) / name
            if path.suffix.lower() in TEXT_SUFFIXES:
                yield path


def move_package_dirs(root: Path, args: argparse.Namespace, dry_run: bool) -> list[tuple[str, str]]:
    old_pkg_path = args.old_package.replace(".", "/")
    new_pkg_path = args.new_package.replace(".", "/")
    old_root_path = old_pkg_path.rsplit("/", 1)[0]
    new_root_path = new_pkg_path.rsplit("/", 1)[0]
    moved: list[tuple[str, str]] = []

    roots = []
    for dirpath, dirnames, _ in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        # java/ and aidl/ both mirror the package path; AIDL declares its own
        # package and generates classes under it, so it has to move too.
        if Path(dirpath).name in {"java", "aidl"} and Path(dirpath).parent.parent.name == "src":
            roots.append(Path(dirpath))

    for java_root in roots:
        # Specific package first (com/androidharness/app -> com/ylprivate/agents).
        src = java_root / old_pkg_path
        if src.is_dir():
            dst = java_root / new_pkg_path
            moved.append((str(src), str(dst)))
            if not dry_run:
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(src), str(dst))
        # Then any remaining sibling under the old root (com/androidharness/gpu).
        src_root = java_root / old_root_path
        if src_root.is_dir():
            dst_root = java_root / new_root_path
            if dst_root.exists() and not dry_run:
                # Merge remaining children into the new root.
                for child in src_root.iterdir():
                    target = dst_root / child.name
                    if target.exists():
                        raise SystemExit(f"refusing to merge, target exists: {target}")
                    shutil.move(str(child), str(target))
                src_root.rmdir()
                moved.append((str(src_root), str(dst_root)))
            else:
                moved.append((str(src_root), str(dst_root)))
                if not dry_run:
                    dst_root.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(src_root), str(dst_root))
    return moved


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    root = Path(args.root).resolve()
    passes = build_replacements(args)
    counts = {label: 0 for label, _, _ in passes}
    changed: list[Path] = []

    for path in iter_files(root):
        try:
            text, bom = read_text(path)
        except UnicodeDecodeError:
            continue
        original = text
        for label, pattern, replacement in passes:
            text, n = pattern.subn(replacement, text)
            counts[label] += n
        if text != original:
            changed.append(path)
            if not args.dry_run:
                write_text(path, text, bom)

    moved = move_package_dirs(root, args, args.dry_run)

    verb = "would update" if args.dry_run else "updated"
    print(f"==> {verb} {len(changed)} files under {root}")
    for label, n in counts.items():
        if n:
            print(f"    {label:20s} {n}")
    verb = "would move" if args.dry_run else "moved"
    for src, dst in moved:
        print(f"    {verb} {Path(src).relative_to(root)} -> {Path(dst).relative_to(root)}")

    # Leftovers that indicate a missed token.
    leftovers = []
    old_pkg_re = re.compile(re.escape(args.old_package.rsplit(".", 1)[0]))
    for path in iter_files(root):
        try:
            text, _ = read_text(path)
        except UnicodeDecodeError:
            continue
        for label, pattern in (("package", old_pkg_re), ("theme", re.compile(re.escape(args.old_theme)))):
            for m in pattern.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                leftovers.append(f"{path.relative_to(root)}:{line}: {label}: {m.group(0)}")
    if leftovers:
        print("==> leftover tokens (review):")
        for item in leftovers[:40]:
            print(f"    {item}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
