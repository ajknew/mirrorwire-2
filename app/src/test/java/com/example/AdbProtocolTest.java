package com.example;

import com.example.adb.AdbMessage;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying ADB packet structure, serialization, checksums, and validation.
 */
public class AdbProtocolTest {

    @Test
    public void testAdbHeaderSerializationAndParsing() {
        byte[] payload = "shell:screenrecord -\0".getBytes(StandardCharsets.UTF_8);
        AdbMessage original = AdbMessage.create(AdbMessage.A_OPEN, 1, 0, payload);

        assertEquals(AdbMessage.A_OPEN, original.getCommand());
        assertEquals(1, original.getArg0());
        assertEquals(0, original.getArg1());
        assertEquals(payload.length, original.getDataLength());
        assertEquals(original.getCommand() ^ 0xFFFFFFFF, original.getMagic());

        byte[] headerBytes = original.serializeHeader();
        assertEquals(AdbMessage.HEADER_LENGTH, headerBytes.length);

        AdbMessage parsed = AdbMessage.parseHeader(headerBytes);
        assertNotNull(parsed);
        assertEquals(AdbMessage.A_OPEN, parsed.getCommand());
        assertEquals(1, parsed.getArg0());
        assertEquals(0, parsed.getArg1());
        assertEquals(payload.length, parsed.getDataLength());
        assertEquals(original.getDataCheck(), parsed.getDataCheck());
        assertEquals(original.getMagic(), parsed.getMagic());

        // Validate with payload attached
        parsed.setData(payload);
        assertTrue(parsed.validate());
    }

    @Test
    public void testAdbChecksumValidation() {
        byte[] data = new byte[] { 10, 20, 30, 40, (byte) 200 };
        int expectedChecksum = 10 + 20 + 30 + 40 + 200; // 300
        assertEquals(expectedChecksum, AdbMessage.calculateChecksum(data));

        AdbMessage msg = AdbMessage.create(AdbMessage.A_WRTE, 1, 2, data);
        assertTrue(msg.validate());

        // Corrupt payload
        byte[] corruptedData = new byte[] { 10, 20, 30, 40, (byte) 201 };
        msg.setData(corruptedData);
        assertFalse(msg.validate());
    }

    @Test
    public void testCommandNames() {
        assertEquals("CNXN", AdbMessage.createCnxn(4096, "host::\0").getCommandName());
        assertEquals("OPEN", AdbMessage.createOpen(1, "shell:ls\0").getCommandName());
        assertEquals("OKAY", AdbMessage.createOkay(1, 2).getCommandName());
        assertEquals("CLSE", AdbMessage.createClse(1, 2).getCommandName());
        assertEquals("AUTH", AdbMessage.createAuth(1, new byte[20]).getCommandName());
    }
}
