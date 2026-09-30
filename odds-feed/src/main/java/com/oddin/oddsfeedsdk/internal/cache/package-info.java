/**
 * The caches: bounded maps of entries with their metadata, and the rules for writing them. A cache
 * never fetches and never blocks - it does not depend on the loaders, the REST client or the broker,
 * which {@code CacheDependencyTest} holds it to. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.cache;

import org.jspecify.annotations.NullMarked;
