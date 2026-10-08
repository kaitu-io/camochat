package app.chencang.shared.pairing.inband

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PendingPairingStoreTest {
    @Test
    fun serializer_roundtrips_record_and_treats_empty_as_null() = runBlocking {
        val ser = PendingPairingRecordSerializer
        val rec = PendingPairingRecord("pid-xyz", "bm9uY2U=", 1718000000000L)
        val out = java.io.ByteArrayOutputStream()
        ser.writeTo(rec, out)
        val back = ser.readFrom(java.io.ByteArrayInputStream(out.toByteArray()))
        assertThat(back).isEqualTo(rec)
        // empty input → null sentinel
        assertThat(ser.readFrom(java.io.ByteArrayInputStream(ByteArray(0)))).isNull()
        // writing null → empty bytes → reads back null
        val nullOut = java.io.ByteArrayOutputStream(); ser.writeTo(null, nullOut)
        assertThat(ser.readFrom(java.io.ByteArrayInputStream(nullOut.toByteArray()))).isNull()
    }
}
