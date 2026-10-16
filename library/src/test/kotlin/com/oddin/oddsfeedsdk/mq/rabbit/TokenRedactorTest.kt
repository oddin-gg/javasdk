package com.oddin.oddsfeedsdk.mq.rabbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Every exception reachable from this one through causes and suppressed exceptions, each once
 * (the same exception can be reached by two paths, and two exceptions can be each other's cause).
 */
internal fun Throwable.reachable(): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val order = mutableListOf<Throwable>()
    val pending = ArrayDeque<Throwable>().apply { add(this@reachable) }
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (seen.add(node)) {
            order.add(node)
            node.cause?.let { pending.add(it) }
            pending.addAll(node.suppressed)
        }
    }
    return order
}

class TokenRedactorTest {

    private val token = "tok-3f9c1d"

    private val redactor = TokenRedactor(token)

    private val named = "refused for user '$token'"

    private fun assertNoToken(failure: Throwable) {
        failure.reachable().forEach {
            assertFalse("the token is in ${it.javaClass.name}: $it", it.toString().contains(token))
            assertFalse("the token is in the text of ${it.javaClass.name}", it.message?.contains(token) == true)
        }
    }

    @Test
    fun everyOccurrenceInATextIsReplacedAndATextWithoutTheTokenIsAsItWas() {
        assertEquals(
            "a${TokenRedactor.MARKER}b and ${TokenRedactor.MARKER}",
            redactor.text("a${token}b and $token")
        )
        assertEquals("nothing to hide", redactor.text("nothing to hide"))
        assertNull(redactor.text(null))
    }

    @Test
    fun anEmptyTokenChangesNothing() {
        val failure = IOException("anything", IOException("at all"))
        val none = TokenRedactor("")

        assertSame(failure, none.throwable(failure))
        assertEquals("anything", none.text("anything"))
    }

    @Test
    fun aCleanFailureWithACleanCauseAndCleanSuppressedExceptionsIsTheSameInstance() {
        val failure = IOException("clean", IOException("clean cause")).also { it.addSuppressed(IOException("clean too")) }

        assertSame(failure, redactor.throwable(failure))
    }

    @Test
    fun aCleanFailureWithACauseNamingTheTokenIsCopiedWithTheCauseRedacted() {
        val failure = IOException("clean outside", IOException(named))

        val redacted = redactor.throwable(failure)

        assertNoToken(redacted)
        assertEquals("java.io.IOException: clean outside", redacted.toString())
        assertTrue(redacted.cause.toString(), redacted.cause.toString().contains("user '${TokenRedactor.MARKER}'"))
        assertTrue(redacted.stackTrace.contentEquals(failure.stackTrace))
    }

    @Test
    fun aSuppressedExceptionNamingTheTokenIsRedactedAndACleanOneStaysTheSameInstance() {
        val clean = IOException("clean suppressed")
        val failure = IOException("clean outside").also {
            it.addSuppressed(IOException(named))
            it.addSuppressed(clean)
        }

        val redacted = redactor.throwable(failure)

        assertNoToken(redacted)
        assertEquals(2, redacted.suppressed.size)
        assertTrue(redacted.suppressed[0].toString().contains("user '${TokenRedactor.MARKER}'"))
        assertSame(clean, redacted.suppressed[1])
    }

    @Test
    fun aCleanSuppressedExceptionOnACopiedFailureStaysTheSameInstance() {
        val clean = IOException("clean suppressed")
        val failure = IOException(named).also { it.addSuppressed(clean) }

        val redacted = redactor.throwable(failure)

        assertNoToken(redacted)
        assertSame(clean, redacted.suppressed.single())
    }

    @Test
    fun anExceptionThatIsBothTheCauseAndASuppressedOneIsRedactedOnBothPaths() {
        val shared = IOException(named)
        val failure = IOException("clean outside", shared).also { it.addSuppressed(shared) }

        val redacted = redactor.throwable(failure)

        assertNoToken(redacted)
        assertSame("one copy for both paths", redacted.cause, redacted.suppressed.single())
    }

    @Test
    fun anExceptionSuppressedTwiceIsRedactedBothTimes() {
        val shared = IOException(named)
        val failure = IOException("clean outside").also {
            it.addSuppressed(shared)
            it.addSuppressed(shared)
        }

        val redacted = redactor.throwable(failure)

        assertNoToken(redacted)
        assertSame(redacted.suppressed[0], redacted.suppressed[1])
    }

    @Test(timeout = 30_000)
    fun aCycleOfCausesEndsWithoutTheToken() {
        val a = IOException(named)
        val b = IOException("clean")
        b.initCause(a)
        a.initCause(b)

        val redacted = redactor.throwable(a)

        assertNoToken(redacted)
        assertEquals("the cycle is kept", redacted, redacted.cause!!.cause)
    }
}
