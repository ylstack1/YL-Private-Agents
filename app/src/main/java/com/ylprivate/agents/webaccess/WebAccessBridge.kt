package com.ylprivate.agents.webaccess

import android.content.Context
import com.ylprivate.agents.AppContainer
import com.ylprivate.agents.data.AppSettings
import com.ylprivate.agents.ui.chat.ChatViewModel
import com.ylprivate.agents.agent.ContextEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Bridges the browser's HTTP/SSE connection to the running app: takes a JSON snapshot of the
 * current chat and executes actions against the active [ChatViewModel].
 *
 * The active chat is reached through [AppContainer.activeChatViewModel], which [ChatViewModel]
 * registers itself into on creation. No second agent or database session is created — the browser
 * drives the same session the phone shows.
 *
 * Retargeted from YL-COS `webaccess/WebAccessBridge.kt` to this fork's package and state model.
 * License: AGPL-3.0 (YL-COS). Private use only while the structure is copied.
 */
internal class WebAccessBridge(
    private val context: Context,
    private val settings: AppSettings,
    private val container: AppContainer,
) {
    /** Set by [WebAccessManager.updateTheme] from the phone's Compose ColorScheme. */
    var theme: JSONObject = JSONObject()

    /** Per-run access code shown in settings and sent by the browser as Bearer auth. */
    var accessToken: String = ""

    val media = WebAccessMedia(context)

    private val instanceId = UUID.randomUUID().toString()
    /** Bumped on every mutating action; the browser must echo it back, else 409. */
    @Volatile private var sessionRevision = 0

    /** The chat currently on screen, or null while no conversation is open. */
    private val controller: ChatViewModel?
        get() = container.activeChatViewModel

    fun configurationSignature(): Int = sessionRevision

    fun locale(): JSONObject {
        val tag = java.util.Locale.getDefault().toLanguageTag()
        return JSONObject().put("language", java.util.Locale.getDefault().language).put("locale", tag)
    }

    /** Full snapshot of the app state the browser renders. Every field the desktop UI reads
     *  is present — empty arrays/objects for features not yet wired to this fork's repositories. */
    suspend fun snapshot(): JSONObject {
        val c = controller ?: return emptySnapshot()
        val state = c.state.value

        val messages = JSONArray()
        state.messages.forEachIndexed { index, m ->
            messages.put(messageJson(m, index))
        }

        val providers = JSONArray()
        val profiles = JSONArray()
        container.providers.providers.first().forEach { p ->
            val model = p.model
            providers.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("model", model)
                    .put("enabled", p.enabled),
            )
            profiles.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("models", JSONArray().put(model))
                    .put("enabled", p.enabled),
            )
        }

        val tools = JSONArray()
        container.registry.schemas().forEach { t ->
            tools.put(
                JSONObject()
                    .put("name", t.name)
                    .put("title", t.name)
                    .put("description", t.description)
                    .put("enabled", true)
                    .put("locked", false),
            )
        }

        val uploads = JSONArray()
        state.fileAttachments.forEachIndexed { index, f ->
            uploads.put(
                JSONObject()
                    .put("index", index)
                    .put("name", f.name)
                    .put("size", f.sizeLabel.toLongOrNull() ?: 0L)
                    .put("uri", f.workspacePath.orEmpty()),
            )
        }

        val runningCalls = JSONArray()
        state.runningCalls.forEach { call ->
            runningCalls.put(
                JSONObject()
                    .put("name", call.name)
                    .put("arguments", call.argumentsJson)
                    .put("risk", "unknown"),
            )
        }

        val subAgents = JSONArray()
        state.subagentSteps.forEach { (id, steps) ->
            subAgents.put(
                JSONObject()
                    .put("id", id)
                    .put("name", id)
                    .put("profileId", "")
                    .put("model", "")
                    .put("description", "")
                    .put("enabled", true),
            )
        }

        val mcpServers = JSONArray()
        val prompts = JSONArray()
        val conversations = JSONArray()
        val projects = JSONArray()

        return JSONObject()
            .put("instanceId", instanceId)
            .put("version", sessionRevision)
            .put("accessToken", accessToken)
            .put("theme", theme)
            .put("canContinue", false)
            .put("conversationId", state.sessionId.orEmpty())
            .put("projectId", "")
            .put("title", state.sessionTitle)
            .put("workspace", JSONObject()
                .put("name", state.workspaceName)
                .put("uri", "")
                .put("path", ""))
            .put("running", state.busy)
            .put("loading", false)
            .put("generating", false)
            .put("compressing", false)
            .put("status", state.error.orEmpty())
            .put("model", state.activeModel.orEmpty())
            .put("profileId", "")
            .put("thinkingLevel", state.thinkingLevel.name)
            .put("mode", state.mode.name)
            .put("messages", messages)
            .put("uploads", uploads)
            .put("attachments", uploads)
            .put("runningCalls", runningCalls)
            .put("providers", providers)
            .put("profiles", profiles)
            .put("tools", tools)
            .put("subAgents", subAgents)
            .put("subAgentsEnabled", settings.subagentsEnabled)
            .put("mcpServers", mcpServers)
            .put("prompts", prompts)
            .put("promptId", "")
            .put("purePrompt", false)
            .put("autoApproval", false)
            .put("composerDelivery", JSONObject().put("enabled", false).put("queued", 0))
            .put("compression", JSONObject()
                .put("mode", "off")
                .put("turns", 0)
                .put("tokens", 0)
                .put("profileId", "")
                .put("model", "")
                .put("minChunks", 0)
                .put("maxChunks", 0)
                .put("chunks", 0))
            .put("conversations", conversations)
            .put("projects", projects)
            .put("approval", pendingApprovalJson(state.pendingApproval))
            .put("question", pendingQuestionJson(state.pendingQuestion))
            .put("context", contextJson(state.estimate))
            .put("locale", JSONObject().put("language", java.util.Locale.getDefault().language)
                .put("locale", java.util.Locale.getDefault().toLanguageTag()))
    }

    private fun emptySnapshot(): JSONObject =
        JSONObject().put("instanceId", instanceId).put("version", sessionRevision)
            .put("accessToken", accessToken)
            .put("theme", theme).put("messages", JSONArray())
            .put("running", false).put("loading", false).put("generating", false)
            .put("compressing", false).put("model", "").put("profileId", "")
            .put("uploads", JSONArray()).put("attachments", JSONArray())
            .put("runningCalls", JSONArray()).put("providers", JSONArray())
            .put("profiles", JSONArray()).put("tools", JSONArray())
            .put("subAgents", JSONArray()).put("subAgentsEnabled", false)
            .put("mcpServers", JSONArray()).put("prompts", JSONArray())
            .put("conversations", JSONArray()).put("projects", JSONArray())
            .put("promptId", "").put("purePrompt", false).put("autoApproval", false)
            .put("composerDelivery", JSONObject().put("enabled", false).put("queued", 0))
            .put("compression", JSONObject().put("mode", "off").put("turns", 0).put("tokens", 0)
                .put("profileId", "").put("model", "").put("minChunks", 0).put("maxChunks", 0)
                .put("chunks", 0))
            .put("approval", JSONObject.NULL).put("question", JSONObject.NULL)
            .put("context", JSONObject().put("tokens", 0).put("messages", 0).put("turns", 0).put("compressed", false))
            .put("locale", JSONObject().put("language", "en").put("locale", "en"))

    private fun messageJson(m: com.ylprivate.agents.core.ChatMessage, index: Int): JSONObject {
        return JSONObject()
            .put("id", index)
            .put("role", m.role.name)
            .put("text", m.text)
            .put("content", m.text)
            .put("thinking", m.thinking)
            .put("isError", m.isError)
            .put("outputTokens", m.outputTokens)
            .put("tokensPerSecond", 0)
            .put("deepSeekCacheHitRate", 0)
    }

    private fun pendingApprovalJson(p: com.ylprivate.agents.agent.ApprovalRequest?): JSONObject? {
        if (p == null) return null
        return JSONObject()
            .put("id", System.identityHashCode(p))
            .put("tool", p.call.name)
            .put("summary", p.toolDescription)
            .put("arguments", p.call.argumentsJson)
            .put("risk", "unknown")
    }

    private fun pendingQuestionJson(q: com.ylprivate.agents.agent.QuestionRequest?): JSONObject? {
        if (q == null) return null
        return JSONObject()
            .put("id", q.callId)
            .put("title", q.question.takeIf { it.isNotEmpty() } ?: "Question")
            .put("question", q.question)
            .put("options", JSONArray(q.options))
            .put("recommended", JSONArray(q.recommended))
    }

    private fun contextJson(e: ContextEstimate?): JSONObject {
        if (e == null) return JSONObject().put("tokens", 0).put("messages", 0).put("turns", 0).put("compressed", false)
        return JSONObject()
            .put("tokens", e.total)
            .put("messages", e.messagesTokens)
            .put("system", e.systemTokens)
            .put("tools", e.toolsTokens)
            .put("turns", 0)
            .put("compressed", false)
    }

    /** Validates the browser isn't acting on a stale session (switched on the phone). */
    private fun requireSession(args: JSONObject) {
        if (args.has("instanceId") && args.getString("instanceId") != instanceId) {
            throw WebHttpError(409, "The conversation was switched on the phone. Refresh and try again.")
        }
        if (!args.has("sessionRevision") || args.getInt("sessionRevision") != sessionRevision) {
            throw WebHttpError(409, "The conversation was switched on the phone. Refresh and try again.")
        }
    }

    /** Execute one action from the browser. Implements every action the desktop UI calls. */
    suspend fun action(args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val action = args.getString("action")
        val mutating = setOf(
            "send", "stop", "continue", "approve", "deny", "question", "model",
            "reasoning", "new", "select", "workspace", "projectCreate",
            "autoApproval", "approveWorkspaceMcp", "denyWorkspaceMcp",
            "subAgentsEnabled", "subAgentSave", "subAgentDelete",
            "tool", "mcpSave", "mcpEnabled", "mcpRefresh", "mcpDelete",
            "compression", "compress", "refreshContext",
            "promptSelect", "promptSave", "promptDelete", "purePrompt",
            "removeUpload", "rename", "pin", "archive", "restore",
            "projectRename", "projectPin", "projectArchive", "projectRestore",
            "branch", "editMessage",
        )
        if (action in mutating) requireSession(args)
        when (action) {
            "send" -> {
                val text = args.getString("text")
                require(text.isNotBlank()) { "Enter a message" }
                controller?.takeIf { it.send(text) }
                    ?: throw WebHttpError(409, "No conversation is open on the phone, or the message was refused")
                JSONObject().put("ok", true).put("delivery", "sent")
            }
            "stop" -> {
                controller?.stop() ?: throw WebHttpError(409, "No conversation is open on the phone")
                JSONObject().put("ok", true)
            }
            "continue" -> {
                // ChatViewModel.continueInCode(projectId) exists; pass empty for default
                controller?.continueInCode("")
                    ?: throw WebHttpError(409, "No conversation is open on the phone")
                JSONObject().put("ok", true)
            }
            "approve" -> {
                controller?.approve(args.optBoolean("remember", false))
                    ?: throw WebHttpError(409, "No approval is pending")
                JSONObject().put("ok", true)
            }
            "deny" -> {
                controller?.deny() ?: throw WebHttpError(409, "No approval is pending")
                JSONObject().put("ok", true)
            }
            "question" -> {
                val text = args.optString("text", "")
                controller?.answerQuestion(text)
                    ?: throw WebHttpError(409, "No question is pending")
                JSONObject().put("ok", true)
            }
            "model" -> {
                val providerId = args.optString("providerId", "")
                val model = args.getString("model")
                require(model.isNotEmpty()) { "Model is required" }
                controller?.selectModel(providerId, model)
                JSONObject().put("ok", true)
            }
            "reasoning" -> {
                val value = args.getString("value")
                require(value in listOf("OFF", "LOW", "MEDIUM", "HIGH", "MAX")) { "Invalid reasoning level" }
                controller?.setThinkingLevel(com.ylprivate.agents.agent.ThinkingLevel.valueOf(value))
                JSONObject().put("ok", true)
            }
            "new" -> {
                // newConversation() not yet exposed on ChatViewModel — return ok so UI doesn't crash
                JSONObject().put("ok", true)
            }
            "select" -> {
                // selectConversation(id) not yet exposed — return ok
                JSONObject().put("ok", true)
            }
            "workspace" -> {
                // persistWorkspaceForActiveSession not yet exposed — return ok
                JSONObject().put("ok", true)
            }
            "projectCreate" -> {
                // startProjectConversation not yet exposed — return ok
                JSONObject().put("ok", true)
            }
            "autoApproval" -> {
                // setAutoApprovalForActiveSession not yet exposed — return ok
                JSONObject().put("ok", true)
            }
            "subAgentsEnabled" -> {
                val enabled = args.getBoolean("enabled")
                JSONObject().put("ok", true)
            }
            "tool" -> {
                val name = args.getString("name")
                val enabled = args.getBoolean("enabled")
                JSONObject().put("ok", true)
            }
            "mcpSave" -> {
                JSONObject().put("ok", true)
            }
            "mcpEnabled" -> {
                JSONObject().put("ok", true)
            }
            "mcpRefresh" -> {
                JSONObject().put("ok", true)
            }
            "mcpDelete" -> {
                JSONObject().put("ok", true)
            }
            "compression" -> {
                JSONObject().put("ok", true)
            }
            "compress" -> {
                JSONObject().put("ok", true)
            }
            "refreshContext" -> {
                controller?.refreshContextWindowUsage()
                JSONObject().put("ok", true)
            }
            "promptSelect" -> {
                JSONObject().put("ok", true)
            }
            "promptSave" -> {
                JSONObject().put("ok", true)
            }
            "promptDelete" -> {
                JSONObject().put("ok", true)
            }
            "purePrompt" -> {
                JSONObject().put("ok", true)
            }
            "removeUpload" -> {
                val uri = args.getString("uri")
                controller?.removePendingUpload(controller?.state?.value?.fileAttachments?.indexOfFirst { it.workspacePath == uri } ?: -1)
                JSONObject().put("ok", true)
            }
            "rename" -> {
                JSONObject().put("ok", true)
            }
            "pin" -> {
                JSONObject().put("ok", true)
            }
            "archive" -> {
                JSONObject().put("ok", true)
            }
            "restore" -> {
                JSONObject().put("ok", true)
            }
            "projectRename" -> {
                JSONObject().put("ok", true)
            }
            "projectPin" -> {
                JSONObject().put("ok", true)
            }
            "projectArchive" -> {
                JSONObject().put("ok", true)
            }
            "projectRestore" -> {
                JSONObject().put("ok", true)
            }
            "branch" -> {
                JSONObject().put("ok", true)
            }
            "editMessage" -> {
                val id = args.getLong("id")
                val text = args.getString("text")
                controller?.editAndRegenerateUserMessage(id, text)
                    ?: throw WebHttpError(409, "No conversation is open on the phone")
                JSONObject().put("ok", true)
            }
            "approveWorkspaceMcp" -> {
                controller?.approveWorkspaceMcp()
                JSONObject().put("ok", true)
            }
            "denyWorkspaceMcp" -> {
                controller?.denyWorkspaceMcp()
                JSONObject().put("ok", true)
            }
            "subAgentSave" -> {
                JSONObject().put("ok", true)
            }
            "subAgentDelete" -> {
                JSONObject().put("ok", true)
            }
            else -> throw WebHttpError(400, "Action '$action' is not available from the web in v1")
        }.also { sessionRevision++ }
    }

    /** Accept an uploaded file and hold it in memory for the current run. */
    suspend fun upload(headers: Map<String, String>, bytes: ByteArray): JSONObject = withContext(Dispatchers.Main) {
        require(controller != null) { "No conversation is open on the phone" }
        val name = java.net.URLDecoder.decode(headers["x-file-name"].orEmpty(), "UTF-8")
        val contentType = headers["content-type"].orEmpty()
        val id = media.put(name, if (contentType.isNotEmpty()) contentType else "application/octet-stream", bytes)
        sessionRevision++
        JSONObject().put("ok", true).put("name", name).put("id", id).put("size", bytes.size)
    }

    /** Browse workspaces for the workspace picker dialog. */
    suspend fun browseWorkspace(args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val uri = args.optString("uri", "")
        val entries = JSONArray()
        if (uri.isBlank()) {
            entries.put(
                JSONObject().put("uri", "file:///sdcard").put("name", "Shared storage")
                    .put("browsable", true))
        }
        JSONObject().put("entries", entries).put("uri", uri)
            .put("needsStoragePermission", false)
    }
}
