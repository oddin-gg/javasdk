# Releasing the Java SDK 1.0 line

A `v1.*` tag on a commit of `next` or `main` releases `gg.oddin.oddsfeed:odds-feed` to Maven
Central. `.github/workflows/release.yml` does the work:

1. **check** - refuses the tag unless every pre-release check below passes.
2. **build** - the branch CI (`next.yml`) on the tagged commit, at the tag's version: unit and
   system tests, the compatibility checks, the coverage floor, the sources and javadoc jars.
3. **approve** - finals only: waits for a reviewer of the `maven-central-approval` environment.
   Release candidates skip it.
4. **publish** - builds `odds-feed` and the parent POM with the `release` profile, signs them,
   uploads them to the Central Portal and waits until Central has published them.
5. **github-release** - a GitHub Release for the tag, with `release-notes/<version>.md` as
   its text and the jar, the POM and their signatures attached.

The version comes from the tag (`v1.0.0-rc.1` builds `1.0.0-rc.1` through `-Drevision`).
Nothing in the repository carries a release version, and nothing is committed for a release
except its notes. The 0.x line releases separately, from `release/0.x` with `v0.*` tags, to
GitHub Packages.

## Once, before the first release

- [ ] **Namespace.** `gg.oddin` is verified in the Central Portal (central.sonatype.com): add
      the namespace under the organisation's account and put the TXT record the portal gives
      into the `oddin.gg` DNS zone. Verifying `gg.oddin` covers `gg.oddin.oddsfeed`.
- [ ] **Portal token.** A user token generated in the portal by the account that owns the
      namespace. It is a username and password pair, not the login.
- [ ] **Signing key.** A key for releases only, not a person's. Its public half is on
      `keyserver.ubuntu.com` and `keys.openpgp.org`, where Central looks for it. Keep the
      revocation certificate offline, and note the expiry date somewhere it will be seen.
- [ ] **Environment `maven-central`** (Settings - Environments), with deployment rule
      "selected tags": `v1.*`, and these secrets:

      | Secret | Value |
      |---|---|
      | `CENTRAL_TOKEN_USERNAME` | the portal token's username |
      | `CENTRAL_TOKEN_PASSWORD` | the portal token's password |
      | `MAVEN_GPG_KEY` | the signing key, ASCII-armoured secret key (`gpg --armor --export-secret-keys <id>`) |
      | `MAVEN_GPG_PASSPHRASE` | its passphrase |

      They are environment secrets, not repository ones, so only a job on a `v1.*` tag can
      read them.
- [ ] **Environment `maven-central-approval`**, with required reviewers (the people who may
      release a final; "prevent self-review" on), deployment rule "selected tags": `v1.*`, and
      no secrets.
- [ ] **Tag ruleset.** Only those people may create, move or delete `v*` tags: a tag is a
      release.

## Every release

1. **Sweep the Go and .NET SDKs** for fixes merged since the last release. Port each one, or
   write down why it does not apply; the list goes into the release notes.
2. **Check `next` is green**, including the schema drift job.
3. **Write the release notes** as `release-notes/<version>.md`, e.g.
   `release-notes/1.0.0-rc.1.md`, and merge them through a PR. A final needs this commit
   anyway: it may not share a commit with its last release candidate.
4. **Tag the merged commit and push the tag:**

   ```
   git fetch origin
   git tag -a v1.0.0-rc.1 -m 1.0.0-rc.1 origin/next
   git push origin v1.0.0-rc.1
   ```

5. **Watch the run** under Actions - "Java SDK 1.0 release". A release candidate publishes on
   its own. A final stops at **approve** until a reviewer approves the deployment, after the
   build is green.
6. **Check the result:** the version in the portal's Deployments as Published, then on
   `https://repo1.maven.org/maven2/gg/oddin/oddsfeed/odds-feed/` (it shows up within about 30
   minutes), and the GitHub Release with its four files.

## Pre-release checks

The **check** job fails the release, before anything is built, when:

- the tag is not `v1.MINOR.PATCH` or `v1.MINOR.PATCH-rc.N`;
- the tagged commit is on neither `next` nor `main`;
- another `v*` tag names the same commit;
- `release-notes/<version>.md` is missing or empty;
- Central already has `odds-feed` or `odds-feed-parent` at that version, or does not answer
  clearly (anything but 404 counts as "has it").

The portal also refuses a version it already has, which covers the minutes before a published
version appears in the repository.

## When something fails

- **Before publish:** nothing left the repository. Fix it, delete the tag
  (`git push origin :refs/tags/v1.0.0-rc.1`, then locally) and tag again - or, for a release
  candidate, take the next number.
- **During publish:** look the deployment up in the portal. Failed validation published
  nothing: fix, delete the tag, tag again. If it shows Published (the wait timed out), the
  version is out and a re-run would be refused: create the GitHub Release by hand with
  `gh release create`, attaching the jar, the POM and their `.asc` files from Central.
- **GitHub Release failed:** re-run the failed job; the bundle is kept as the run's
  `central-bundle` artifact.
- Never reuse a version Central has, and never move a tag that was published.

## Dry run

The release build without signing or uploading, as CI checks it on every push:

```
./mvnw -Prelease -Dgpg.skip -Drevision=1.0.0-rc.1 -DskipTests -pl odds-feed -am verify
```

`odds-feed/target` then has the jar, the sources and javadoc jars, and `odds-feed/.flattened-pom.xml`
is the POM Central would get. Always pass `-pl odds-feed -am` with the `release` profile: the
Central plugin fails on `system-tests`, which has no jar.
