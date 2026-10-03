/**
 * The catalog caches: market descriptions, void reasons and match status descriptions, fetched as
 * lists and, for dynamic market variants, one by one. They refresh after write and serve what they
 * hold while a refresh runs and for as long as refreshes fail. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.catalog;

import org.jspecify.annotations.NullMarked;
