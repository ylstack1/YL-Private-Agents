# YL-Private-Agents — Agent Rulebook

This repo is a **fork of `Sanuu7/AndroidHarness`** (Release 4.0, commit `c2f12baf`) rebranded as **YL-Private-Agents**.

This `AGENTS.md` is the source of truth for any AI agent working in this workspace. Read it fully before making changes. It exists so that:

1. Your custom brand and features survive upstream syncs.
2. Upstream features can be ported in without a hard rewrite every release.
3. No brand name, package ID, or app name is hardcoded — everything is replaceable by token.

---

## 1. Project identity

| Field | Value (this fork) | Upstream value |
|-------|-------------------|----------------|
| Git repo | `ylstack1/YL-Private-Agents` | `Sanuu7/AndroidHarness` |
| Remote `origin` | `https://github.com/ylstack1/YL-Private-Agents.git` | — |
| Remote `upstream` | `https://github.com/Sanuu7/AndroidHarness.git` (read-only) | — |
| Package / namespace | `com.androidharness.app` ← **replaceable token** | `com.androidharness.app` |
| Application ID | `com.androidharness.app` ← **replaceable token** | `com.androidharness.app` |
| App name (string) | `AndroidHarness` ← **replaceable token** | `AndroidHarness` |
| Theme | `Theme.AndroidHarness` ← **replaceable token** | `Theme.AndroidHarness` |
| MCP scheme | `androidharness` ← **replaceable token** | `androidharness` |

> **Rule:** Treat `com.androidharness.app`, `AndroidHarness`, `Theme.AndroidHarness`, and `androidharness` as **replaceable tokens**, not constants. When you rebrand, replace the token everywhere via the rebrand script (see §4), not by editing each file by hand.

---

## 2. Golden rules (never break these)

1. **Never `git merge` or `git pull upstream` directly into your working branch.**
   Upstream uses package `com.androidharness.app`; this fork uses its own rebranded package. A direct merge creates massive conflicts and can clobber your brand.

2. **Commit your baseline before porting anything.**
   `git add -A && git commit -m "chore: baseline <name>"` gives you a restore point if a port breaks the build.

3. **Cherry-pick, don't wholesale-copy.**
   When upstream releases a feature you want, `git fetch upstream` and `git cherry-pick -n <commit>` into a feature branch, then adapt package/imports manually. One feature = one commit.

4. **No hardcoded brand or package names in code.**
   If you introduce a new string, resource, or class that mentions `AndroidHarness`, `com.androidharness.app`, or `Theme.AndroidHarness`, you are doing it wrong. Use the rebrand tokens (§4) and string resources.

5. **Protect these files from upstream overwrites:**
   - `app/src/main/res/` (logo, icon, colors, themes, mipmap)
   - `app/src/main/AndroidManifest.xml` (package, label, scheme)
   - `app/build.gradle.kts` (namespace, applicationId)
   - `settings.gradle.kts` (rootProject.name)
   - `strings.xml` (app_name)
   - `AGENTS.md` (this file)

6. **One feature per commit.**
   Do not bundle "port upstream Release 4.1" + "rebrand" + "my new feature" in a single commit. Split them so any single change can be reverted.

7. **Tag every release.**
   `yl-private-agents-v1.0`, `yl-private-agents-v1.1-upstream-port`, etc.

---

## 3. Directory layout (source of truth)

```
YL-Private-Agents/
├── AGENTS.md              ← this rulebook (update when rules change)
├── README.md              ← user-facing; mentions AGENTS.md
├── build.gradle.kts       ← root (do not hardcode brand)
├── settings.gradle.kts    ← rootProject.name = replaceable token
├── app/
│   ├── build.gradle.kts   ← namespace + applicationId = replaceable tokens
│   └── src/main/
│       ├── AndroidManifest.xml ← package/label/scheme = replaceable tokens
│       ├── res/
│       │   ├── values/strings.xml   ← app_name = replaceable token
│       │   ├── values/themes.xml    ← Theme.<Brand> = replaceable token
│       │   ├── mipmap-*/ic_launcher* ← brand icon (replace via script)
│       │   └── drawable/*           ← brand art (replace via script)
│       └── java/com/androidharness/app/   ← package = replaceable token
├── scripts/
│   ├── rebrand.sh        ← Linux/macOS/Git Bash rebrand script
│   └── rebrand.ps1       ← Windows PowerShell rebrand script
└── .github/
    └── assets/
        └── icon.svg      ← brand logo source (replace file, keep path)
```

