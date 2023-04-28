package com.oddin.oddsfeedsdk.schema.utils;

import com.oddin.oddsfeedsdk.exceptions.UnsupportedUrnFormatException;

import java.util.Objects;

public class URN {
    final private String prefix;
    final private String type;
    final private Long id;

    static public final String TypeMatch = "match";
    static public final String TypeTournament = "tournament";

    URN(String prefix, String type, Long id) {
        this.prefix = prefix;
        this.type = type;
        this.id = id;
    }

    public static URN parse(String urnString) {
        String[] parts = urnString.split(":");
        if (parts.length != 3) {
            throw new UnsupportedUrnFormatException("URN could not be parsed " + urnString, null);
        }

        long id;
        try {
            id = Long.parseLong(parts[2]);
        } catch (Exception e) {
            throw new UnsupportedUrnFormatException("URN could not be parsed [$urnString] ", e);
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