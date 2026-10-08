package app.chencang.shared.media

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * spec §2.5 / 裁决 R6：文字零网络。`:app`、`:shared`、`:design` 的所有生产源码集
 * （`src/` 下除 `test`/`androidTest` 以外的每个目录：main、debug……）里，除了**那一个**
 * `shared/src/main/kotlin/app/chencang/shared/media/MediaTransport.kt`，出现任何网络 API
 * 或中转 base URL 都算违规。生产依赖里也不许出现 HTTP 客户端库——`MediaTransport` 只用平台
 * 自带的 `HttpURLConnection`。Custom Tab 打开 `/source` 走系统浏览器组件，不在名单里。
 */
class NetworkIsolationGuardTest {

    private val networkApi = Regex(
        listOf(
            """HttpURLConnection""",
            """HttpsURLConnection""",
            """openConnection""",
            """openStream""",
            """\bURL\b.*\.read(Text|Bytes)\s*\(""",
            """OkHttp""",
            """okhttp3""",
            """\bWebView\b""",
            """\bloadUrl\s*\(""",
            """\bDownloadManager\b""",
            """javax\.net\.ssl""",
            """java\.net\.Socket""",
            """import\s+java\.net\.\*""",
            """import\s+android\.webkit\.\*""",
            """URLSession|NSURLSession|CFNetwork""",
        ).joinToString("|"),
    )
    private val allowedPaths = listOf(
        "shared/src/main/kotlin/app/chencang/shared/media/MediaTransport.kt",
        "shared/src/main/kotlin/app/chencang/shared/config/ConfigFetcher.kt",
        "app/src/direct/kotlin/app/chencang/android/update/ApkDownloader.kt",
    )

    /** 生产依赖里不许出现的 HTTP 客户端（group 或 group:artifact 前缀）。 */
    private val httpClientModules = listOf(
        "com.squareup.okhttp", "com.squareup.okhttp3", "com.squareup.retrofit", "com.squareup.retrofit2",
        "io.ktor", "com.android.volley", "org.chromium.net", "com.google.android.gms:play-services-cronet",
        "org.apache.httpcomponents", "com.github.kittinunf.fuel",
    )
    private val modules = listOf("app", "shared", "design")