---

## 4. Rebranding procedure (replaceable-token method)

Do **not** rename files one by one. Use the rebrand script so the operation is repeatable and auditable.

### What the script replaces (tokens)

| Token | Replacement target | Example |
|-------|-------------------|---------|
| `com.androidharness.app` | Kotlin/Java package + `namespace` + `applicationId` | `com.ylprivate.agents` |
| `AndroidHarness` | `rootProject.name`, `android:label`, `app_name` string | `YL Private Agents` |
| `Theme.AndroidHarness` | Theme style name in `themes.xml` + manifest `android:theme` | `Theme.YLPrivateAgents` |
| `androidharness` | MCP OAuth `android:scheme` | `ylprivateagents` |
| `icon.svg` / `ic_launcher*` | Brand artwork files | replace `.github/assets/icon.svg` + mipmap pngs |

### Run the rebrand (one-time, after clone)

**PowerShell (Windows):**
```powershell
# From repo root
.\scripts\rebrand.ps1 -OldPackage com.androidharness.app -NewPackage com.ylprivate.agents -OldName AndroidHarness -NewName "YL Private Agents" -OldTheme Theme.AndroidHarness -NewTheme Theme.YLPrivateAgents -OldScheme androidharness -NewScheme ylprivateagents
```

**Git Bash / Linux / macOS:**
```bash
./scripts/rebrand.sh --old-package com.androidharness.app --new-package com.ylprivate.agents --old-name AndroidHarness --new-name "YL Private Agents" --old-theme Theme.AndroidHarness --new-theme Theme.YLPrivateAgents --old-scheme androidharness --new-scheme ylprivateagents
```

### After rebranding
1. `git add -A && git commit -m "chore: rebrand to YL Private Agents (replaceable tokens)"`
2. `./gradlew assembleDebug` — verify it builds
3. Tag: `git tag yl-private-agents-v1.0-branded && git push origin --tags`

### Rebrand script contract

The token table and replacement order live in **`scripts/rebrand.py`**.
`rebrand.sh` and `rebrand.ps1` are thin launchers around it, so adding a new
replaceable token means editing one file, not two.

The script must:
- Replace tokens **only** in: `*.kt`, `*.java`, `*.xml`, `*.kts`, `*.gradle.kts`, `AndroidManifest.xml`, `strings.xml`, `themes.yml`, `*.svg`, `*.png` (mipmap by path replacement).
- **Skip** `.git/`, `build/`, `.gradle/`, `CHANGELOG.md`, `FEATURES.md`, `LICENSE`, `README.md` (unless you want the README rebranded too — do that manually).
- Update `app/src/main/java/com/androidharness/app/` directory path to match the new package (move folders).
- Update all `import com.androidharness.app...` → `import com.ylprivate.agents...`.
- Update `namespace = "..."` and `applicationId = "..."` in `app/build.gradle.kts`.
- Update `package="..."` in `AndroidManifest.xml`.
- Update `android:label="@string/app_name"` and `app_name` value.
- Update `android:theme="@style/Theme.AndroidHarness"`.
- Update MCP `<data android:scheme="androidharness" ... />`.
- Rename `rootProject.name = "AndroidHarness"` in `settings.gradle.kts`.

Keep the scripts under `scripts/` and version them. If you add a new replaceable token later, add it to both `rebrand.sh` and `rebrand.ps1`.

---

## 5. Upstream sync workflow (port features safely)

Upstream `Sanuu7/AndroidHarness` moves fast. To bring in a fix or feature without breaking your brand:

```powershell
# 1. Work on a clean feature branch off your branded main
git checkout yl-private-agents
git pull origin yl-private-agents
git checkout -b feat/port-upstream-<short-description>

# 2. Fetch upstream (read-only remote)
git remote add upstream https://github.com/Sanuu7/AndroidHarness.git   # run once
git fetch upstream

# 3. Review what changed (do not merge)
git log upstream/main --oneline --since="2026-01-01"
git show <upstream-commit> --stat

# 4. Cherry-pick the single commit you want
git cherry-pick -n <upstream-commit-hash>

# 5. Resolve conflicts manually
#    - Adapt package: com.androidharness.app → your new package
#    - Adapt imports
#    - Do NOT let upstream overwrite res/, AndroidManifest.xml, build.gradle.kts
git add <fixed-files>
git cherry-pick --continue   # or: git commit

# 6. Build & test
./gradlew assembleDebug

# 7. Commit & tag
git commit -m "feat(ui): port <description> from upstream <commit>"
git tag yl-private-agents-v1.1-port-<name>
git push origin yl-private-agents --tags
```

