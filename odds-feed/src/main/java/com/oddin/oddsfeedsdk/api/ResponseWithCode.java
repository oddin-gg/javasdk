package com.oddin.oddsfeedsdk.api;

import com.oddin.oddsfeedsdk.schema.rest.v1.RAResponseCode;

/** A REST response that carries the API's response code. */
public interface ResponseWithCode {
    RAResponseCode getResponseCode();
}
