package app.tether.client.ext

import app.tether.core.ext.EchoExtension
import app.tether.core.ext.TetherExtension

/**
 * The extensions the Android client runs (protocol §7, docs/EXTENDING.md). Add yours here: ClientApp registers this
 * list, announces it in every sync and routes the Android phone's extension messages to it (main thread).
 */
fun clientExtensions(): List<TetherExtension> = listOf(
    EchoExtension(),
)
