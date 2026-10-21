package com.oddin.oddsfeedsdk.mq.rabbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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

    // the copy of a failure keeps its class (in its text), its stack trace and the redacted message
    private fun assertCopyOf(original: Throwable, copy: Throwable) {
        assertNotSame("${original.javaClass.name} was not copied", original, copy)
        assertEquals(redactor.text(original.toString()), copy.toString())
        assertTrue(copy.stackTrace.contentEquals(original.stackTrace))
    }

    @Test
    fun aCleanFailureWhoseCauseIsACleanCauseOfAFailureNamingTheTokenIsCopiedAtEveryLevel() {
        val c = IOException(named)
        val b = IllegalStateException("clean middle", c)
        val a = IOException("clean outside", b)

        val redacted = redactor.throwable(a)

        assertNoToken(redacted)
        assertCopyOf(a, redacted)
        assertCopyOf(b, redacted.cause!!)
        assertCopyOf(c, redacted.cause!!.cause!!)
        assertNull(redacted.cause!!.cause!!.cause)
    }

    @Test
    fun aLongChainOfCleanCausesAboveAFailureNamingTheTokenIsCopiedAtEveryLevel() {
        // a pass over the exceptions in whatever order they come would miss the token in a chain
        // this long almost always, and a look at one level above it always
        val depth = 12
        val originals = ArrayList<Throwable>()
        originals.add(IOException(named))
        repeat(depth) { originals.add(IOException("clean $it", originals.last())) }
        val outermost = originals.last()

        val redacted = redactor.throwable(outermost)

        assertNoToken(redacted)
        var copy: Throwable? = redacted
        for (original in originals.asReversed()) {
            assertCopyOf(original, copy!!)
            copy = copy.cause
        }
        assertNull(copy)
    }

    @Test
    fun aFailureNamingTheTokenUnderACleanSuppressedExceptionWithACleanCauseIsReachedFromTheOutermost() {
        val tainted = IOException(named)
        val middle = IllegalStateException("clean cause", tainted)
        val suppressed = IOException("clean suppressed", middle)
        val outside = IOException("clean outside").also { it.addSuppressed(suppressed) }

        val redacted = redactor.throwable(outside)

        assertNoToken(redacted)
        assertCopyOf(outside, redacted)
        val copiedSuppressed = redacted.suppressed.single()
        assertCopyOf(suppressed, copiedSuppressed)
        assertCopyOf(middle, copiedSuppressed.cause!!)
        assertCopyOf(tainted, copiedSuppressed.cause!!.cause!!)
    }

    @Test
    fun aTaintBelowASuppressedExceptionOfACauseReachesTheOutermostAndLeavesCleanBranchesAlone() {
        val tainted = IOException(named)
        val deepSuppressed = IOException("clean deep suppressed", tainted)
        val cleanSibling = IOException("clean sibling")
        val cause = IllegalStateException("clean cause", cleanSibling).also { it.addSuppressed(deepSuppressed) }
        val outside = IOException("clean outside", cause)

        val redacted = redactor.throwable(outside)

        assertNoToken(redacted)
        assertCopyOf(outside, redacted)
        assertCopyOf(cause, redacted.cause!!)
        assertCopyOf(deepSuppressed, redacted.cause!!.suppressed.single())
        assertCopyOf(tainted, redacted.cause!!.suppressed.single().cause!!)
        // what carries no token is the original, however far down the copied chain it hangs
        assertSame(cleanSibling, redacted.cause!!.cause)
    }

    @Test
    fun aFailureNamingTheTokenReachedByACauseChainAndBySuppressionIsOneCopyOnBothPaths() {
        val tainted = IOException(named)
        val middle = IOException("clean middle", tainted)
        val outside = IOException("clean outside", middle)
        // the failure naming the token is suppressed on the outermost too: a second way to the same copy
        outside.addSuppressed(tainted)

        val redacted = redactor.throwable(outside)

        assertNoToken(redacted)
        assertCopyOf(middle, redacted.cause!!)
        assertSame("one copy on both paths", redacted.cause!!.cause, redacted.suppressed.single())
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
