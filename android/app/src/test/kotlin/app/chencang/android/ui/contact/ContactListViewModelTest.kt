package app.chencang.android.ui.contact

import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingKind
import app.chencang.shared.pairing.inband.PendingPairingRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private fun contact(name: String, fp: String = "fp-$name") = Contact(
    fingerprintHex = fp, username = fp, displayName = name, pairedAt = 0L,
)

/** Cross-platform case table S. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 注意:JVM 单测里 `Collator(Locale.CHINA)` 是 JDK 的实现,与设备上的 ICU 在「汉字 vs 拉丁字母」的相对先后上相反
     * (JDK:字母在前;设备 ICU:汉字在前,如 `阿青, 老周, 联系人…, 张三, A, Bob`)。所以这里只断言**类内**顺序
     * (汉字按拼音、字母大小写不敏感),不断言跨类先后;跨类顺序以真机 ICU 为准。产品代码的排序不动。
     */
    @Test
    fun `sorts by table S within each class`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(listOf("张三", "alice", "老周", "Bob", "阿青", "联系人 a1b2c3").map { contact(it) })
        val vm = ContactListViewModel(contacts, locale = Locale.CHINA)
        val rows = vm.rows.filter { it.isNotEmpty() }.first()
        val names = rows.map { it.displayName }
        assertEquals(listOf("阿青", "老周", "联系人 a1b2c3", "张三"), names.filter { it.first().code > 0x2E80 })
        assertEquals(listOf("alice", "Bob"), names.filter { it.first().code < 0x80 })
    }

    /** Latin ordering is case-insensitive and accent-aware under the given locale. */
    @Test
    fun `sorting follows the given locale`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(listOf("bob", "Alice", "Émile").map { contact(it) })
        val rows = ContactListViewModel(contacts, locale = Locale.US).rows.filter { it.isNotEmpty() }.first()
        assertEquals(listOf("Alice", "bob", "Émile"), rows.map { it.displayName })
    }

    /** Pinyin order: 李 (li) before 张 (zhang). This runs on the JDK Collator, not the device ICU, and only asserts what holds for both. */
    @Test
    fun `chinese locale sorts by pinyin`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(listOf("张三", "李四").map { contact(it) })
        val rows = ContactListViewModel(contacts, locale = Locale.SIMPLIFIED_CHINESE).rows.filter { it.isNotEmpty() }.first()
        assertEquals(listOf("李四", "张三"), rows.map { it.displayName })
    }

    @Test
    fun `same name falls back to fingerprint order`() = runTest(dispatcher) {
        val bb = "bb".repeat(16)
        val aa = "aa".repeat(16)
        val contacts = MutableStateFlow(listOf(contact("老周", bb), contact("老周", aa)))
        val vm = ContactListViewModel(contacts, locale = Locale.CHINA)
        val rows = vm.rows.filter { it.isNotEmpty() }.first()
        assertEquals(listOf(aa, bb), rows.map { it.fingerprintHex })
    }

    private val t = 1_000_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun invite(id: String, createdAgo: Long, shared: Boolean) = PendingPairingRecord(
        pairingId = id, pairingNonceB64 = "", createdAtMillis = t - createdAgo, inviteWire = "🔒$id",
        lastSharedAtMillis = if (shared) t else null,
    )

    private fun response(fp: String, createdAgo: Long, shared: Boolean) = PairingResponseRecord(
        fingerprintHex = fp, responseWire = "🔒r-$fp", inviteDigest = "d-$fp", createdAtMillis = t - createdAgo,
        lastSharedAtMillis = if (shared) t else null,
    )

    /** 跨端用例表 K。 */
    @Test
    fun `pending follows table K and badge is 1`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(listOf(contact("甲", "r"), contact("乙", "r2")))
        val invites = MutableStateFlow(
            listOf(invite("A", 5 * minute, false), invite("B", 3 * day, true), invite("C", 31 * day, true)),
        )
        val responses = MutableStateFlow(
            listOf(response("r", minute, false), response("r2", minute, true), response("r3", minute, false)),
        )
        val vm = ContactListViewModel(contacts, invites, responses, now = { t })
        val items = vm.pending.filter { it.isNotEmpty() }.first()
        // A never went out: not listed.
        assertEquals(listOf("r", "B", "C"), items.map { it.id })
        assertEquals(
            listOf(PendingKind.ResponseUnsent, PendingKind.InviteAwaiting, PendingKind.InviteAwaiting),
            items.map { it.kind },
        )
        assertEquals(listOf(false, false, true), items.map { it.isStale })
        assertEquals(1, vm.badge.filter { it > 0 }.first())
    }

    @Test
    fun `contact with unsent response is not in rows`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(listOf(contact("阿青", "fp-1"), contact("老周", "fp-2"), contact("张三", "fp-3")))
        val responses = MutableStateFlow(listOf(response("fp-2", minute, false)))
        val vm = ContactListViewModel(contacts, flowOf(emptyList()), responses, now = { t })
        assertEquals(listOf("fp-1", "fp-3"), vm.rows.filter { it.isNotEmpty() }.first().map { it.fingerprintHex })

        // 回应发出去之后这位联系人回到列表里。
        responses.value = listOf(response("fp-2", minute, true))
        assertEquals(listOf("fp-1", "fp-2", "fp-3"), vm.rows.filter { it.size == 3 }.first().map { it.fingerprintHex })
    }

    @Test
    fun `badge counts only unsent responses`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(listOf(contact("甲", "r")))
        val responses = MutableStateFlow(listOf(response("r", minute, false)))
        val invites = MutableStateFlow(listOf(invite("A", 5 * minute, false), invite("B", 5 * minute, true)))
        val vm = ContactListViewModel(contacts, invites, responses, now = { t })
        assertEquals(1, vm.badge.filter { it > 0 }.first())
        responses.value = listOf(response("r", minute, true))
        assertEquals(0, vm.badge.filter { it == 0 }.first())
    }
}
