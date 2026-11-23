feat(web-mode): full compatible web mode with YL Private Agents branding

Complete the web access bridge with full snapshot mapping matching the
YL-COS web client contract: messages, uploads, running calls, providers,
tools, subagents, MCP servers, prompts, compression, context, approval,
question, theme, locale, and all 30+ state fields the desktop UI reads.
Implements chat-core actions (send/stop/continue/approve/deny/question/
model/upload) plus settings-compatible stubs for actions not yet wired
to this fork's SettingsRepository.

Rebrand all web assets: index.html title YL COS → YL Private Agents,
lang zh-CN → en, all Chinese UI text → English. Add web_access_* string
resources and register WEB_ACCESS settings page.

Copied webaccess/ and assets/web-access/ carry YL-COS AGPL-3.0
provenance (private use only); documented in THIRD_PARTY_NOTICES.md.
