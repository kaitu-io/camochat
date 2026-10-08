package app.chencang.shared.media

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MediaFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files(free: Long = Long.MAX_VALUE) = MediaFiles(File(tmp.root, "media")) { free }

    @Test
    fun `layout is media slash message slash index dot cca or bin`() {
        val f = files()
        assertThat(f.cca("m-1", 0).path).endsWith("media/m-1/0.cca")
        assertThat(f.bin("m-1", 8).path).endsWith("media/m-1/8.bin")
    }

    @Test
    fun `message ids that could escape the media dir are rejected`() {
        val f = files()
        assertThrows(IllegalArgumentException::class.java) { f.dir("../identity") }
        assertThrows(IllegalArgumentException::class.java) { f.dir("a/b") }
        assertThrows(IllegalArgumentException::class.java) { f.dir("") }
    }

    @Test
    fun `write is atomic and leaves no tmp file`() {
        val f = files()
        val target = f.cca("m1", 0)
        f.write(target, byteArrayOf(1, 2, 3))
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(target.parentFile!!.list()!!.toList()).containsExactly("0.cca")
    }

    @Test
    fun `write refuses when free space is below payload plus margin`() {
        val f = files(free = MediaFiles.SPACE_MARGIN_BYTES + 2)
        assertThrows(StorageFullException::class.java) { f.write(f.cca("m1", 0), ByteArray(3)) }
        assertThat(f.cca("m1", 0).exists()).isFalse()
    }

    @Test
    fun `moveIn relocates a prepared file into the message dir`() {
        val f = files()
        val src = tmp.newFile("prep.jpg").apply { writeBytes(byteArrayOf(7, 7)) }
        f.moveIn(src, f.bin("m1", 0))
        assertThat(src.exists()).isFalse()
        assertThat(f.bin("m1", 0).readBytes()).isEqualTo(byteArrayOf(7, 7))
    }

    @Test
    fun `stageCopy copies without touching the source`() {
        val f = files()
        f.write(f.bin("m1", 0), byteArrayOf(5))
        val staged = f.stageCopy(f.bin("m1", 0))
        assertThat(staged.readBytes()).isEqualTo(byteArrayOf(5))
        assertThat(f.bin("m1", 0).exists()).isTrue()
    }

    @Test
    fun `deleteMessage removes one message dir and deleteAll removes everything`() {
        val f = files()
        f.write(f.bin("m1", 0), byteArrayOf(1))
        f.write(f.bin("m2", 0), byteArrayOf(2))
        f.deleteMessage("m1")
        assertThat(f.dir("m1").exists()).isFalse()
        assertThat(f.dir("m2").exists()).isTrue()
        f.deleteAll()
        assertThat(f.root.exists()).isFalse()
    }

    /**
     * A user must never be told "deleted" while plaintext media is still on
     * disk. Make the message dir's contents impossible to unlink (no write
     * permission on the containing dir — this is how POSIX enforces delete,
     * not the child file's own mode) and assert [MediaFiles.deleteMessage]
     * surfaces that as an [IOException] instead of swallowing
     * `deleteRecursively()`'s `false`.
     */
    @Test
    fun `deleteMessage throws when a file can't actually be removed`() {
        val f = files()
        val dir = f.dir("m1")
        dir.mkdirs()
        val child = File(dir, "0.bin").apply { writeBytes(byteArrayOf(1)) }
        check(dir.setWritable(false)) { "test setup: could not mark $dir read-only" }
        try {
            // Some JVM/user/CI-sandbox combinations (notably running as root)
            // ignore the write bit for unlink; skip there instead of
            // false-failing on an assumption this test can't control.
            assumeTrue(
                "this JVM/user does not enforce directory write permissions for delete",
                !child.delete(),
            )
            assertThrows(IOException::class.java) { f.deleteMessage("m1") }
        } finally {
            dir.setWritable(true) // let TemporaryFolder's own cleanup succeed
        }
    }

    @Test
    fun `deleteAll throws when a file can't actually be removed`() {
        val f = files()
        val dir = f.dir("m1")
        dir.mkdirs()
        val child = File(dir, "0.bin").apply { writeBytes(byteArrayOf(1)) }
        check(dir.setWritable(false)) { "test setup: could not mark $dir read-only" }
        try {
            assumeTrue(
                "this JVM/user does not enforce directory write permissions for delete",
                !child.delete(),
            )
            assertThrows(IOException::class.java) { f.deleteAll() }
        } finally {
            dir.setWritable(true)
        }
    }
}
