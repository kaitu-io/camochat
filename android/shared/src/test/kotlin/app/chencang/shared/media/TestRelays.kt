package app.chencang.shared.media

import app.chencang.shared.config.FakeSharedPreferences
import app.chencang.shared.config.RelaySelector
import okhttp3.mockwebserver.MockWebServer

/** Test relay selectors over plain origins (no trailing slash). */
fun relaysOf(vararg origins: String) = RelaySelector({ origins.toList() }, FakeSharedPreferences())

fun origin(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

fun relaysOf(vararg servers: MockWebServer) = relaysOf(*servers.map { origin(it) }.toTypedArray())
