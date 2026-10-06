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
 * @param resumePoint where a recovery would have to start for the sessions to miss nothing, epoch
 *     millis by the producer's clock, 0 for a full snapshot, {@link #NO_RESUME_POINT} until the
 *     recovery actor has one
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
        long resumePoint,
        long recoveryFrom,
        @Nullable RecoveryInfo recoveryInfo) {

    /** The resume point before the recovery actor has published one. */
    static final long NO_RESUME_POINT = -1;

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
                resumePoint,
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
                resumePoint,
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
                resumePoint,
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
                resumePoint,
                recoveryFrom,
                recoveryInfo);
    }

    ProducerState withResumePoint(long value) {
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
                resumePoint,
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
                resumePoint,
                recoveryFrom,
                value);
    }
}
