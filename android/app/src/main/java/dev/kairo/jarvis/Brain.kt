package dev.kairo.jarvis

import java.io.File

/**
 * Where JARVIS's thinking happens: the brain in Termux on this phone (BrainClient), or the laptop
 * through Supabase (RelayClient). Every callback runs on the main thread.
 */
interface Brain {
    interface Listener {
        /** A tool name ("pc_status") or a ready-made label ("Searching the web…"). */
        fun onTool(name: String)
        fun onReply(text: String, images: List<File>)
        fun onError(message: String, brainDown: Boolean)
    }

    fun ask(text: String, listener: Listener)
    fun screenshot(listener: Listener)
    fun cancel()
    fun reset(done: (Boolean) -> Unit = {})
    fun health(done: (ok: Boolean, text: String) -> Unit)
}
