package com.oddin.oddsfeedsdk.internal.catalog;

import static java.util.Objects.requireNonNullElse;

import com.github.benmanes.caffeine.cache.Ticker;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.internal.rest.Deadline;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAMarketVoidReasons;
import jakarta.xml.bind.JAXBElement;
import java.io.Serializable;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * The void reasons: one list, in no locale, fetched whole, refreshed after {@link #REFRESH_AGE}
 * and served while a refresh runs and for as long as refreshes fail; see {@link Catalog}. A
 * refresh replaces the list; an id missing from it fetches the list again once.
 *
 * <p>Safe for concurrent use.
 */
public final class VoidReasons {

    /** How old the list gets before a read refreshes it. */
    static final Duration REFRESH_AGE = Duration.ofHours(1);

    private final ApiClient client;
    private final Catalog<Whole, Listed> list;

    /**
     * @param timeout the HTTP client timeout, each fetch's deadline
     * @param fetches where the fetches run: virtual threads
     */
    public VoidReasons(ApiClient client, Duration timeout, Executor fetches) {
        this(client, timeout, fetches, fetches, InstantSource.system(), Ticker.systemTicker());
    }

    /** With the clocks a test drives, and where it runs the background refreshes. */
    VoidReasons(
            ApiClient client,
            Duration timeout,
            Executor fetches,
            Executor refreshes,
            InstantSource clock,
            Ticker ticker) {
        this.client = client;
        this.list = new Catalog<>(
                "void reasons",
                1,
                REFRESH_AGE,
                this::fetchList,
                timeout,
                fetches,
                refreshes,
                clock,
                ticker,
                client::isClosed);
    }

    /**
     * Every void reason, by id, as 0.0.x listed them.
     *
     * @throws ApiException when the list is not held and cannot be fetched
     */
    public List<VoidReason> all() {
        return list.get(Whole.LIST).reasons();
    }

    /**
     * The void reason {@code id}, or null when the list does not have it.
     *
     * @throws ApiException when the list is not held and cannot be fetched
     */
    public @Nullable VoidReason reason(int id) {
        return list.find(Whole.LIST, id, (listed, wanted) -> listed.byId().get(wanted));
    }

    /**
     * The list fetched now, whatever is held; what was held stays when the fetch fails.
     *
     * @throws ApiException when the fetch fails
     */
    public List<VoidReason> reload() {
        return list.reload(Whole.LIST).reasons();
    }

    /**
     * Fetches the list unless it is held, for no reader: a failure backs off no read.
     *
     * @throws ApiException when the fetch fails
     */
    public void preload() {
        list.preload(Whole.LIST);
    }

    /** Drops the list. */
    public void clear() {
        list.clear();
    }

    public CatalogHealth health() {
        return list.health();
    }

    /** The list; an empty one does not replace one with reasons in it, and counts as failed. */
    private Listed fetchList(Whole whole, @Nullable Listed previous, Deadline deadline) {
        var reasons = new ArrayList<VoidReason>();
        for (RAMarketVoidReasons.VoidReason reason :
                client.fetchMarketVoidReasons(deadline).getVoidReason()) {
            reasons.add(new VoidReason(
                    reason.getId(),
                    requireNonNullElse(reason.getName(), ""),
                    requireNonNullElse(reason.getDescription(), ""),
                    reason.getTemplate(),
                    params(reason.getContent())));
        }
        if (reasons.isEmpty() && previous != null && !previous.reasons().isEmpty()) {
            throw new ApiException(
                    "void reasons: an empty list does not replace one of "
                            + previous.reasons().size(),
                    null,
                    null);
        }
        reasons.sort(Comparator.comparingInt(VoidReason::id));
        var byId = new HashMap<Integer, VoidReason>();
        for (VoidReason reason : reasons) {
            byId.put(reason.id(), reason);
        }
        return new Listed(List.copyOf(reasons), Map.copyOf(byId));
    }

    /** The names of a reason's parameters: its content is text and {@code param} elements, mixed. */
    private static List<String> params(List<Serializable> content) {
        var names = new ArrayList<String>();
        for (Serializable part : content) {
            if (part instanceof JAXBElement<?> element
                    && element.getValue() instanceof RAMarketVoidReasons.VoidReason.Param param
                    && param.getName() != null) {
                names.add(param.getName());
            }
        }
        return names;
    }

    /** The one key of the one list. */
    private enum Whole {
        LIST
    }

    /** The list by id, and in id order. */
    private record Listed(List<VoidReason> reasons, Map<Integer, VoidReason> byId) {}
}
