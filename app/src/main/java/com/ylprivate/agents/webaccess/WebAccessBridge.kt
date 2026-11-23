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
 * current chat and executes actions ([send], [stop], [approve], [deny], [question], [model], …)
 * against the active [ChatViewModel].
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

    /** Full snapshot of the app state the browser renders. v1: chat-core fields only. */
    suspend fun snapshot(): JSONObject {
        val c = controller ?: return emptySnapshot()
        val state = c.state.value

        val messages = JSONArray()
        state.messages.forEachIndexed { index, m ->
            messages.put(messageJson(m, index))
        }

        val providers = JSONArray()
        container.providers.providers.first().forEach { p ->
            providers.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("model", p.model)
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

        val attachments = JSONArray()
        state.fileAttachments.forEachIndexed { index, f ->
            attachments.put(
                JSONObject()
                    .put("index", index)
                    .put("name", f.name)
                    .put("mime", f.mime)
                    .put("size", f.sizeLabel),
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

        return JSONObject()
            .put("instanceId", instanceId)
            .put("version", sessionRevision)
            .put("theme", theme)
            .put("canContinue", false)
            .put("conversationId", state.sessionId.orEmpty())
            .put("title", state.sessionTitle)
            .put("workspace", JSONObject()
                .put("name", state.workspaceName)
                .put("path", ""))
            .put("running", state.busy)
            .put("loading", false)
            .put("generating", false)
            .put("compressing", false)
            .put("model", state.activeModel.orEmpty())
            .put("thinkingLevel", state.thinkingLevel.name)
            .put("mode", state.mode.name)
            .put("messages", messages)
            .put("attachments", attachments)
            .put("runningCalls", runningCalls)
            .put("providers", providers)
            .put("tools", tools)
            .put("reasoning", settings.thinkingLevel.name)
            .put("subAgentsEnabled", settings.subagentsEnabled)
            .put("approval", pendingApprovalJson(state.pendingApproval))
            .put("question", pendingQuestionJson(state.pendingQuestion))
            .put("context", contextJson(state.estimate))
    }

    private fun emptySnapshot(): JSONObject =
        JSONObject().put("instanceId", instanceId).put("version", sessionRevision)
            .put("theme", theme).put("messages", JSONArray())
            .put("running", false).put("loading", false).put("generating", false)
            .put("compressing", false).put("model", "").put("attachments", JSONArray())
            .put("runningCalls", JSONArray()).put("providers", JSONArray())
            .put("tools", JSONArray())
            .put("approval", JSONObject.NULL).put("question", JSONObject.NULL)
            .put("context", JSONObject().put("tokens", 0))

    private fun messageJson(m: com.ylprivate.agents.core.ChatMessage, index: Int): JSONObject {
        return JSONObject()
            .put("id", index)
            .put("role", m.role.name)
            .put("text", m.text)
            .put("thinking", m.thinking)
            .put("isError", m.isError)
            .put("outputTokens", m.outputTokens)
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
            .put("question", q.question)
            .put("options", JSONArray(q.options))
            .put("recommended", JSONArray(q.recommended))
    }

    private fun contextJson(e: ContextEstimate?): JSONObject {
        if (e == null) return JSONObject().put("tokens", 0)
        return JSONObject()
            .put("tokens", e.total)
            .put("messages", e.messagesTokens)
            .put("system", e.systemTokens)
            .put("tools", e.toolsTokens)
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

    /** Execute one action from the browser. v1 chat-core subset. */
    suspend fun action(args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val action = args.getString("action")
        val mutating = setOf("send", "stop", "approve", "deny", "question", "model")
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
                val providerId = args.getString("providerId")
                val model = args.getString("model")
                require(model.isNotEmpty()) { "Model is required" }
                controller?.selectModel(providerId, model)
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
}
