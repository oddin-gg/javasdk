package com.oddin.oddsfeedsdk.mq.entities;

import org.jspecify.annotations.Nullable;

/**
 * When a message was created by the producer, sent by the broker, received and handed to the listener, in epoch milliseconds.
 */
public final class MessageTimestamp {
    private long created;
    private final long sent;
    private final long received;
    private long published;

    public MessageTimestamp(long created, long sent, long received, long published) {
        this.created = created;
        this.sent = sent;
        this.received = received;
        this.published = published;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public long getSent() {
        return sent;
    }

    public long getReceived() {
        return received;
    }

    public long getPublished() {
        return published;
    }

    public void setPublished(long published) {
        this.published = published;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MessageTimestamp that)) {
            return false;
        }
        return created == that.created && sent == that.sent && received == that.received && published == that.published;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(created);
        result = 31 * result + Long.hashCode(sent);
        result = 31 * result + Long.hashCode(received);
        result = 31 * result + Long.hashCode(published);
        return result;
    }

    @Override
    public String toString() {
        return "MessageTimestamp(" + "created=" + created + ", "
                + "sent=" + sent + ", "
                + "received=" + received + ", "
                + "published=" + published + ")";
    }
}
