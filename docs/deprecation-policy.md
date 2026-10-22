# Deprecation policy for 1.x

This policy says what a client may rely on while it stays on 1.x. It builds on what the design already states: source compatibility of the public API within a line, a single list of API differences, and no schema shape change inside 1.x.

## What the version numbers mean

- **The public API** is every type reachable from the entry points (`OddsFeed`, the configuration and its builder, the managers, the sessions and listeners, the exceptions), with everything their signatures reach. Internal types live in packages whose name contains `internal` and have no promise at all.
- **Patch** (`1.0.x`): bug fixes. No API addition or removal. A fix can change behaviour that was a bug; it is listed in the release notes and, if a client could see it, in the known-differences file.
- **Minor** (`1.x.0`): additions only. New methods on public interfaces come as `default` methods, so a client's own implementation of an interface keeps compiling. Additive schema changes are taken; a change of shape of a public schema class is not.
- **Major** (`2.0.0`): the only place something public is removed or changed incompatibly, and where the Java baseline moves.
- **Release candidates** (`1.0.0-rc.N`) carry no promise except that the final is source compatible with 0.0.x as described.

## How something is deprecated

1. It is marked `@Deprecated(since = "1.x", forRemoval = false)` in a minor release, with a Javadoc `@deprecated` that names the replacement. `forRemoval = true` is used only once the removal release is decided.
2. It is listed in that release's notes under a "Deprecated" heading, and in the [API differences list](../odds-feed/src/test/resources/api/differences.txt) (`deprecated | member | reason`), which the build checks against the code.
3. It keeps working, with the same behaviour, for the rest of 1.x. It is not removed earlier than 2.0.0, and not within 12 months of being deprecated, whichever is later.
4. The deprecated members 1.0 starts with - the getters for attributes the feed never sends - stay for all of 1.x and return null.
5. A removal in 2.0.0 is announced in the release notes of the last 1.x minor and by a notice to clients at least 6 months ahead.

## Behaviour

Behaviour that clients can observe and that is not a bug stays within 1.x. Documented defaults (`prefetch` 200, the maximum message size, the thresholds) change only in a minor release and with a note; a default that makes the SDK stricter, such as a smaller limit, waits for a major release. The known-differences file keeps recording behaviour that changes on purpose.

## Dependencies and platform

- The Java baseline of the whole 1.x line is 25. Raising it is a major change.
- The SDK's own dependencies are updated within compatible ranges, security fixes included. A dependency exposed in the public API (`jakarta.xml.bind`, `org.slf4j` for logging) does not change its major version within 1.x.
- The module name `gg.oddin.oddsfeed` and the coordinates do not change within 1.x.

## Support of releases

The newest minor of 1.x gets fixes; an older minor gets only a critical fix, for six months after the next minor. 0.0.x keeps critical fixes and additive wire fields until 31 March 2027.
