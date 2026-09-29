package cn.testanswer.lsm;

final class Json {
    private Json() { }

    static String quote(String value) {
        if (value == null) { return "null"; }
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    static String stats(LsmStore.Stats stats) {
        return "{\"lastSequence\":" + stats.lastSequence() + ",\"checkpoint\":" + stats.checkpoint()
                + ",\"memTableEntries\":" + stats.memTableEntries() + ",\"memTableBytes\":" + stats.memTableBytes()
                + ",\"walBytes\":" + stats.walBytes() + ",\"sstableCount\":" + stats.sstableCount()
                + ",\"sstableEntries\":" + stats.sstableEntries() + ",\"sstableBytes\":" + stats.sstableBytes() + "}";
    }
}
