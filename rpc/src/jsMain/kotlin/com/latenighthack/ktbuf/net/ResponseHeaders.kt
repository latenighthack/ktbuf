package com.latenighthack.ktbuf.net

/** XHR exposes repeated response headers as comma-joined values. Keys are canonical lowercase;
 * use rpcHeader for case-insensitive lookup. Cookie headers are not exposed by XHR. */
internal fun parseResponseHeaders(raw: String): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    for (line in raw.split('\n')) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim().lowercase()
        if (name.isEmpty()) continue
        val value = line.substring(colon + 1).trim()
        headers[name] = headers[name]?.let { "$it, $value" } ?: value
    }
    return headers
}
