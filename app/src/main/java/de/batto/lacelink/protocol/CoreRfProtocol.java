package de.batto.lacelink.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Small clean-room implementation of the on-device CoreRF wire format used by
 * Adapt BB-era footwear. It intentionally contains no firmware/update commands.
 */
public final class CoreRfProtocol {
    public enum KeyExchangeSignal {
        OTHER,
        CONFIRMATION_REQUIRED,
        ALREADY_PAIRED,
        GROUP_ACCEPTED
    }

    public static final UUID SERVICE_UUID = UUID.fromString("1a2328af-3d0b-4b04-a2aa-973c239d3904");
    public static final UUID WRITE_UUID = UUID.fromString("226baea6-1543-40c2-8eae-a69b02171b08");
    public static final UUID NOTIFY_UUID = UUID.fromString("30c4142f-b083-42cf-865a-d5b91801bcd7");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    public static final int ACTION_REQUEST = 0;
    public static final int ACTION_ACK = 1;
    public static final int ACTION_NAK = 2;
    public static final int ACTION_EVENT = 3;

    public static final int OP_SERVO_MOVE = 0;
    public static final int OP_SET_POSITION = 3;
    public static final int OP_BATTERY = 81;
    public static final int OP_START_KEY_EXCHANGE = 110;
    public static final int OP_PUBLIC_KEY = 111;
    public static final int OP_START_AUTH = 112;
    public static final int OP_AUTH_CHALLENGE = 113;
    public static final int OP_LED_CLEAR = 216;
    public static final int OP_LED_SET_COLOR = 222;

    public static final int SERVO_SHORT_TIGHTEN = 1;
    public static final int SERVO_SHORT_LOOSEN = 3;
    public static final int SERVO_STOP = 8;

    private static final int MAX_MESSAGE_SIZE = 513;
    private static final int SEGMENT_DATA_SIZE = 19;

    private CoreRfProtocol() {
    }

    public static byte[] request(int opcode, byte[] payload) {
        return encodeMessage(opcode, ACTION_REQUEST, payload);
    }

