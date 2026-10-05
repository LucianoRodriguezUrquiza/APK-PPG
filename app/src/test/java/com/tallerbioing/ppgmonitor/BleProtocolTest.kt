package com.tallerbioing.ppgmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProtocolTest {

    @Test
    fun parsesHelloContract() {
        val frame = BleProtocol.parse(
            "H:2,0123456789ABCDEF,2,B18,31,100,20,192,180"
        ) as? B18Frame.Hello

        assertNotNull(frame)
        assertEquals("0123456789ABCDEF", frame!!.value.boot)
        assertEquals(2L, frame.value.epoch)
        assertEquals("B18", frame.value.firmware)
        assertEquals(31, frame.value.capabilities)
    }

    @Test
    fun parsesUpdatedBpmAndKeepsISeparateFromN() {
        val frame = BleProtocol.parse(
            "B:078 A:0 C:4 P:084 I:25 T:123450 V:1 E:4 D:350 N:18"
        ) as? B18Frame.Bpm

        assertNotNull(frame)
        assertEquals(78, frame!!.value.bpm)
        assertEquals(25L, frame.value.sequence)
        assertEquals(18L, frame.value.bpmSequence)
        assertTrue(frame.value.visible)
    }

    @Test
    fun parsesUnavailableBpmWithoutInventingZero() {
        val frame = BleProtocol.parse(
            "B:NA A:3 C:0 P:NA I:27 T:128950 V:0 E:0 D:5850 N:18"
        ) as? B18Frame.Bpm

        assertNotNull(frame)
        assertNull(frame!!.value.bpm)
        assertNull(frame.value.battery)
        assertFalse(frame.value.visible)
        assertEquals(5850L, frame.value.ageMs)
    }

    @Test
    fun rejectsMalformedNullableBpm() {
        assertNull(
            BleProtocol.parse(
                "B:abc A:0 C:4 P:084 I:25 T:123450 V:0 E:0 D:350 N:18"
            )
        )
    }

    @Test
    fun parsesValidAndInvalidPpg() {
        val valid = BleProtocol.parse(
            "S:240,123450,-123.4,1"
        ) as? B18Frame.Ppg

        assertNotNull(valid)
        assertTrue(valid!!.value.valid)
        assertEquals(123450L, valid.value.sampleTimeMs)
        assertEquals(-123.4f, valid.value.value!!, 0.001f)

        val invalid = BleProtocol.parse(
            "S:241,NA,NA,0"
        ) as? B18Frame.Ppg

        assertNotNull(invalid)
        assertFalse(invalid!!.value.valid)
        assertNull(invalid.value.sampleTimeMs)
        assertNull(invalid.value.value)
    }

    @Test
    fun parsesSpo2AndPrv() {
        val spo2 = BleProtocol.parse(
            "O:12,123450,98.0,1,450,0"
        ) as? B18Frame.Spo2

        assertNotNull(spo2)
        assertTrue(spo2!!.value.valid)
        assertEquals(98.0f, spo2.value.value!!, 0.001f)

        val prv = BleProtocol.parse(
            "R:3,123450,1,250,58000,48,50,45,96,1000.0,25.2,31.4,6.7,1,0,0"
        ) as? B18Frame.Prv

        assertNotNull(prv)
        assertTrue(prv!!.value.valid)
        assertEquals(25.2f, prv.value.rmssdMs!!, 0.001f)
        assertEquals(false, prv.value.flag)
    }

    @Test
    fun assemblerReconstructsFragmentedLine() {
        val assembler = B18LineAssembler()

        assertTrue(
            assembler.offer("H:2,0123456789ABC".toByteArray()).isEmpty()
        )

        val events = assembler.offer(
            "DEF,2,B18,31,100,20,192,180\n".toByteArray()
        )

        val line = events.single() as B18AssemblerEvent.Line
        assertEquals(
            "H:2,0123456789ABCDEF,2,B18,31,100,20,192,180",
            line.value
        )
    }

    @Test
    fun assemblerAcceptsMultipleLinesInOneNotification() {
        val assembler = B18LineAssembler()

        val events = assembler.offer(
            (
                "S:240,123450,-123.4,1\n" +
                    "S:241,123500,-100.0,1\n"
                ).toByteArray()
        )

        assertEquals(2, events.size)
        assertTrue(events.all { it is B18AssemblerEvent.Line })
    }

    @Test
    fun assemblerResyncMarkerDropsPartial() {
        val assembler = B18LineAssembler()

        assembler.offer("R:3,123".toByteArray())
        val events = assembler.offer("!\n".toByteArray())

        assertEquals(1, events.size)
        assertTrue(events.single() is B18AssemblerEvent.Resync)
        assertFalse(assembler.hasPartial)
    }

    @Test
    fun unsignedMillisDeltaHandlesWrap() {
        val beforeWrap = 0xFFFF_FFF0L
        val afterWrap = 0x0000_0010L

        assertEquals(
            32L,
            BleProtocol.u32Delta(afterWrap, beforeWrap)
        )
    }
}
