package app.chencang.android.ui.chat

import app.chencang.shared.R
import app.chencang.shared.chat.ChatMessage
import app.chencang.shared.chat.MediaItem
import app.chencang.shared.chat.MessagePreview
import app.chencang.shared.i18n.UiText
import app.chencang.shared.media.MediaConstants
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingPairingRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private fun contact(u: String, at: Long = 0L) = Contact(
    fingerprintHex = "fp-$u", username = u, displayName = u, pairedAt = at,
)

private fun invite(id: String, sharedAt: Long?) = PendingPairingRecord(
    pairingId = id, pairingNonceB64 = "AAAA", createdAtMillis = 1L, inviteWire = "w", lastSharedAtMillis = sharedAt,
)

private fun vm(
    contacts: Flow<List<Contact>> = MutableStateFlow(emptyList()),
    latest: Flow<List<ChatMessage>> = MutableStateFlow(emptyList()),
    media: Flow<List<MediaItem>> = MutableStateFlow(emptyList()),
    invites: Flow<List<PendingPairingRecord>> = MutableStateFlow(emptyList()),
    itemCounts: Flow<Map<String, Int>> = MutableStateFlow(emptyMap()),
    responses: Flow<List<PairingResponseRecord>> = MutableStateFlow(emptyList()),
    ccaExists: (String, Int) -> Boolean = { _, _ -> true },
) = ConversationListViewModel(contacts, latest, MutableStateFlow(false), media, invites, itemCounts, responses, ccaExists)

private fun response(u: String, sharedAt: Long?) = PairingResponseRecord(
    fingerprintHex = "fp-$u", responseWire = "r", inviteDigest = "d", createdAtMillis = 1L, lastSharedAtMillis = sharedAt,
)

