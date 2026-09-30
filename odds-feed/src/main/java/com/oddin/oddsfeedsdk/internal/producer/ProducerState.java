package com.oddin.oddsfeedsdk.internal.producer;

import com.oddin.oddsfeedsdk.api.entities.ProducerScope;
import com.oddin.oddsfeedsdk.api.entities.RecoveryInfo;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One producer at one moment: what the producer list says about it, and what the feed has seen of
 * it since. Immutable; a change makes a new one.
 *
 * @param available whether the API lists it as active
 * @param enabled whether the client takes its messages; at first, whether it is available
 * @param down whether the feed has it down; every producer starts down, as in 0.0.x
 * @param recoveryFrom the client's recovery start, epoch millis, 0 for none
 */
record ProducerState(
        long id,
        String name,
        String description,
        boolean available,
        String apiUrl,
        Set<ProducerScope> scopes,
        int statefulRecoveryWindowInMinutes,
        boolean enabled,
        boolean down,
        long lastMessageTimestamp,
        long lastProcessedMessageGenTimestamp,
        long lastAliveReceivedGenTimestamp,
        long recoveryFrom,
        @Nullable RecoveryInfo recoveryInfo) {

    ProducerState {
        scopes = Set.copyOf(scopes);
    }

    ProducerState withEnabled(boolean value) {
        return new ProducerState(
                id,
                name,
                description,
                available,
                apiUrl,
                scopes,
                statefulRecoveryWindowInMinutes,
                value,
                down,
                lastMessageTimestamp,
                lastProcessedMessageGenTimestamp,
                lastAliveReceivedGenTimestamp,
                recoveryFrom,
                recoveryInfo);
    }

    ProducerState withDown(boolean value) {
        return new ProducerState(
                id,
                name,
                description,
                available,
                apiUrl,
                scopes,
                statefulRecoveryWindowInMinutes,
                enabled,
                value,
                lastMessageTimestamp,
                lastProcessedMessageGenTimestamp,
                lastAliveReceivedGenTimestamp,
                recoveryFrom,
                recoveryInfo);
    }

    ProducerState withLastMessageTimestamp(long value) {
        return new ProducerState(
                id,
                name,
                description,
                available,
                apiUrl,
                scopes,
                statefulRecoveryWindowInMinutes,
                enabled,
                down,
                value,
                lastProcessedMessageGenTimestamp,
                lastAliveReceivedGenTimestamp,
                recoveryFrom,
                recoveryInfo);
    }

    ProducerState withLastProcessedMessageGenTimestamp(long value) {
        return new ProducerState(
                id,
                name,
                description,
                available,
                apiUrl,
                scopes,
                statefulRecoveryWindowInMinutes,
                enabled,
                down,
                lastMessageTimestamp,
                value,
                lastAliveReceivedGenTimestamp,
                recoveryFrom,
                recoveryInfo);
    }

    ProducerState withLastAliveReceivedGenTimestamp(long value) {
        return new ProducerState(
                id,
                name,
                description,
                available,
                apiUrl,
                scopes,
                statefulRecoveryWindowInMinutes,
                enabled,
                down,
                lastMessageTimestamp,
                lastProcessedMessageGenTimestamp,
                value,
                recoveryFrom,
                recoveryInfo);
    }

    ProducerState withRecoveryFrom(long value) {
        return new ProducerState(
                id,
                name,
                description,
                available,
                apiUrl,
                scopes,
                statefulRecoveryWindowInMinutes,
                enabled,
                down,
                lastMessageTimestamp,
                lastProcessedMessageGenTimestamp,
                lastAliveReceivedGenTimestamp,
                value,
                recoveryInfo);
    }

    ProducerState withRecoveryInfo(RecoveryInfo value) {
        return new ProducerState(
                id,
                name,
                description,
                available,
                apiUrl,
                scopes,
                statefulRecoveryWindowInMinutes,
                enabled,
                down,
                lastMessageTimestamp,
                lastProcessedMessageGenTimestamp,
                lastAliveReceivedGenTimestamp,
                recoveryFrom,
                value);
    }
}
