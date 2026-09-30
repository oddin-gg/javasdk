package com.oddin.oddsfeedsdk.internal.cache;

import java.util.Set;

/**
 * An endpoint as a source of an entity's fields: the fields it is the authoritative source of, and
 * of those the shared ones it always sends when they exist, so that their absence means they are
 * gone. Each field of an entity has exactly one authoritative endpoint.
 *
 * @param name for messages, such as {@code competitor profile}
 */
public record Endpoint(String name, Set<Field<?>> authoritativeFor, Set<Field<?>> alwaysSent) {

    public Endpoint {
        authoritativeFor = Set.copyOf(authoritativeFor);
        alwaysSent = Set.copyOf(alwaysSent);
        for (Field<?> field : alwaysSent) {
            if (!authoritativeFor.contains(field)) {
                throw new IllegalArgumentException(
                        name + " always sends " + field + " but is not authoritative for it");
            }
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