    public static byte[] encodeMessage(int opcode, int action, byte[] payload) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        if (safePayload.length > MAX_MESSAGE_SIZE) {
            throw new IllegalArgumentException("Payload is too large");
        }
        int packedLength = (safePayload.length & 0x3fff) | ((action & 0x03) << 14);
        byte[] result = new byte[safePayload.length + 3];
        result[0] = (byte) opcode;
        result[1] = (byte) (packedLength & 0xff);
        result[2] = (byte) ((packedLength >>> 8) & 0xff);
        System.arraycopy(safePayload, 0, result, 3, safePayload.length);
        return result;
    }

    public static Message parseMessage(byte[] bytes) {
        if (bytes == null || bytes.length < 3) {
            return null;
        }
        int packedLength = (bytes[1] & 0xff) | ((bytes[2] & 0xff) << 8);
        int payloadLength = packedLength & 0x3fff;
        int action = (packedLength >>> 14) & 0x03;
        if (payloadLength > MAX_MESSAGE_SIZE || bytes.length < payloadLength + 3) {
            return null;
        }
        return new Message(bytes[0] & 0xff, action, Arrays.copyOfRange(bytes, 3, 3 + payloadLength));
    }

    /**
     * Classifies messages received while opcode 0x6E is pending. CoreRF uses a
     * 0x6F EVENT as a user-intervention prompt; it is not the MODP response.
     */
    public static KeyExchangeSignal classifyStartKeyExchangeMessage(Message message) {
        if (message == null) {
            return KeyExchangeSignal.OTHER;
        }
        boolean keyExchangeOpcode = message.opcode == OP_START_KEY_EXCHANGE
                || message.opcode == OP_PUBLIC_KEY;
        if (keyExchangeOpcode && message.action == ACTION_NAK) {
            return KeyExchangeSignal.ALREADY_PAIRED;
        }
        if (message.opcode == OP_PUBLIC_KEY && message.action == ACTION_EVENT) {
            return KeyExchangeSignal.CONFIRMATION_REQUIRED;
        }
        if (message.opcode == OP_START_KEY_EXCHANGE && message.action == ACTION_ACK) {
            return KeyExchangeSignal.GROUP_ACCEPTED;
        }
        return KeyExchangeSignal.OTHER;
    }

    public static SegmentedMessage segment(byte[] message, int initialSequence) {
        if (message == null || message.length == 0) {
            throw new IllegalArgumentException("Message must not be empty");
        }
        List<byte[]> frames = new ArrayList<>();
        int sequence = initialSequence & 0x3f;
        for (int offset = 0; offset < message.length; offset += SEGMENT_DATA_SIZE) {
            int count = Math.min(SEGMENT_DATA_SIZE, message.length - offset);
            byte[] frame = new byte[count + 1];
            frame[0] = (byte) sequence;
            System.arraycopy(message, offset, frame, 1, count);
            frames.add(frame);
            sequence = (sequence + 1) & 0x3f;
        }
        frames.get(frames.size() - 1)[0] |= (byte) 0x80;
        return new SegmentedMessage(Collections.unmodifiableList(frames), sequence);
    }

    public static boolean isFlowControl(byte[] frame) {
        return frame != null && frame.length >= 2 && (frame[0] & 0xc0) == 0xc0;
    }

    public static int sequenceOf(byte[] frame) {
        return frame[0] & 0x3f;
    }

    public static int flowControlType(byte[] frame) {
        return frame[1] & 0xff;
    }

    public static byte[] flowControlAck(int sequence) {
        return new byte[]{(byte) (0xc0 | (sequence & 0x3f)), 0};
    }

    public static byte[] laceMovePayload(int movement) {
        return protobufVarintField(1, movement);
    }

    public static byte[] positionPayload(int percent) {
        return protobufVarintField(1, clamp(percent, 0, 100));
    }

    public static byte[] modernColorPayload(int red, int green, int blue) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(output, protobufVarintField(1, 4)); // user-defined color slot
        write(output, protobufVarintField(2, clamp(red, 0, 255) * 256L));
        write(output, protobufVarintField(3, clamp(green, 0, 255) * 256L));
        write(output, protobufVarintField(4, clamp(blue, 0, 255) * 256L));
        return output.toByteArray();
    }

    public static byte[] protobufVarintField(int fieldNumber, long value) {
        if (fieldNumber <= 0 || value < 0) {
            throw new IllegalArgumentException("Invalid protobuf field");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) fieldNumber << 3));
        writeVarint(output, value);
        return output.toByteArray();
    }

    public static byte[] protobufBytesField(int fieldNumber, byte[] value) {
        if (fieldNumber <= 0 || value == null) {
            throw new IllegalArgumentException("Invalid protobuf bytes field");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) fieldNumber << 3) | 2);
        writeVarint(output, value.length);
        output.write(value, 0, value.length);
        return output.toByteArray();
    }

    public static Long readVarintField(byte[] payload, int wantedField) {
        if (payload == null) {
            return null;
        }
        int[] cursor = {0};
        while (cursor[0] < payload.length) {
            Long tag = readVarint(payload, cursor);
            if (tag == null) {
                return null;
            }
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                Long value = readVarint(payload, cursor);
                if (value == null) {
                    return null;
                }
                if (field == wantedField) {
                    return value;
                }
            } else if (!skipField(payload, cursor, wire)) {
                return null;
            }
        }
        return null;
    }

    public static byte[] readBytesField(byte[] payload, int wantedField) {
        if (payload == null) {
            return null;
        }
        int[] cursor = {0};
        while (cursor[0] < payload.length) {
            Long tag = readVarint(payload, cursor);
            if (tag == null) {
                return null;
            }
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 2) {
                Long lengthValue = readVarint(payload, cursor);
                if (lengthValue == null || lengthValue > Integer.MAX_VALUE) {
                    return null;
                }
                int length = lengthValue.intValue();
                if (length < 0 || cursor[0] + length > payload.length) {
                    return null;
                }
                byte[] value = Arrays.copyOfRange(payload, cursor[0], cursor[0] + length);
                cursor[0] += length;
                if (field == wantedField) {
                    return value;
                }
            } else if (!skipField(payload, cursor, wire)) {
                return null;
            }
        }
        return null;
    }

    public static Integer batteryPercent(byte[] payload) {
        Long value = readVarintField(payload, 4);
        return value == null ? null : clamp(value.intValue(), 0, 100);
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        }
        return result.toString();
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7fL) != 0) {
            output.write(((int) value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write((int) value);
    }

    private static Long readVarint(byte[] bytes, int[] cursor) {
        long result = 0;
        for (int shift = 0; shift < 64 && cursor[0] < bytes.length; shift += 7) {
            int value = bytes[cursor[0]++] & 0xff;
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                return result;
            }
        }
        return null;
    }

    private static boolean skipField(byte[] bytes, int[] cursor, int wire) {
        if (wire == 0) {
            return readVarint(bytes, cursor) != null;
        }
        if (wire == 1) {
            cursor[0] += 8;
        } else if (wire == 2) {
            Long length = readVarint(bytes, cursor);
            if (length == null || length > Integer.MAX_VALUE) {
                return false;
            }
            cursor[0] += length.intValue();
        } else if (wire == 5) {
            cursor[0] += 4;
        } else {
            return false;
        }
        return cursor[0] <= bytes.length;
    }

    private static void write(ByteArrayOutputStream output, byte[] value) {
        output.write(value, 0, value.length);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Message {
        public final int opcode;
        public final int action;
        public final byte[] payload;

        public Message(int opcode, int action, byte[] payload) {
            this.opcode = opcode;
            this.action = action;
            this.payload = payload == null ? new byte[0] : payload;
        }
    }

    public static final class SegmentedMessage {
        public final List<byte[]> frames;
        public final int nextSequence;

        public SegmentedMessage(List<byte[]> frames, int nextSequence) {
            this.frames = frames;
            this.nextSequence = nextSequence;
        }
    }

    public static final class Reassembler {
        private final ByteArrayOutputStream gathered = new ByteArrayOutputStream();
        private int expectedSequence = -1;

        public byte[] accept(byte[] frame) {
            if (frame == null || frame.length < 2 || isFlowControl(frame)) {
                return null;
            }
            int sequence = sequenceOf(frame);
            if (expectedSequence >= 0 && sequence != expectedSequence) {
                reset();
                return null;
            }
            gathered.write(frame, 1, frame.length - 1);
            expectedSequence = (sequence + 1) & 0x3f;
            if ((frame[0] & 0x80) == 0) {
                return null;
            }
            byte[] complete = gathered.toByteArray();
            reset();
            return complete;
        }

        public void reset() {
            gathered.reset();
            expectedSequence = -1;
        }
    }
}
