package de.batto.lacelink.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

public class CoreRfProtocolTest {
    @Test
    public void tightenPacketMatchesWireFormat() {
        byte[] message = CoreRfProtocol.request(
                CoreRfProtocol.OP_SERVO_MOVE,
                CoreRfProtocol.laceMovePayload(CoreRfProtocol.SERVO_SHORT_TIGHTEN));
        assertArrayEquals(new byte[]{0, 2, 0, 8, 1}, message);

        CoreRfProtocol.SegmentedMessage segmented = CoreRfProtocol.segment(message, 0);
        assertEquals(1, segmented.frames.size());
        assertArrayEquals(new byte[]{(byte) 0x80, 0, 2, 0, 8, 1}, segmented.frames.get(0));
    }

    @Test
    public void messageRoundTripPreservesActionAndPayload() {
        byte[] encoded = CoreRfProtocol.encodeMessage(81, CoreRfProtocol.ACTION_ACK,
                new byte[]{32, 87});
        CoreRfProtocol.Message decoded = CoreRfProtocol.parseMessage(encoded);
        assertNotNull(decoded);
        assertEquals(81, decoded.opcode);
        assertEquals(CoreRfProtocol.ACTION_ACK, decoded.action);
        assertEquals(Integer.valueOf(87), CoreRfProtocol.batteryPercent(decoded.payload));
    }

    @Test
    public void segmentedMessageCanBeReassembled() {
        byte[] payload = new byte[48];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }
        byte[] message = CoreRfProtocol.request(CoreRfProtocol.OP_PUBLIC_KEY, payload);
        List<byte[]> frames = CoreRfProtocol.segment(message, 61).frames;
        CoreRfProtocol.Reassembler reassembler = new CoreRfProtocol.Reassembler();
        byte[] result = null;
        for (byte[] frame : frames) {
            result = reassembler.accept(frame);
        }
        assertArrayEquals(message, result);
    }

    @Test
    public void invalidSequenceResetsGatherer() {
        CoreRfProtocol.Reassembler reassembler = new CoreRfProtocol.Reassembler();
        assertNull(reassembler.accept(new byte[]{1, 10}));
        assertNull(reassembler.accept(new byte[]{3, 11}));
    }
}
