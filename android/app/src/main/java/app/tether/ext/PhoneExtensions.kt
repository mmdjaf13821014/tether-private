package app.tether.ext

import app.tether.TetherApp
import app.tether.core.ext.EchoExtension
import app.tether.core.ext.TetherExtension

/**
 * The extensions the Android phone runs (protocol §7, docs/EXTENDING.md). Add yours here: Hub registers this list once,
 * routes their messages from the fallback of its message switch and announces them in every status. An extension
 * whose id or types break the rules is refused at registration (logged), never half-registered.
 */
fun phoneExtensions(@Suppress("UNUSED_PARAMETER") app: TetherApp): List<TetherExtension> = listOf(
    EchoExtension(),
)
