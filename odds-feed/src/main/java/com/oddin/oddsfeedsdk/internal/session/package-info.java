/**
 * The feed's sessions: the objects its builders hand out, what each was built with, and the plan
 * {@code open()} follows for them - the interest validation, each session's routing keys, which
 * sessions take snapshot completions, and the producers no session asks for. Not public API.
 */
@NullMarked
package com.oddin.oddsfeedsdk.internal.session;

import org.jspecify.annotations.NullMarked;
