/**
 * The entity caches: each entity's fields, the endpoints that write them, and the caches with the
 * loaders that fill them from the API. Built on the caches and the loaders; the façades read them
 * and the dispatchers write the feed's share into them. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.entity;

import org.jspecify.annotations.NullMarked;
