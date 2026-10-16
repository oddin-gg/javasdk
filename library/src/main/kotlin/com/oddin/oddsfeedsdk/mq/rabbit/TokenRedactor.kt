package com.oddin.oddsfeedsdk.mq.rabbit

import java.util.Collections
import java.util.IdentityHashMap

/**
 * The access token is the AMQP user name, and a broker that refuses something names the user:
 * RabbitMQ's "access to vhost '...' refused for user '<token>'". Whatever the SDK logs or throws
 * of a broker failure goes through here first.
 *
 * Text has every occurrence of the token replaced by [MARKER]. A throwable keeps its identity
 * when neither it nor anything it carries (causes and suppressed exceptions, however deep)
 * mentions the token; one that does is copied with the same class name (in its text), stack
 * trace, redacted message, redacted causes and suppressed exceptions, so the failure reads as
 * before and still points at the same code. What does not mention the token stays the original
 * instance, and an exception reached by more than one path is one copy on all of them.
 */
internal class TokenRedactor(private val token: String) {

    companion object {
        const val MARKER = "[access token redacted]"
    }

    fun text(text: String?): String? =
        if (text == null || token.isEmpty()) text else text.replace(token, MARKER)

    fun throwable(throwable: Throwable): Throwable {
        if (token.isEmpty()) {
            return throwable
        }

        // Everything reachable through causes and suppressed exceptions, each node once: the same
        // exception can be a cause and a suppressed one, or part of a cycle
        val children = IdentityHashMap<Throwable, List<Throwable>>()
        val pending = ArrayDeque<Throwable>().apply { add(throwable) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (children.containsKey(node)) {
                continue
            }
            val next = listOfNotNull(node.cause) + node.suppressed
            children[node] = next
            pending.addAll(next)
        }

        // a copy is made of every exception that names the token or reaches one that does
        val copied = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        children.keys.filterTo(copied) { it.toString().contains(token) }
        do {
            val before = copied.size
            children.forEach { (node, next) ->
                if (node !in copied && next.any { it in copied }) {
                    copied.add(node)
                }
            }
        } while (copied.size != before)
        if (throwable !in copied) {
            return throwable
        }

        // every path to an exception gets the one copy of it; the copies exist before they are
        // joined, so a cycle between them is rebuilt rather than followed
        val copies = IdentityHashMap<Throwable, Throwable>()
        copied.forEach { copies[it] = RedactedException(it.javaClass.name, text(it.message)) }
        copied.forEach { node ->
            val copy = copies.getValue(node)
            copy.stackTrace = node.stackTrace
            node.cause?.let { copy.initCause(copies[it] ?: it) }
            node.suppressed.forEach { copy.addSuppressed(copies[it] ?: it) }
        }
        return copies.getValue(throwable)
    }
}

/**
 * A broker failure whose text named the access token, with the token taken out. It is what the
 * original was in class (named in [toString]), stack trace and cause chain.
 */
internal class RedactedException(private val originalClass: String, message: String?) : RuntimeException(message) {

    override fun toString(): String = if (message == null) originalClass else "$originalClass: $message"
}
