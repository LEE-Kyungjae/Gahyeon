package com.gahyeonbot.adapters.discord.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WavEncoderTest {
    @Test
    void convertsBigEndian16BitPcmToLittleEndian() {
        byte[] bigEndian = {0x12, 0x34, (byte) 0xFE, (byte) 0xDC};

        byte[] littleEndian = WavEncoder.bigEndianToLittleEndian(bigEndian);

        assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xDC, (byte) 0xFE}, littleEndian);
        assertArrayEquals(new byte[]{0x12, 0x34, (byte) 0xFE, (byte) 0xDC}, bigEndian);
    }

    @Test
    void rejectsIncomplete16BitSample() {
        assertThrows(IllegalArgumentException.class,
                () -> WavEncoder.bigEndianToLittleEndian(new byte[]{0x01}));
    }
}
