# Releasing the Java SDK 0.x line

A `v0.*` tag on the tip of `release/0.x` publishes `com.oddin.oddsfeed:odds-feed` to GitHub
Packages. The 1.0 line releases separately, from `main` with `v1.*` tags, to Maven Central; see
`RELEASING.md` on `next` (`main` once 1.0 is out). `.github/workflows/ci.yml` does the work, on a `v0.*` tag:

1. **build_and_test_library**, **build_and_test_examples** and **test_release_check** - the
   branch CI at the tagged commit: unit tests, the examples, and the release check against its
   own cases (`scripts/release-check-test.sh`).
2. **check_version** - `scripts/release-check.sh` refuses the tag unless every pre-release
   check below passes. Its answer, the version, is what the next job builds.
3. **publish_maven** - builds the library at that version, runs the check again right before
   the upload, and uploads with `./gradlew publish -PreleaseVersion=<version>`. Runs for one
   version take turns.

The workflow does **not** make the GitHub Release or attach files to it: that is step 6 below,
by hand.

The version comes from the tag (`v0.0.59` publishes `0.0.59`, `v0.0.59-rc1` publishes
`0.0.59-rc1`), handed to Gradle as `-PreleaseVersion`. Nothing in the repository carries a
release version, and nothing is committed for a release. Without `-PreleaseVersion` the
Gradle build falls back to the last tag, which is why a commit with two tags is refused.

0.x is a maintenance line on Java 8 (`library/`, Gradle 7.4.1, Kotlin). Every change on it goes
through a PR into `release/0.x`.

## Every release

1. **Sweep the Go and .NET SDKs** for fixes merged since the last release. Port each one, or
   write down why it does not apply; the list goes into the release notes.
2. **Check `release/0.x` is green** at the commit you will tag (the CI run of its last merge).
3. **Tag the tip of `release/0.x` and push the tag.** Always an annotated tag, on the tip of
   `release/0.x` as origin has it now, with no other tag on that commit. Each release is its own
   commit: a release candidate's commit is not reused for the final, so merge the next change
   first (a notes-only or test PR will do) or release a further candidate.

   ```
   git fetch origin --tags
   git tag -a v0.0.59 -m 0.0.59 origin/release/0.x
   git push origin v0.0.59
   ```

   A release candidate is `v0.0.59-rc1`; the next is `-rc2`.
4. **Watch the run** under Actions - "Java SDK CI" for the tag. Nothing waits for an approval,
   so the upload happens as soon as the checks pass.
5. **Check the registry:** the version is under the repository's Packages
   (`com.oddin.oddsfeed.odds-feed`), with its jar and POM. A lookup needs a token with
   `read:packages`.
6. **Make the GitHub Release**, from the tag, as a draft first: the notes are GitHub's generated
   notes with the sweep list from step 1 put in front of them, and a release candidate is
   marked a pre-release (`--prerelease`, added to the same command):

   ```
   gh release create v0.0.59 --verify-tag --draft --generate-notes --title v0.0.59 \
     --notes "<the sweep list from step 1>"
   gh release create v0.0.59-rc1 --verify-tag --draft --prerelease --generate-notes \
     --title v0.0.59-rc1 --notes "<the sweep list from step 1>"
   ```

   Then attach `odds-feed-<version>.jar`, `odds-feed-<version>.pom` and, when you have it,
   `odds-feed-<version>-sources.jar`, **copied from the registry, not rebuilt**: the Gradle
   build is not reproducible, and the 1.0 line's system tests pin the digests of exactly what
   the registry serves (see "After a release" below). Download the files with a token, check
   they are the ones the run published, and `gh release upload v0.0.59 <files>`. Only when the
   assets are on the draft, publish it: `gh release edit v0.0.59 --draft=false`. A release
   that is published first cannot take its assets afterwards once releases are immutable.
   0.0.56 to 0.0.58 have these assets (0.0.58's were attached a day after the release); 0.0.54
   has none.
7. **Move the 1.0 line's compatibility baseline**, as below.

## After a release: move the baseline on `next`

Once a 0.x version is published and its GitHub Release has the jar and POM, the 1.0 line moves
its baseline to it, so that its API check and its system tests run against the newest 0.x
release. This is a PR into `next`, not `release/0.x`, and it is the same PR for all of it:

- `sdk.version` in the root `pom.xml` (the SDK the system tests run against; its default is the
  last published 0.0.x release);
- `api.baseline.version` in `odds-feed/pom.xml` (the release whose public API 1.0 keeps
  source-compatible);
- the SHA-256 of the new jar and POM in
  `system-tests/src/test/resources/sdk-jar-checksums.properties`, taken from the release's files,
  which are the registry's (`scripts/fetch-sdk.sh` downloads them from the GitHub Release and
  refuses anything that differs). Without the entry the build cannot fetch the new baseline;