private fun build(
    contacts: List<Contact>,
    latest: List<ChatMessage> = emptyList(),
    responses: List<PairingResponseRecord> = emptyList(),
) = ConversationListViewModel.buildRows(contacts, latest, emptyList(), emptyMap(), responses) { _, _ -> true }

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rows include contacts without messages but not leftovers of deleted contacts`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(listOf(contact("alice", at = 1L), contact("bob", at = 2L)))
        val latest = MutableStateFlow(
            listOf(
                ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "早", 100L),
                // 联系人已删但消息还在库里的残留：不出行。
                ChatMessage("g", "ghost", ChatMessage.DIRECTION_IN, "?", 300L),
            ),
        )
        val vm = vm(contacts, latest)
        val rows = vm.rows.filter { it.isNotEmpty() }.first()
        assertEquals(listOf("alice", "bob"), rows.map { it.contact.username })
        assertEquals("早", rows[0].last!!.body)
        assertNull(rows[1].last)
    }

    @Test
    fun `rows are empty when the only contact still owes its reply`() = runTest(dispatcher) {
        val vm = vm(
            contacts = MutableStateFlow(listOf(contact("alice", at = 5L))),
            responses = MutableStateFlow(listOf(response("alice", sharedAt = null))),
        )
        val seen = mutableListOf<List<ConversationListViewModel.Row>>()
        val job = backgroundScope.launch { vm.rows.collect { seen += it } }
        assertEquals(listOf(emptyList<ConversationListViewModel.Row>()), seen)
        job.cancel()
    }

    @Test
    fun `rows sorted by latest timestamp descending`() = runTest(dispatcher) {
        // pairedAt 与时间戳反着来：有消息的行只看最新一条消息的时间。
        val contacts = MutableStateFlow(listOf(contact("alice", at = 90L), contact("bob", at = 1L), contact("carol", at = 50L)))
        val latest = MutableStateFlow(
            listOf(
                ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "早", 100L),
                ChatMessage("b", "bob", ChatMessage.DIRECTION_OUT, "晚", 300L),
                ChatMessage("c", "carol", ChatMessage.DIRECTION_IN, "中", 200L),
            ),
        )
        val vm = vm(contacts, latest)
        val rows = vm.rows.filter { it.isNotEmpty() }.first()
        assertEquals(listOf("bob", "carol", "alice"), rows.map { it.contact.username })
        assertEquals(listOf(300L, 200L, 100L), rows.map { it.last!!.timestamp })
    }

    @Test
    fun `zero message rows sort by pairing time among message rows, unknown pairing time last`() {
        val rows = build(
            contacts = listOf(
                contact("old", at = 0L), // 配对时间不知道：排最后、不显示时间
                contact("alice", at = 1L),
                contact("fresh", at = 250L),
                contact("bob", at = 2L),
            ),
            latest = listOf(
                ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "早", 100L),
                ChatMessage("b", "bob", ChatMessage.DIRECTION_IN, "晚", 300L),
            ),
        )
        assertEquals(listOf("bob", "fresh", "alice", "old"), rows.map { it.contact.username })
        assertEquals(listOf(300L, 250L, 100L, null), rows.map { it.timeMillis })
    }

    @Test
    fun `zero message row subtitle waits for the peer only when I accepted their invite`() {
        val rows = build(
            contacts = listOf(contact("acc", at = 2L).copy(acceptedInviteDigest = "d"), contact("init", at = 1L)),
        ).associateBy { it.contact.username }
        assertEquals(R.string.conversations_preview_waiting_peer, rows["acc"]!!.placeholderRes)
        assertEquals(R.string.conversations_preview_no_messages, rows["init"]!!.placeholderRes)
        assertNull(rows["acc"]!!.unsentPrefixRes)
    }

    @Test
    fun `message row has no placeholder`() {
        val row = build(
            contacts = listOf(contact("alice").copy(acceptedInviteDigest = "d")),
            latest = listOf(ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "hi", 100L)),
        ).single()
        assertNull(row.placeholderRes)
        assertEquals(100L, row.timeMillis)
    }

    @Test
    fun `accepter whose reply is unsent is left out until it has messages`() {
        val contacts = listOf(contact("owes", at = 9L), contact("sent", at = 8L))
        val responses = listOf(response("owes", sharedAt = null), response("sent", sharedAt = 3L))
        assertEquals(listOf("sent"), build(contacts, responses = responses).map { it.contact.username })
        // 已经有消息了：照常列出（排除只针对没消息的行）。
        val msg = ChatMessage("o", "owes", ChatMessage.DIRECTION_IN, "hi", 100L)
        assertEquals(
            listOf("owes", "sent"),
            build(contacts, latest = listOf(msg), responses = responses).map { it.contact.username },
        )
    }

    private fun image(msg: String, index: Int, state: String) = MediaItem(
        messageId = msg, index = index, kind = MediaConstants.KIND_IMAGE, durMs = 0, width = 1, height = 1,
        byteLen = 1L, blobSecret = ByteArray(32), blobId = "b", state = state,
    )

    @Test
    fun `预览在最后一条对方还看不到时以未上传开头,永久失败与已上传不加`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(listOf(contact("alice"), contact("bob"), contact("carol"), contact("dave")))
        fun out(id: String, peer: String) = ChatMessage(
            id, peer, ChatMessage.DIRECTION_OUT, "[图片]", 100L, kind = ChatMessage.KIND_IMAGE, shareText = "🔒 x",
        )
        val latest = MutableStateFlow(listOf(out("a", "alice"), out("b", "bob"), out("c", "carol"), out("d", "dave")))
        val media = MutableStateFlow(
            listOf(
                image("a", 0, MediaItem.STATE_SEALED), image("a", 1, MediaItem.STATE_FAILED),
                image("b", 0, MediaItem.STATE_FAILED),
                image("d", 0, MediaItem.STATE_FAILED).copy(uploadFailure = "TOO_LARGE"),
            ),
        )
        val vm = vm(contacts, latest, media, ccaExists = { id, _ -> id != "b" })
        val rows = vm.rows.filter { it.isNotEmpty() }.first().associateBy { it.contact.username }
        assertEquals(R.string.media_unsent_prefix, rows["alice"]!!.unsentPrefixRes)
        assertNull(rows["bob"]!!.unsentPrefixRes) // 文件丢失：重传不了，不催
        assertNull(rows["carol"]!!.unsentPrefixRes) // 没有失败项
        assertNull(rows["dave"]!!.unsentPrefixRes) // 文件太大（.cca 还在）：永久失败，不催
    }

    @Test
    fun `hasContactsOrPending is unknown until read, then follows contacts`() = runTest(dispatcher) {
        val contacts = MutableStateFlow(emptyList<Contact>())
        val vm = vm(contacts = contacts)
        assertNull(vm.hasContactsOrPending.value) // 还没订阅、没读出来：未知，不是 false
        val job = backgroundScope.launch { vm.hasContactsOrPending.collect { } }
        assertEquals(false, vm.hasContactsOrPending.value)
        contacts.value = listOf(contact("alice"))
        assertEquals(true, vm.hasContactsOrPending.value)
        job.cancel()
    }

    @Test
    fun `unshared invite does not count as pending`() = runTest(dispatcher) {
        val vm = vm(invites = MutableStateFlow(listOf(invite("p1", sharedAt = null))))
        val job = backgroundScope.launch { vm.hasContactsOrPending.collect { } }
        assertEquals(false, vm.hasContactsOrPending.value)
        job.cancel()
    }

    @Test
    fun `shared invite counts as pending`() = runTest(dispatcher) {
        val vm = vm(invites = MutableStateFlow(listOf(invite("p1", sharedAt = null), invite("p2", sharedAt = 5L))))
        val job = backgroundScope.launch { vm.hasContactsOrPending.collect { } }
        assertEquals(true, vm.hasContactsOrPending.value)
        job.cancel()
    }

    @Test
    fun `unverified contact still shows the last message preview`() = runTest(dispatcher) {
        val alice = contact("alice").copy(verified = false)
        val msg = ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "hello", 100L)
        val vm = vm(MutableStateFlow(listOf(alice)), MutableStateFlow(listOf(msg)))
        val row = vm.rows.filter { it.isNotEmpty() }.first().single()
        assertEquals(msg, row.last)
        assertEquals("hello", (MessagePreview.of(row.last!!, row.itemCount) as UiText.Raw).value)
    }

    @Test
    fun `unsent media prefix is a resource id`() = runTest(dispatcher) {
        val msg = ChatMessage(
            "a", "alice", ChatMessage.DIRECTION_OUT, "", 100L, kind = ChatMessage.KIND_IMAGE, shareText = "x",
        )
        val vm = vm(
            MutableStateFlow(listOf(contact("alice"))),
            MutableStateFlow(listOf(msg)),
            MutableStateFlow(listOf(image("a", 0, MediaItem.STATE_FAILED))),
        )
        val row = vm.rows.filter { it.isNotEmpty() }.first().single()
        assertEquals(R.string.media_unsent_prefix, row.unsentPrefixRes)
    }

    @Test
    fun `album row carries its item count so the preview reads as several photos`() = runTest(dispatcher) {
        val album = ChatMessage("a", "alice", ChatMessage.DIRECTION_IN, "", 100L, kind = ChatMessage.KIND_IMAGE)
        val single = ChatMessage("b", "bob", ChatMessage.DIRECTION_IN, "", 90L, kind = ChatMessage.KIND_IMAGE)
        val vm = vm(
            MutableStateFlow(listOf(contact("alice"), contact("bob"))),
            MutableStateFlow(listOf(album, single)),
            itemCounts = MutableStateFlow(mapOf("a" to 3)),
        )
        val rows = vm.rows.filter { it.isNotEmpty() }.first().associateBy { it.contact.username }
        assertEquals(3, rows["alice"]!!.itemCount)
        assertEquals(1, rows["bob"]!!.itemCount)
        assertEquals(
            UiText.Plural(R.plurals.media_preview_images, 3),
            MessagePreview.of(rows["alice"]!!.last!!, rows["alice"]!!.itemCount),
        )
    }
}
