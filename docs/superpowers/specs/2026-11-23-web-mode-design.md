# Design: web mode (desktop browser access)

**Status:** draft — awaiting approval  
**Author:** agent  
**Date:** 2026-11-23  
**Fork:** YL-Private-Agents (`rebrand/yl-private-agents`)  
**Reference:** YL-COS `E:\ylstack-space\Developments\ai\agents_app\YL-COS`

---

## 1. Intent and why

The user wants to access the app's chat/agent from a desktop browser (YL-COS's "Web Access" feature): start a server on the phone, open `http://<phone-ip>:<port>` on a computer, authenticate with an access code shown in the app settings, and drive chat, composer, model/reasoning and tool approvals from the browser.

YL-COS already builds this end-to-end:
- a hand-rolled HTTP/1.1 server (`ServerSocket`) running in a foreground service,
- a JSON snapshot endpoint + SSE state stream,
- action endpoints (`send`, `stop`, `continue`, `model`, `tool`, `approval`, `question`, …),
- a single-page app that renders the snapshot and posts actions back,
- live theme mirroring from the phone's Compose `ColorScheme`.

This fork is being rebranded to `com.ylprivate.agents`; the web mode should inherit that identity and use **our** theme (`Color.kt`, `PaletteColors.kt`, `PersonalColors.kt`), not YL-COS's grey/blue palette.

License note, confirmed with the user: YL-COS is AGPL-3.0, this fork is MIT, and the user has opted to copy YL-COS's web-access code/assets anyway for **private use only**. If this ever ships publicly, the fork must be relicensed AGPL-3.0.

---

## 2. Goals, non-goals, anti-goals

### Goals (first version)
- Run an HTTP server on the phone reachable from the desktop browser over Wi-Fi (`0.0.0.0`) or localhost-only (`127.0.0.1`), port configurable (default 8765).
- Single access code per run, shown in app settings, copyable/resettable.
- Browser UI: chat transcript, composer (send/stop/continue), model + reasoning pickers, auto-approval toggle, workspace picker, file upload, settings dialog (prompts/subagents/compression/tools/MCP).
- State streaming: full snapshot on connect, diff stream while running, heartbeat every 10 s.
- Phone and browser stay in sync via `sessionRevision` + `conversationId` + `projectId` conflict checks (409 if switched).
- Theme follows the phone's current Compose colour scheme (dark/light + personal colours).
- Portrait and desktop-width breakpoints (sidebar 292 px → collapsible drawer on narrow screens).

### Non-goals (first version)
- Remote runtime module / desktop tray server (YL-COS Phase 0–3; `desktop/` module is a separate JVM app).
- WebRTC, WebSocket, PRoot file preview, SVG/chart rendering in the browser beyond what the HTML already vendors.
- Multi-user, HTTPS, authentication beyond the bearer token, or LAN discovery.
- Chat history search, attachments beyond file upload, voice input.

### Anti-goals
- A second agent or database session in the browser (manager comment in YL-COS: "It never creates a second agent or database session").
- Any change to `AndroidManifest.xml` brand settings (package/label/theme/scheme stay as rebranded).
- Anything that alters the rebrand commit `d1234426`.

---

## 3. Constraints

- Repo is MIT; copied YL-COS files carry AGPL-3.0. Private use only; document the provenance in `THIRD_PARTY_NOTICES.md` and keep the original copyright headers inside copied files.
- `minSdk 26`, `targetSdk 36`, `compileSdk 37` (our `app/build.gradle.kts`).
- Java 17 toolchain; Kotlin compiles to JVM 8 bytecode compatible with AGP.
- The phone must stay awake while the server runs (foreground service + partial wake lock + `FLAG_KEEP_SCREEN_ON`).
- No new Gradle dependencies beyond what YL-COS's `desktop`/`common` used (`kotlinx.coroutines.core`, `kotlinx.serialization` via libs.versions.toml — verify ours has them).
- Foreground service type `specialUse` requires `FOREGROUND_SERVICE_SPECIAL_USE` permission on Android 14+.
- Network permission (`INTERNET`, `ACCESS_NETWORK_STATE`) already declared in our manifest — verify.
- All existing rebrand tokens must remain intact (`com.ylprivate.agents`, `Theme.YLPrivateAgents`, `ylprivateagents`, preserved skill/tmp/URL tokens).

