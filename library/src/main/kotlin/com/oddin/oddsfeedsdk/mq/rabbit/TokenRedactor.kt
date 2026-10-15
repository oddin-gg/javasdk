package com.oddin.oddsfeedsdk.mq.rabbit

import java.util.IdentityHashMap

/**
 * The access token is the AMQP user name, and a broker that refuses something names the user:
 * RabbitMQ's "access to vhost '...' refused for user '<token>'". Whatever the SDK logs or throws
 * of a broker failure goes through here first.
 *
 * Text has every occurrence of the token replaced by [MARKER]. A throwable keeps its identity
 * when neither it nor anything it carries mentions the token; one that does is copied with the
 * same class name (in its text), stack trace, redacted message, redacted cause chain and
 * suppressed exceptions, so the failure reads as before and still points at the same code.
 */
internal class TokenRedactor(private val token: String) {

    companion object {
        const val MARKER = "[access token redacted]"
    }

    fun text(text: String?): String? =
        if (text == null || token.isEmpty()) text else text.replace(token, MARKER)

    fun throwable(throwable: Throwable): Throwable =
        if (token.isEmpty()) throwable else redact(throwable, IdentityHashMap())

    private fun redact(throwable: Throwable, seen: IdentityHashMap<Throwable, Throwable>): Throwable {
        // a cycle in the cause chain is left as it is, rather than followed forever
        if (seen.put(throwable, throwable) != null) {
            return throwable
        }
        val cause = throwable.cause?.let { redact(it, seen) }
        val suppressed = throwable.suppressed.map { redact(it, seen) }
        val unchanged = !throwable.toString().contains(token) &&
            cause === throwable.cause &&
            suppressed.indices.all { suppressed[it] === throwable.suppressed[it] }
        if (unchanged) {
            return throwable
        }

        val copy = RedactedException(throwable.javaClass.name, text(throwable.message), cause)
        copy.stackTrace = throwable.stackTrace
        suppressed.forEach { copy.addSuppressed(it) }
        return copy
    }
}

/**
 * A broker failure whose text named the access token, with the token taken out. It is what the
 * original was in class (named in [toString]), stack trace and cause chain.
 */
internal class RedactedException(private val originalClass: String, message: String?, cause: Throwable?) :
    RuntimeException(message, cause) {

    override fun toString(): String = if (message == null) originalClass else "$originalClass: $message"
}
