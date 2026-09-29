package cn.testanswer.lsm;

/** value=null 是删除标记；空字符串是合法的值。 */
record Entry(long sequence, String key, String value) {
    boolean deleted() {
        return value == null;
    }

    long encodedSize() {
        return Frames.HEADER_BYTES + RecordCodec.FIXED_BYTES + Utf8.encode(key).length
                + (deleted() ? 0 : Utf8.encode(value).length);
    }
}
