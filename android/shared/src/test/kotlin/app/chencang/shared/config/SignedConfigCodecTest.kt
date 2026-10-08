package app.chencang.shared.config

import app.chencang.shared.config.ConfigTestSupport.envelope
import app.chencang.shared.config.ConfigTestSupport.fakeSig
import app.chencang.shared.config.ConfigTestSupport.fakeVerify
import app.chencang.shared.config.ConfigTestSupport.payload
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignedConfigCodecTest {
    private fun dec(e: String) = SignedConfigCodec.decode(e, fakeVerify)

    @Test fun decodesValidEnvelope() {
        val c = dec(envelope(payload(seq = 7)))!!
        assertThat(c.seq).isEqualTo(7)
        assertThat(c.sources).containsExactly("https://a.example/c/")
        assertThat(c.android!!.latest.versionCode).isEqualTo(3)
    }

    @Test fun rejectsBadSignature() {
        assertThat(dec(envelope(payload(), sig = ByteArray(64)))).isNull()
        assertThat(dec(envelope(payload(), sig = fakeSig("x".toByteArray())))).isNull()
    }

    @Test fun rejectsSchema2() = assertThat(dec(envelope(payload(schema = 2)))).isNull()

    @Test fun rejectsHttpUrl() {
        assertThat(dec(envelope(payload(sources = """["http://a.example/c/"]""")))).isNull()
        assertThat(dec(envelope(payload(relays = """["http://r.example"]""")))).isNull()
        assertThat(dec(envelope(payload(shareSite = "http://s.example/")))).isNull()
        assertThat(dec(envelope(payload(mirrors = """["http://m.example/x.apk"]""")))).isNull()
    }

    @Test fun rejectsUppercaseSha() =
        assertThat(dec(envelope(payload(sha = ConfigTestSupport.SHA.uppercase())))).isNull()

    @Test fun rejectsLatestBelowMin() =
        assertThat(dec(envelope(payload(versionCode = 2, minVersionCode = 3)))).isNull()

    @Test fun ignoresUnknownKeys() =
        assertThat(dec(envelope(payload(extra = ""","future":{"a":1}""")))).isNotNull()

    @Test fun rejectsMalformedBase64() {
        assertThat(dec("""{"p":"!!!","s":"???"}""")).isNull()
        assertThat(dec("not json")).isNull()
    }

    @Test fun rejectsStructuralViolations() {
        assertThat(dec(envelope(payload(sources = "[]")))).isNull()
        assertThat(dec(envelope(payload(sources = """["https://a.example/c"]""")))).isNull()
        assertThat(dec(envelope(payload(relays = """["https://r.example/"]""")))).isNull()
        assertThat(dec(envelope(payload(shareSite = "https://s.example")))).isNull()
        assertThat(dec(envelope(payload(mirrors = "[]")))).isNull()
    }
}

class FactoryConfigTest {
    @Test fun factoryConfigVerifiesWithProductionKey() {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !java.io.File(dir, "release/config/chencang-config.json").exists()) dir = dir.parentFile
        val f = requireNotNull(dir) { "release/config/chencang-config.json not found" }
            .resolve("release/config/chencang-config.json")
        val c = SignedConfigCodec.decode(f.readText()) { p, s -> uniffi.chencang.verifySignedConfig(p, s) }
        assertThat(c).isNotNull()
        assertThat(c!!.seq).isAtLeast(1L)
        assertThat(c.relays).hasSize(3)
    }
}
