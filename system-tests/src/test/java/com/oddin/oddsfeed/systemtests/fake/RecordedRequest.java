package com.oddin.oddsfeed.systemtests.fake;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URLDecoder;
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

    /** The first value of this query parameter, decoded, or null when the query has none. */
    public String parameter(String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), UTF_8).equals(name)) {
                return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), UTF_8);
            }
        }
        return null;
    }
}
