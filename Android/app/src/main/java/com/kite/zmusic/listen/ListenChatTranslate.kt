package com.kite.zmusic.listen

/**
 * 聊天室消息翻译（仅本机可见）。
 * [visible]=false 时仍保留 [text]，避免重复请求；结束一起听后整体清空。
 */
data class ListenChatTranslateEntry(
    val text: String = "",
    val visible: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

fun listenChatTranslateFingerprint(msg: ListenChatMsg): String =
    "${msg.uid.trim()}\n${msg.text}"

fun listenChatTranslateLookup(
    map: Map<String, ListenChatTranslateEntry>,
    msg: ListenChatMsg,
): ListenChatTranslateEntry? {
    map[listenChatToastKey(msg)]?.let { return it }
    return map[listenChatTranslateFingerprint(msg)]
}

/**
 * 本地乐观消息 id 变为服务端 id 时，把翻译缓存键迁过去。
 */
fun retargetListenChatTranslations(
    current: Map<String, ListenChatTranslateEntry>,
    previous: List<ListenChatMsg>,
    merged: List<ListenChatMsg>,
): Map<String, ListenChatTranslateEntry> {
    if (current.isEmpty()) return current
    val out = current.toMutableMap()
    for (prev in previous) {
        if (prev.id >= 0L) continue
        val oldKey = listenChatToastKey(prev)
        val entry = out[oldKey] ?: continue
        val remote = retargetListenChatToast(prev, merged) ?: continue
        if (remote.id <= 0L || remote.id == prev.id) continue
        val newKey = listenChatToastKey(remote)
        out.remove(oldKey)
        out[newKey] = entry
        out[listenChatTranslateFingerprint(remote)] = entry
    }
    return out
}

fun putListenChatTranslate(
    current: Map<String, ListenChatTranslateEntry>,
    msg: ListenChatMsg,
    entry: ListenChatTranslateEntry,
): Map<String, ListenChatTranslateEntry> {
    val next = current.toMutableMap()
    next[listenChatToastKey(msg)] = entry
    next[listenChatTranslateFingerprint(msg)] = entry
    return next
}

fun hideListenChatTranslate(
    current: Map<String, ListenChatTranslateEntry>,
    msg: ListenChatMsg,
): Map<String, ListenChatTranslateEntry> {
    val cur = listenChatTranslateLookup(current, msg) ?: return current
    if (!cur.visible && !cur.loading) return current
    return putListenChatTranslate(
        current,
        msg,
        cur.copy(visible = false, loading = false),
    )
}
