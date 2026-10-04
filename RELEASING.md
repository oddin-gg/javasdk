# Releasing the Java SDK 1.0 line

A `v1.*` tag on a commit of `next` or `main` releases `gg.oddin.oddsfeed:odds-feed` to Maven
Central. `.github/workflows/release.yml` does the work:

1. **check** - refuses the tag unless every pre-release check below passes.
2. **build** - the branch CI (`next.yml`) on the commit the check accepted, at the tag's
   version: unit and system tests, the compatibility checks, the coverage floor, the signed
   release bundle against a stand-in portal.
3. **publish** - waits until a reviewer of the `maven-central` environment approves it, for a
   release candidate as for a final. Then it builds `odds-feed` and the parent POM with the
   `release` profile, signs them, uploads them to the Central Portal and waits until Central
   has published them.
4. **github-release** - a GitHub Release for the tag, with `release-notes/<version>.md` as
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
- [ ] **Branch protection on `next` and `main`:** every change through a reviewed PR, no
      bypass, no force push. This is part of the release's security: the approver approves a
      commit because it is on one of these branches, and trusts all of it - `mvnw`, the POMs,
      the plugins, the code - since the approved job builds and signs from it with the secrets
      at hand.
- [ ] **Tag ruleset.** Only the release managers may create, move or delete `v*` tags, with no
      bypass for anyone else, admins included. Defence in depth: a tag runs `release.yml` as
      the tagged commit has it, so the checks in it cannot stop a tag put on an unreviewed
      commit whose `release.yml` skips them. The environment's reviewers are the control.
- [ ] **Only `release.yml` reacts to tags.** No other workflow on `next` or `main` may run on a
      `v1.*` tag: it would publish as well, unchecked. The check job refuses a tagged commit
      where another workflow would: a push trigger with tags or without a branch filter, or a
      `create` or `release` trigger.
- [ ] **Environment `maven-central`** (Settings - Environments), set up in this order:
      1. required reviewers - at least two people, so someone other than the one who pushed
         the tag can approve - with **"Prevent self-review"** on, and no bypass for admins;
      2. deployment rule "selected tags": `v1.*`;
      3. only then these secrets:

      | Secret | Value |
      |---|---|
      | `CENTRAL_TOKEN_USERNAME` | the portal token's username |
      | `CENTRAL_TOKEN_PASSWORD` | the portal token's password |
      | `MAVEN_GPG_KEY` | the signing key, ASCII-armoured secret key (`gpg --armor --export-secret-keys <id>`) |
      | `MAVEN_GPG_PASSPHRASE` | its passphrase |

      No job gets them before a second person approves it. GitHub enforces that outside the
      workflow, which matters because the workflow is whatever the tagged commit says it is.
      No other environment is needed.

## Every release

1. **Sweep the Go and .NET SDKs** for fixes merged since the last release. Port each one, or
   write down why it does not apply; the list goes into the release notes.
2. **Check `next` is green**, including the schema drift job.
3. **Write the release notes** as `release-notes/<version>.md`, e.g.
   `release-notes/1.0.0-rc.1.md`, and merge them through a PR. A final needs this commit
   anyway: it may not share a commit with its last release candidate.
4. **Tag the merge commit of the notes PR and push the tag** - or the current tip of `next`,
   which is always the merge commit of the last PR merged. Not an older commit on `next`: see
   step 5. Runs for one version take turns; runs for different versions don't wait for each
   other.

   ```
   git fetch origin
   git tag -a v1.0.0-rc.1 -m 1.0.0-rc.1 origin/next
   git push origin v1.0.0-rc.1
   ```