---

## 4. Approaches considered

| Approach | What it is | Trade-off | Decision |
|---|---|---|---|
| A. Clean reimplementation | Write our own `webaccess/` + web UI from scratch, same pattern (snapshot/actions/SSE), our theme. | Safest license-wise (MIT stays MIT), but ~2× the work and the UI must be designed. | Not chosen |
| B. Clean reimplementation, chat-only first | Same as A but browser covers only chat/composer/model/reasoning/approvals. | Ships fastest, proves the bridge, leaves settings for later. | Not chosen |
| C. Copy YL-COS package, adapt to ours (chosen) | Lift `webaccess/` Kotlin + `assets/web-access/` HTML/CSS/JS, rewrite only `WebAccessBridge` against our `ChatViewModel`, remap theme to our colors, retarget i18n to our strings. | Fastest, closest to the layout the user praised, but copied code is AGPL-3.0 and i18n is Chinese-first (user accepted: private use only). | **Chosen** |

---

## 5. How it gets built (the plan)

### New files
| File | Source | What changes |
|---|---|---|
| `app/src/main/java/com/ylprivate/agents/webaccess/WebAccessHttp.kt` | YL-COS copy | none (self-contained parser) |
| `app/src/main/java/com/ylprivate/agents/webaccess/WebAccessManager.kt` | YL-COS copy | package retarget; starts on our `MainActivity` |
| `app/src/main/java/com/ylprivate/agents/webaccess/WebAccessBridge.kt` | YL-COS skeleton | **new logic**: snapshot from `ChatViewModel.state`, actions dispatched to `ChatViewModel`/`AgentEngine`, `allSessions()`/`ProjectEntity` for workspace list, media/locale stubs |
| `app/src/main/java/com/ylprivate/agents/webaccess/WebAccessForegroundService.kt` | YL-COS copy | package retarget; notification channel + wake lock |
| `app/src/main/assets/web-access/index.html` | YL-COS copy | title `YL Private Agents`; replace `data-i18n` keys with our string-resource equivalents where they exist; keep layout/CSS classes intact |
| `app/src/main/assets/web-access/app.js` | YL-COS copy | same action names (`send`, `stop`, `continue`, `model`, `tool`, `approval`, `question`, …); endpoint paths unchanged |
| `app/src/main/assets/web-access/style.css` | YL-COS copy | colour tokens remapped to our palette via theme JSON from snapshot |
| `app/src/main/assets/web-access/{stream,rich-content,reasoning,i18n}.js` | YL-COS copy | i18n.js: replace Chinese-first table with our strings where available |
| `app/src/main/assets/web-access/{marked,purify,katex,mermaid,echarts}.js/.css` | YL-COS copy | vendored, unchanged |
| `app/src/main/java/com/ylprivate/agents/ui/settings/services/SettingsWebAccessScreen.kt` | YL-COS skeleton | settings rows for host/port/start-stop/addresses/token, using our `AppSettings` keys |

### Modified files
| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | add `FOREGROUND_SERVICE_SPECIAL_USE` permission (Android 14+); register `WebAccessForegroundService` (`foregroundServiceType="specialUse"`, `stopWithTask="true"`); add `INTERNET`/`ACCESS_NETWORK_STATE` if missing |
| `app/src/main/java/com/ylprivate/agents/MainActivity.kt` | instantiate `WebAccessManager`, call `updateTheme(darkMode, webColors)` in a `SideEffect`, expose the manager to the bridge |
| `app/src/main/java/com/ylprivate/agents/ui/theme/Color.kt` (read-only) | source of `--background/--primary/…` for theme mapping |
| `app/src/main/java/com/ylprivate/agents/ui/settings/SettingsHome.kt` | register `WebAccessSettings` as one section (AGENTS.md rule: one registration point) |
| `app/src/main/res/values/strings.xml` | add `web_access_*` strings (title, desc, lan, port, start/stop, addresses, token, copy, reset, hint, storage perm) |
| `THIRD_PARTY_NOTICES.md` | document AGPL-3.0 provenance of copied `webaccess/` + `assets/web-access/` |