- anything the new release changes that the repository records about the 0.x side:
  `system-tests/KNOWN-DIFFERENCES.md`, `NEXT.md`, and the version range in the header of the
  checksums file (the header of `scripts/fetch-sdk.sh` is open-ended and stays);
- `odds-feed/src/test/resources/api/differences.txt`, when the API check says an accepted
  difference changed.

Run the system tests (job `system-tests-next`) on the PR; the API check runs in the `build` job,
in the unit tests of `odds-feed` (`ApiUsageTest`, `PublicShapeTest`). When the release added
public API (as 0.0.58 added `Player.getUnderage()`), 1.0 must have it too, with the same
shape, and the baseline moves in that PR: the API check compiles a class implementing each
baseline interface, so it accepts a new abstract method only against the release that has it.

## Pre-release checks

The **check_version** job (`scripts/release-check.sh`) fails the release, before anything is
built, when:

- the tag is not `v0.MINOR.PATCH` or `v0.MINOR.PATCH-rcN` (so a `v1.*` tag can never publish
  here; the workflow's trigger lists `v0.*` only);
- the tag, as origin has it, no longer names the commit the run was started for (deleted or
  moved after the push) - asked again right before the upload;
- any other tag names the same commit;
- GitHub Packages already has `odds-feed` at that version (a 200, or a 302 to the file: the
  version is burned), or does not answer clearly within a minute or answers anything else (the
  check could not tell: run it again later). Only a 404 passes.

The check does **not** verify that the commit is on `release/0.x` or that CI was green on it;
that is steps 2 and 3. Tags on this line are not protected by a ruleset the check can see:
nobody should move or delete one.

## Known traps

- **A published version without a tag.** GitHub Packages keeps a version for good, and a
  version can reach it without a tag: `0.0.55` is in the registry (July 2026) with no
  `v0.0.55` tag and no GitHub Release, and so is `0.0.55-rc1`. The tag-driven check cannot know
  about those, and a registry version can never be uploaded again, so never reuse a version
  number: before choosing one, look at the registry's list as well as `git tag`. The next
  release after an untagged one is simply the next number (0.0.56 followed 0.0.55).
- **Two tags on one commit.** Gradle's fallback version is the last tag, which could be either
  tag on such a commit, so the check refuses it. A final is never tagged on its candidate's
  commit.
- **A tag deleted and pushed again.** Two runs for the same tag could both pass the check
  before either uploads. Publish runs for one version take turns, and each re-asks origin
  what the tag names; a run for a tag that has since moved fails. Do not move tags; make a new
  version.
- **A registry that does not answer.** The check cannot tell whether the version is there, so it
  stops. Re-run the failed run when GitHub Packages is back.
- **Assets rebuilt instead of copied.** A jar built again from the tag is not the published
  jar. The 1.0 line pins the digests of the registry's files.

## When something fails

A version is never uploaded twice, so a failed release is never retried under a version that
reached the registry; the next attempt is the next patch or release candidate.

- **Before publish_maven uploads:** nothing was published. Fix what failed. If the tag is
  right, re-run the failed jobs; if the tag has to change (wrong commit), the tag stays and
  the release is the next version on a commit of its own.
- **During the upload:** look the version up in the registry. If it is there with its jar and
  POM, the version is out and a re-run would be refused: carry on with step 5. If it is not
  there, re-run the job. A half-uploaded version (a POM without its jar) cannot be completed:
  release the next version.

## Dry run

Without a tag or a token, on JDK 8 in a checkout that has its tags (the Gradle git-version
plugin cannot read a linked worktree):

```
./scripts/release-check-test.sh      # the release check against its cases
./scripts/release-version-test.sh    # the build publishes the version it is handed
cd library && ./gradlew assemble -PreleaseVersion=0.0.59-rc1
```