**If a cherry-pick fails badly:** `git cherry-pick --abort`, return to baseline, and port the feature by hand-copying logic instead of the commit.

---

## 6. Adding a new feature (your own code)

1. Create a branch: `git checkout -b feat/<your-feature-name>`
2. Write code under `app/src/main/java/com/<your-package>/...` (use your rebranded package, never `com.androidharness.app`).
3. Register/wire the feature in **one place**:
   - Preferences/settings → one `SettingsSection` composable added to `SettingsHome`
   - Tools → one entry in the tool registry
   - UI screens → one route in `AppNav` / navigation graph
4. Do **not** scatter the feature across `AndroidManifest.xml`, `strings.xml`, and `build.gradle.kts` unless it truly needs a permission or dependency.
5. Add string resources to `res/values/strings.xml` (never hardcode UI text in Kotlin).
6. Build: `./gradlew assembleDebug`
7. Commit: `git commit -m "feat(<area>): <description>"`
8. Tag if user-facing: `git tag yl-private-agents-v1.2-<feature>`

---

## 7. Protected-files merge driver (optional safety net)

To prevent an accidental merge from clobbering your brand files, create `.gitattributes`:

```text
app/src/main/res/ merge=ours
app/src/main/AndroidManifest.xml merge=ours
app/build.gradle.kts merge=ours
settings.gradle.kts merge=ours
app/src/main/res/values/strings.xml merge=ours
AGENTS.md merge=ours
```

Then configure (one-time):
```powershell
git config merge.ours.driver "true"
```

With this, even `git merge upstream/main` would keep **your** version of those files. You should still cherry-pick instead of merging, but this is a belt-and-suspenders guard.

---

## 8. Commit message convention

```
<type>(<scope>): <short summary>

<body (optional)>

Footer: references (optional)
```

Types: `feat`, `fix`, `chore`, `refactor`, `docs`, `rebrand`, `port`.

Examples:
- `feat(settings): add remote runtime section`
- `port(ui): bring MotionBlur from upstream c2f12baf`
- `chore(rebrand): rename package to com.ylprivate.agents`
- `fix(build): resolve NDK path on Windows`

Scope examples: `ui`, `settings`, `tools`, `workspace`, `build`, `brand`, `upstream`.

---

## 9. What to skip when porting from upstream

| Upstream item | Action | Reason |
|---------------|--------|--------|
| `DonationFundingNotice.kt` / buy-me-a-coffee links | **Skip** | This fork de-commercialized; do not restore donation UI |
| `com.androidharness.app` package name | **Never copy as-is** | Must be token-replaced to your package |
| Official app icon / `ic_launcher` | **Skip** | Keeps your YL Private Agents branding |
| `build.gradle.kts` versionCode/versionName changes | **Review manually** | Don't let upstream change your `applicationId` or versioning |
| `Theme.AndroidHarness` references | **Token-replace** | Use `Theme.<YourBrand>` |

---

## 10. Quick checklist before any PR or push

- [ ] `./gradlew assembleDebug` succeeds
- [ ] No `com.androidharness.app` remains in `app/src/main/java/` (unless you intentionally kept upstream package)
- [ ] App icon/logo reflects YL Private Agents, not AndroidHarness
- [ ] `strings.xml` `app_name` is your brand name
- [ ] Your new feature is wired in exactly one registration point
- [ ] Each commit touches one logical change only
- [ ] You are on a feature branch, not directly on `yl-private-agents` (unless it's a hotfix)

---

## 11. Where to find upstream reference

- Upstream repo: `https://github.com/Sanuu7/AndroidHarness`
- Upstream Release 4.0 tag: `c2f12baf` (this clone's starting point)
- Upstream changelog: `CHANGELOG.md` (in this repo at clone time)
- Upstream features: `FEATURES.md` (in this repo at clone time)

Use these as **read-only reference**. Copy logic, adapt tokens, never merge blindly.
