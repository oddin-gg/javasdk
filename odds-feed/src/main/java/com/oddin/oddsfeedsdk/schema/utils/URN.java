package com.oddin.oddsfeedsdk.schema.utils;

import com.oddin.oddsfeedsdk.exceptions.UnsupportedUrnFormatException;

import java.util.Objects;

public class URN {
    private final String prefix;
    private final String type;
    private final Long id;

    public static final String TypeMatch = "match";
    public static final String TypeTournament = "tournament";
    public static final String TypePlayer = "player";

    URN(String prefix, String type, Long id) {
        this.prefix = prefix;
        this.type = type;
        this.id = id;
    }

    // String.split drops trailing empty parts, so "od:match:1:" parses as 0.0.x parsed it
    @SuppressWarnings("StringSplitter")
    public static URN parse(String urnString) {
        String[] parts = urnString.split(":");
        if (parts.length != 3) {
            throw new UnsupportedUrnFormatException("URN could not be parsed " + urnString, null);
        }

        long id;
        try {
            id = Long.parseLong(parts[2]);
        } catch (Exception e) {
            throw new UnsupportedUrnFormatException("URN could not be parsed " + urnString, e);
        }

        return new URN(parts[0], parts[1], id);
    }

    public String getPrefix() {
        return prefix;
    }

    public String getType() {
        return type;
    }

    public Long getId() {
        return id;
    }

    // URN is not final: comparing classes keeps equals symmetric if a client subclasses it
    @SuppressWarnings("EqualsGetClass")
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        URN urn = (URN) o;
        return Objects.equals(prefix, urn.prefix) &&
                Objects.equals(type, urn.type) &&
                Objects.equals(id, urn.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix, type, id);
    }

    @Override
    public String toString() {
        return prefix + ":" + type + ":" + id;
    }
}