    private fun androidRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "android/settings.gradle.kts not found above ${System.getProperty("user.dir")}" }
    }

    private fun rel(f: File): String = f.relativeTo(androidRoot()).invariantSeparatorsPath

    /** 三个模块 `src/` 下所有非测试源码集里的 Kotlin/Java 文件。 */
    private fun productionSources(): List<File> =
        modules.flatMap { m ->
            (File(androidRoot(), "$m/src").listFiles() ?: emptyArray())
                .filter { it.isDirectory && it.name != "test" && it.name != "androidTest" && !it.name.startsWith("test") }
                .flatMap { set ->
                    set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
                }
        }

    private fun offenders(match: (String) -> Boolean): List<String> =
        productionSources().filter { rel(it) !in allowedPaths }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (match(line)) "${rel(f)}:${i + 1}: ${line.trim()}" else null
            }
        }

    @Test
    fun `guard actually scans the source tree, including design and the exempt file`() {
        val sources = productionSources().map { rel(it) }
        assertThat(sources.size).isGreaterThan(20)
        assertThat(sources).containsAtLeastElementsIn(allowedPaths)
        assertThat(sources.any { it.startsWith("design/src/main/") }).isTrue()
    }

    @Test
    fun `network APIs appear only in the one MediaTransport kt`() {
        val bad = offenders { networkApi.containsMatchIn(it) }
        assertWithMessage("network API outside $allowedPaths:\n" + bad.joinToString("\n")).that(bad).isEmpty()
    }

    @Test
    fun playSourceSetHasNoNetworkApi() {
        val playFiles = File(androidRoot(), "app/src/play").walkTopDown().filter { it.isFile }.toList()
        assertThat(playFiles).isNotEmpty()
        val bad = playFiles.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (networkApi.containsMatchIn(line)) "${rel(f)}:${i + 1}" else null
            }
        }
        assertWithMessage("network API in play flavor:\n" + bad.joinToString("\n")).that(bad).isEmpty()
    }

    /** 旧域名彻底退出：needle 由两段拼成，所以本文件自己也在扫描范围内、不需要排除。 */
    @Test
    fun noLegacyDomainAnywhere() {
        val needle = "52j" + ".me"
        val textExt = setOf("kt", "java", "xml", "kts", "json", "md", "txt", "properties", "toml", "html")
        val files = modules.flatMap { m ->
            File(androidRoot(), "$m/src").walkTopDown().filter { it.isFile && it.extension in textExt }.toList() +
                listOf(File(androidRoot(), "$m/build.gradle.kts")).filter { it.isFile }
        }
        assertThat(files.size).isGreaterThan(50)
        val bad = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (line.contains(needle)) "${rel(f)}:${i + 1}" else null }
        }
        assertWithMessage("legacy domain still referenced:\n" + bad.joinToString("\n")).that(bad).isEmpty()
    }

    @Test
    fun `the exemption is the exact path - a same-named file elsewhere is not exempt`() {
        assertThat(allowedPaths).doesNotContain(rel(File(androidRoot(), "app/src/main/kotlin/x/MediaTransport.kt")))
        allowedPaths.forEach { assertThat(rel(File(androidRoot(), it))).isEqualTo(it) }
    }

    @Test
    fun `guard regex catches what it is meant to catch and nothing else`() {
        listOf(
            "val c = URL(u).openConnection() as HttpURLConnection",
            "val c = u.openConnection() as HttpsURLConnection",
            "val body = URL(\"https://x\").readText()",
            "val bytes = URL(u).readBytes()",
            "URL(u).openStream().use { }",
            "import okhttp3.OkHttpClient",
            "import okhttp3.*",
            "java.net.Socket(host, 443)",
            "import java.net.*",
            "import android.webkit.*",
            "val w = WebView(context)",
            "web.loadUrl(u)",
            "val dm = context.getSystemService(DownloadManager::class.java)",
            "import javax.net.ssl.SSLSocketFactory",
        ).forEach { assertWithMessage(it).that(networkApi.containsMatchIn(it)).isTrue() }
        listOf(
            "CustomTabsIntent.Builder().build()",
            "https://site.test/m/",
            "val text = file.readText()",
            "Uri.parse(url)",
        ).forEach { assertWithMessage(it).that(networkApi.containsMatchIn(it)).isFalse() }
    }

    /**
     * 生产依赖（非 test/androidTest 配置）里不许有 HTTP 客户端。直接读 `build.gradle.kts` + 版本目录，
     * 不跑 Gradle 依赖解析——保持这条测试毫秒级。
     */
    @Test
    fun `production dependencies contain no HTTP client library`() {
        val catalog = File(androidRoot(), "gradle/libs.versions.toml").readText()
        val aliasToModule = Regex("""^([A-Za-z0-9_-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(catalog).associate { it.groupValues[1].replace('-', '.').replace('_', '.') to it.groupValues[2] }
        val dep = Regex("""^\s*(\w+)\s*\(\s*(?:platform\()?\s*(libs\.[A-Za-z0-9_.]+|"[^"]+")""", RegexOption.MULTILINE)

        val production = modules.flatMap { m ->
            dep.findAll(File(androidRoot(), "$m/build.gradle.kts").readText())
                .filter { match ->
                    val conf = match.groupValues[1]
                    !conf.startsWith("test") && !conf.startsWith("androidTest") && conf != "ksp" && conf != "kapt"
                }
                .map { match ->
                    val ref = match.groupValues[2]
                    val coord = if (ref.startsWith("libs.")) aliasToModule[ref.removePrefix("libs.")] ?: ref else ref.trim('"')
                    "$m ${match.groupValues[1]} $coord"
                }
                .toList()
        }
        assertThat(production.size).isGreaterThan(10)
        val bad = production.filter { line -> httpClientModules.any { line.substringAfterLast(' ').startsWith(it) } }
        assertWithMessage("HTTP client in production dependencies:\n" + bad.joinToString("\n")).that(bad).isEmpty()
    }
}