5. **Approve the upload** under Actions - "Java SDK 1.0 release", once check and build are
   green: every release, candidates included, stops at **publish** until a reviewer other than
   the tag's author approves the `maven-central` deployment. Before approving, the reviewer
   checks that the run's commit is the merge commit of a PR merged into `next` or `main`, and
   approves nothing else:

   ```
   gh api repos/oddin-gg/javasdk/commits/<sha>/pulls \
     --jq '.[] | select(.merged_at and .merge_commit_sha == "<sha>") | .base.ref'
   ```

   must print `next` or `main`. If it prints nothing, ask the other way round (GitHub documents
   the first form as listing only open PRs for a commit off `main`, though it lists merged
   ones today), for `base=next` and then `base=main`; it must print a PR number:

   ```
   gh api --paginate 'repos/oddin-gg/javasdk/pulls?state=closed&base=next&per_page=100' --jq '.[] | select(.merge_commit_sha == "<sha>" and .merged_at) | .number'
   ```

   Being on `next` is not enough: the repository merges by
   rebase, which puts every intermediate commit of a PR on `next`, while the review saw only
   the PR's final state - its merge commit. The whole commit is trusted, not just `.github/`:
   the approved job runs that commit's `mvnw`, POMs and plugins with the token and the key, so
   what makes it trustworthy is that it is a reviewed PR's final state on a protected branch.
   The checks inside a tagged `release.yml` are only as trustworthy as that commit.
6. **Check the result:** the version in the portal's Deployments as Published, then on
   `https://repo1.maven.org/maven2/gg/oddin/oddsfeed/odds-feed/` (it shows up within about 30
   minutes), and the GitHub Release with its four files.

## Pre-release checks

The **check** job (`scripts/release-check.sh`) fails the release, before anything is built,
when:

- the tag is not `v1.MINOR.PATCH` or `v1.MINOR.PATCH-rc.N`;
- the tag, as origin has it, no longer names the commit the run was started for (it moved
  after the push: push it again) - the same is asked again right before the upload;
- the tagged commit is on neither `next` nor `main`, or is not the merge commit of a PR merged
  into one of them (an intermediate commit of a rebase-merged PR is on `next`, but was never
  reviewed as a state of its own);
- any other tag names the same commit;
- `release-notes/<version>.md` is missing, empty or not a plain file in the tagged commit (`gh`
  would follow a link into the public release text);
- another workflow in the tagged commit would also run on the tag;
- Central already has `odds-feed` or `odds-feed-parent` at that version, or does not answer
  clearly within a minute (anything but 404 counts as "has it").

Right before the upload, and again before the GitHub Release, the run asks GitHub whether the
tag still names the commit it checked and built (`scripts/release-tag-check.sh`), and stops if
it was moved or deleted - a release may have waited days for its approval.

The portal also refuses a version it already has, which covers the minutes before a published
version appears in the repository. On every push to `next`, `scripts/release-check-test.sh`
runs each of these cases against a scratch repository and a stub Central, and
`scripts/release-workflow-test.sh` holds `release.yml` and the workflows it calls to its policy:
the release check always run, the upload only after check and build and only in the approved
environment, no secrets or environment anywhere else, no cache there, and the tag checked
again first. It also compares `release.yml` with its pinned form in
`scripts/release-workflow.json`: a change to the release workflow updates that file too
(`scripts/release-workflow-test.sh --update`), so the reviewer sees it. `scripts/release-bundle-test.sh` runs the signed release build with a throwaway
key against a stand-in portal and checks the bundle.

## When something fails

- **Before publish:** nothing left the repository. Fix it, delete the tag
  (`git push origin :refs/tags/v1.0.0-rc.1`, then locally) and tag again - or, for a release
  candidate, take the next number. Either way the failed tag goes first if the new one lands
  on the same commit: two tags on one commit are refused.
- **During publish:** look the deployment up in the portal. Failed validation published
  nothing: fix, delete the tag, tag again. If it shows Published (the wait timed out), the
  version is out and a re-run would be refused: create the GitHub Release by hand with
  `gh release create`, attaching the jar, the POM and their `.asc` files from Central.
- **GitHub Release failed:** re-run the failed job; the bundle is kept as the run's
  `central-bundle` artifact.
- Never reuse a version Central has, and never move a tag that was published.

## Dry run

`scripts/release-bundle-test.sh`, as CI runs it on every push: the signed release build with a
throwaway key, uploaded to a stand-in portal on `127.0.0.1`, and the bundle checked. Without
signing or uploading:

```
./mvnw -Prelease -Dgpg.skip -Drevision=1.0.0-rc.1 -DskipTests -pl odds-feed -am verify
```

`odds-feed/target` then has the jar, the sources and javadoc jars, and `odds-feed/.flattened-pom.xml`
is the POM Central would get. Always pass `-pl odds-feed -am` with the `release` profile: the
Central plugin fails on `system-tests`, which has no jar.
