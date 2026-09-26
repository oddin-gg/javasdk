package com.oddin.oddsfeedsdk.mq.entities;

public interface BasicMessage extends UnparsedMessage {
    int getProduct();

    long getTimestamp();
}
