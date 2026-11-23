package com.ylprivate.agents.webaccess

import java.io.InputStream

/**
 * Minimal media store for /api/media (unguessable ID grants read access to one referenced file).
 * The v1 chat-core scope doesn't serve attachments yet, so the catalog is empty and
 * any request returns 404. Expanded later when file upload lands.
 * Copied in structure from YL-COS `webaccess/WebAccessMedia.kt`.
 * License: AGPL-3.0 (YL-COS). Private use only while copied verbatim.
 */
internal class WebAccessMedia(private val context: android.content.Context) {
    /** One addressable media resource. */
    internal data class Resource(
        val name: String,
        val mime: String,
        val length: Long,
        private val bytes: ByteArray,
    ) {
        fun input(): InputStream = java.io.ByteArrayInputStream(bytes)
    }

    private val catalog = linkedMapOf<String, Resource>()

    /** Register a resource under an unguessable id; returns the id. */
    fun put(name: String, mime: String, bytes: ByteArray): String {
        val id = java.util.UUID.randomUUID().toString()
        catalog[id] = Resource(name, mime, bytes.size.toLong(), bytes)
        return id
    }

    fun open(id: String): Resource? = catalog[id]
}
