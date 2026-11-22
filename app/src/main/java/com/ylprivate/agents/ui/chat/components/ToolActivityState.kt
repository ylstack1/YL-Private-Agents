package com.ylprivate.agents.ui.chat.components

import com.ylprivate.agents.core.ChatMessage
import com.ylprivate.agents.core.ToolCallData

/** Completed results take precedence over delayed running-state updates. */
internal fun activeToolIds(
    calls: List<ToolCallData>,
    results: Map<String, ChatMessage?>,
    runningIds: Set<String>,
): Set<String> = calls.mapNotNull { call ->
    call.id.takeIf { it in runningIds && results[it] == null }
}.toSet()