### Bridge contract (the risky part)
YL-COS bridge exposes ~30 actions. Our first version implements the chat-core subset that the HTML actually calls:

| Action | Our equivalent |
|---|---|
| `send` | `ChatViewModel.send(text)` |
| `stop` / `continue` | `ChatViewModel.stop()` / `ChatViewModel.continueActive()` |
| `new` / `select` / `rename` / `pin` / `archive` / `restore` | Room session CRUD via `Database.allSessions()` + ViewModel |
| `model` / `reasoning` | provider + thinking level selection |
| `autoApproval` / `approval` / `question` | `approve(rememberForSession)` / `deny()` / `answerQuestion(answer)` |
| `tool` / `mcpSave` / `mcpEnabled` / `mcpRefresh` | settings tool toggles + `McpClientManager` |
| `compression` / `compress` | history compaction settings + run |
| `browseWorkspace` / `workspace` / `projectCreate` | `ProjectEntity` / workspace selection |
| `upload` | `UploadedFileManager` / pending uploads |

Snapshot fields (from YL-COS client's expected list) mapped to our `ChatUiState`:
`messages`, `runningCalls`, `pendingApproval`, `pendingQuestion`, `busy`, `streamingText`, `currentTurnId`, `thinkingLevel`, `mode`, `workspaceName`, `title`, `sessionId`, `conversationId`, `projectId`, `providers`/`activeModel`, `theme`, `uploads`, `approval`, `question`, `subAgents`, `mcpServers`, `tools`, `prompts`, `purePrompt`, `autoApproval`, `compression`, `context` (estimate), `canContinue`, `generating`, `compressing`, `loading`, `instanceId`, `sessionRevision`.

Theme mapping: `ColorScheme` roles → CSS variables (`--background`, `--on-background`, `--surface`, `--primary`, `--secondary`, `--error`, …) pushed as `theme` JSON in every snapshot; browser applies them via `applyTheme()`.

### Bridge left out of first version (can follow)
- subAgentSave/subAgentDelete (settings-only screens)
- promptSave/promptDelete (settings-only)
- `projectRename`/`projectPin`/`projectArchive` (settings-only)
- voice settings

---

## 6. What's left to the builder

- Confirm `libs.versions.toml` has `kotlinx.coroutines.core` and serialization for the JVM desktop-style server code path (we only need the Android side, but coroutines are already used everywhere).
- Decide whether `WebAccessManager` is constructed in `MainActivity` (Activity-scoped, like YL-COS) or as a singleton; YL-COS passes `activity` and `controller` into the bridge, so the Activity holds the single controller instance.
- Verify `AppSettings` has writable keys for `webAccessHost`, `webAccessPort`, `webAccessToken`; add if missing.
- Confirm `UploadedFileManager` exists in our tree (YL-COS uses it for `/api/upload`).
- i18n: decide how much of `i18n.js` to retarget to our `strings.xml` vs keep as inline English.
- Add the `FOREGROUND_SERVICE_SPECIAL_USE` permission gate for Android 14+ (`Build.VERSION.SDK_INT >= 34`).

---

## 7. Size

This is a **project**: new package (~4 Kotlin files + bridge logic), new assets folder (~10 files), manifest + settings + strings + theme integration. Estimate: 3–5 days of focused work including manual device testing of the browser flow.

---

Approval requested: approve this design so I can proceed to the plan and implementation. If anything above should change (scope, license stance, bridge actions), say so now and I'll revise.
