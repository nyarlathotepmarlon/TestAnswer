package cn.testanswer.lsm;

import java.io.IOException;
import java.util.Iterator;

interface EntryStream extends AutoCloseable {
    /** 返回下一条记录，耗尽时返回 null。 */
    Entry next() throws IOException;

    @Override
    default void close() throws IOException { }

    static EntryStream of(Iterable<Entry> entries) {
        Iterator<Entry> iterator = entries.iterator();
        return () -> iterator.hasNext() ? iterator.next() : null;
    }
}
