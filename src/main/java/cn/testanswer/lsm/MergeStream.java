package cn.testanswer.lsm;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** K 路归并，只保留同 key 最大 sequence；每个输入只缓存一条记录。 */
final class MergeStream implements EntryStream {
    private record Head(Entry entry, EntryStream source) { }

    private final List<EntryStream> sources;
    private final PriorityQueue<Head> queue = new PriorityQueue<>(Comparator
            .comparing((Head head) -> head.entry().key())
            .thenComparing(Comparator.comparingLong((Head head) -> head.entry().sequence()).reversed()));
    private final boolean discardTombstones;

    MergeStream(List<EntryStream> sources, boolean discardTombstones) throws IOException {
        this.sources = List.copyOf(sources);
        this.discardTombstones = discardTombstones;
        try {
            for (EntryStream source : sources) {
                advance(source);
            }
        } catch (IOException | RuntimeException e) {
            try {
                close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private void advance(EntryStream source) throws IOException {
        Entry entry = source.next();
        if (entry != null) {
            queue.add(new Head(entry, source));
        }
    }

    @Override
    public Entry next() throws IOException {
        while (!queue.isEmpty()) {
            Head head = queue.remove();
            Entry latest = head.entry();
            advance(head.source());
            while (!queue.isEmpty() && queue.peek().entry().key().equals(latest.key())) {
                Head duplicate = queue.remove();
                if (duplicate.entry().sequence() > latest.sequence()) {
                    latest = duplicate.entry();
                }
                advance(duplicate.source());
            }
            if (!discardTombstones || !latest.deleted()) {
                return latest;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (EntryStream source : sources) {
            try {
                source.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        queue.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
