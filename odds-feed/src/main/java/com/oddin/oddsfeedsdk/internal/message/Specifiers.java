package com.oddin.oddsfeedsdk.internal.message;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A market's specifiers: a few {@code name=value} pairs in the feed's order, kept in one array. A message
 * builds one for every market, and most carry one to three pairs, for which a linked hash map is the larger part
 * of what the message allocates; the array is searched, so it holds few ({@link #MOST}) and the rest are kept
 * in a map. Read-only; safe for concurrent use.
 */
final class Specifiers extends AbstractMap<String, String> {

    /** The most pairs kept in the array. */
    static final int MOST = 16;

    /** Name, value, name, value, ...: as many as the pairs. */
    private final String[] pairs;

    /** @param pairs the names and values in turn, which this keeps and the caller no longer changes */
    Specifiers(String[] pairs) {
        this.pairs = pairs;
    }

    @Override
    public int size() {
        return pairs.length / 2;
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        return indexOf(key) >= 0;
    }

    @Override
    public @Nullable String get(@Nullable Object key) {
        int at = indexOf(key);
        return at < 0 ? null : pairs[at + 1];
    }

    private int indexOf(@Nullable Object key) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return pairs.length / 2;
            }

            @Override
            public Iterator<Entry<String, String>> iterator() {
                return new Iterator<>() {
                    private int next;

                    @Override
                    public boolean hasNext() {
                        return next < pairs.length;
                    }

                    @Override
                    public Entry<String, String> next() {
                        if (next >= pairs.length) {
                            throw new NoSuchElementException();
                        }
                        var entry = Map.entry(pairs[next], pairs[next + 1]);
                        next += 2;
                        return entry;
                    }
                };
            }
        };
    }
}
