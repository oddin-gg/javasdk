package com.oddin.oddsfeed.systemtests.fake;

import java.util.Map;

/**
 * One request the fake received, as the SDK sent it.
 *
 * @param method the HTTP method
 * @param path the path, including the API version prefix, without the query
 * @param query the raw query string, or null when there was none
 * @param headers the request headers, names in lower case, first value only
 */
public record RecordedRequest(String method, String path, String query, Map<String, String> headers) {

  public String header(String name) {
    return headers.get(name.toLowerCase(java.util.Locale.ROOT));
  }
}
