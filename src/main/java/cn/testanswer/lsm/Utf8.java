package cn.testanswer.lsm;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class Utf8 {
    private Utf8() { }

    static byte[] encode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text must not be null");
        }
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Text contains an unpaired Unicode surrogate", e);
        }
    }

    static String decode(byte[] bytes) throws CorruptStoreException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CorruptStoreException("Invalid UTF-8 in storage file", e);
        }
    }
}
