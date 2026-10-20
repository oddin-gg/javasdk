# Releasing the Java SDK 1.0 line

A `v1.*` tag on the merge commit of a PR merged into `main` releases
`gg.oddin.oddsfeed:odds-feed` to Maven Central. Releases - release candidates included - are
cut from `main` only, never from `next`. `main` is the 1.0 line once ticket 44 of NEXT.md has
renamed `next` to it: the Kotlin line then moves to `legacy/main`, with an archive tag, and
`next` no longer exists. Until that rename there is nothing to release from: `release.yml` and the
1.0 code are on `next` alone. `.github/workflows/release.yml` does the work:

1. **check** - refuses the tag unless every pre-release check below passes.
2. **build** - the branch CI (`next.yml`) on the commit the check accepted, at the tag's
   version: unit and system tests, the compatibility checks, the coverage floor, the signed
   release bundle against a stand-in portal.
3. **publish** - waits until a reviewer of the `maven-central` environment approves it, for a
   release candidate as for a final. Then it checks that `MAVEN_GPG_KEY` is the checked key's
   subkeys (see "Pre-release checks"), builds `odds-feed` and the parent POM with the
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
- [ ] **Signing key,** as in "The signing key" below. A key for releases only, not a person's:
      a primary key that never expires and never leaves the release managers' vault entry, and
      a signing subkey of two years, which alone goes into the `maven-central` environment. Its
      public half is on `keyserver.ubuntu.com` and `keys.openpgp.org`, where Central looks for
      it; its primary's fingerprint is the repository variable `RELEASE_KEY_FINGERPRINT`; the
      subkey's expiry date is in this file and a reminder is set.
- [ ] **Branch protection on `main`:** every change through a PR with a required approval and
      code owners' review, linear history, no bypass, no force push, and - all three required
      for the release's security:
      - "Dismiss stale pull request approvals when new commits are pushed";
      - "Require approval of the most recent reviewable push";
      - "Require branches to be up to date before merging", so a rebase merge cannot produce
        a final state nobody saw.

      The approver approves a PR's merge commit as reviewed, and trusts all of it - `mvnw`,
      the POMs, the plugins, the code - since the approved job builds and signs from it with
      the secrets at hand. Without these settings an approval can outlive the code it was for.

      `next` is deliberately not protected this way: its PRs merge without a required
      approval. That is why releases never come from it - the check refuses a commit that is on
      `next` but not on `main` - and why the rename described at the top is what makes a release
      possible.
      `.github/CODEOWNERS` names the release managers' team as owner of the whole repository
      (`*`), so that every change needs a release manager's review, whatever path a release
      script reaches. The lines after it say what the release depends on: what the publish job
      runs with the key and the token, or what decides that it runs: `.github/` (the workflows
      and the policy that holds them), this file and `release-notes/`, `scripts/release-*`,
      the Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/`) and every `pom.xml` of the reactor, not
      only the root one. For the build job, which tests the commit first, also
      `scripts/fetch-sdk*`, the checksums `fetch-sdk.sh` reads
      (`system-tests/src/test/resources/sdk-jar-checksums.properties`) and the pinned
      `scripts/next-workflow.json`; and the schema drift job's files. The publish job only compiles
      the other sources, with the tests skipped. That list is not kept
      by hand alone: `scripts/release-codeowners-test.sh` derives every script a workflow runs
      and every file those scripts name by a literal path from the repository root (`$root/...`,
      `$here/...`) or from their own directory (a helper called through `dirname "$0"`), follows
      the scripts among those files the same way, resolves a symlink on the way to any of them and
      requires the team to own the file it leads to as well, and requires the team to own them all,
      so a new one cannot be missed. The whole of `scripts/` is owned again after `*`, as a
      second backstop; the test holds the derivation to the specific lines, without the two, and
      requires the two to own every file (the last matching rule winning), so a later rule for a
      directory, a wildcard or a file is refused.
      The scan is lexical and has limits. A symlink that leaves the tree and comes back into it
      (`../../<repository>/x`) is dropped, not followed. The target of a symlink outside
      `scripts/` is checked for ownership, but the helpers it names are not followed. Hard links
      are not seen. A script a workflow runs by a path that does not start with `scripts/`, such
      as `$GITHUB_WORKSPACE/scripts/...`, is not derived. A path built any other way is not
      seen. The whole repository is owned by the release managers, so these are covered by review.
      GitHub drops what it cannot read from a CODEOWNERS file, so `next.yml` asks it on every
      run (the `codeowners` job, `gh api repos/<repo>/codeowners/errors`) and fails on any error.
      It does not notice a line that was removed, which is why that test resolves the owners the
      way GitHub does, for the paths above and the reactor's POMs (from the root POM's `<modules>`).
- [ ] **Tag ruleset.** The release managers may create `v*` tags; nobody - admins and the tag's
      creator included - may update or delete one. A published version keeps its tag for good.
      Defence in depth: a tag runs `release.yml` as the tagged commit has it, so the checks in
      it cannot stop a tag put on an unreviewed commit whose `release.yml` skips them. The
      environment's reviewers are the control.
- [ ] **Immutable releases** (Settings - General - Releases), before the first release: a
      published GitHub Release and its files cannot change afterwards. The release workflow
      attaches the files to a draft and publishes it only then.
- [ ] **Only `release.yml` reacts to tags.** No other workflow on `main` may run on a
      `v1.*` tag: it would publish as well, unchecked. The check job refuses a tagged commit
      where another workflow would: a push trigger with tags or without a branch filter, or a
      `create` or `release` trigger.
- [ ] **Environment `maven-central`** (Settings - Environments), set up in this order:
      1. required reviewers - at least two people, so someone other than the one who pushed
         the tag can approve - with **"Prevent self-review"** on, and no bypass for admins;
      2. Deployment branches and tags: "Selected branches and tags", then "Add deployment
         branch or tag rule" with Ref type **Tag** and name pattern `v1.*` - and no branch
         rule at all. The Ref type defaults to Branch, and a branch rule `v1.*` would let a
         branch named, say, `v1.x` run a job with these secrets;
      3. only then these secrets:

      | Secret | Value |
      |---|---|
      | `CENTRAL_TOKEN_USERNAME` | the portal token's username |
      | `CENTRAL_TOKEN_PASSWORD` | the portal token's password |
      | `MAVEN_GPG_KEY` | the signing subkey, ASCII-armoured (`gpg --armor --export-secret-subkeys <primary fingerprint>`); the primary is a stub in that export and never goes in |
      | `MAVEN_GPG_PASSPHRASE` | its passphrase |

      No job gets them before a second person approves it. GitHub enforces that outside the
      workflow, which matters because the workflow is whatever the tagged commit says it is.
      No other environment is needed.

## The signing key

The release key has two parts, and only one of them goes to GitHub:

- the **primary key** certifies and never expires. It is not a secret of the repository or of
  any environment: it lives only in the release managers' vault entry (the release managers
  are the required reviewers of the `maven-central` environment), together with its passphrase
  and its revocation certificate. Anyone with the primary can make a new subkey, so it is used
  only to make and revoke subkeys, on a machine that is cleaned afterwards;
- the **signing subkey** signs the release and expires two years after it is made. It is never
  extended: a renewal makes a new subkey and revokes the old one, so a subkey that leaked without
  anyone noticing is good for its two years at most. `MAVEN_GPG_KEY` is its export,
  `MAVEN_GPG_PASSPHRASE` its passphrase. A leak of the environment then costs a subkey, which the
  primary replaces under the same fingerprint, not the key.

The fingerprint of the primary is what everything is looked up by: the repository variable
`RELEASE_KEY_FINGERPRINT` (Settings - Secrets and variables - Actions - Variables, a repository
variable; it is public), by which the check job fetches the public key (see "Pre-release
checks"). Renewing or replacing the subkey changes neither.

**Making the key**, once, on a machine you trust, in a throwaway home (gpg 2.4; it asks for the
passphrase, which goes into the vault entry):

```
export GNUPGHOME=$(mktemp -d)
gpg --quick-gen-key '<name of the release key> <release mailbox>' ed25519 cert never
gpg --list-keys --with-colons                # the first fpr: line is the primary's fingerprint
gpg --quick-add-key <primary fingerprint> ed25519 sign 2y
gpg --list-keys --with-subkey-fingerprints <primary fingerprint>   # the subkey and its expiry
```

The name and the mailbox of the user ID are the owner's to choose; the mailbox must be one that
can receive mail, for the verification below. Then:

1. **The vault entry.** The primary with its subkey, from `gpg --armor --export-secret-keys
   <primary fingerprint>`, the passphrase, and the revocation certificate: gpg 2.4 wrote that
   one when it made the key, to `$GNUPGHOME/openpgp-revocs.d/<primary fingerprint>.rev`. All
   three go into the vault entry. The certificate revokes the whole key, so it leaves the
   machine with the rest and is kept nowhere else.
2. **Upload the public key to both keyservers**, since Central reads both and the check job
   asks both:

   ```
   gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <primary fingerprint>
   gpg --export <primary fingerprint> | curl -T - https://keys.openpgp.org
   ```

   The second prints a link. Open it and ask for the verification mail for the release
   mailbox, then follow the link in that mail. Without that, keys.openpgp.org serves the key
   without its user ID, and gpg refuses to import such a key (`contains no user ID - skipped`)
   for everyone who fetches it from there to check a signature. The check job copes with a
   key without user IDs - it reads the subkeys and their signatures only - so it does not tell
   whether the verification was done.
3. **The CI secrets.** `gpg --armor --export-secret-subkeys <primary fingerprint>` is
   `MAVEN_GPG_KEY` and the passphrase is `MAVEN_GPG_PASSPHRASE`; set both in the `maven-central`
   environment, after its reviewers and deployment rule, as above. Set the repository variable
   `RELEASE_KEY_FINGERPRINT` to the primary's fingerprint, 40 hex digits without spaces.
   `RELEASE_KEY_FINGERPRINT=<fingerprint> scripts/release-key-check.sh`, run in any checkout,
   says whether a release would pass its key check on the public key. That check reads no
   secret; the publish job checks the secret itself, once approved, and refuses a
   `MAVEN_GPG_KEY` that is not the export of this key's subkeys.
4. **The expiry date, here.** Change the line below to the expiry of the subkey `MAVEN_GPG_KEY`
   holds (`YYYY-MM-DD`) by a PR:

   Signing subkey expires: **not created yet**

5. **The reminder.** A calendar event for the release managers and a tracked task, both dated 60
   days before that expiry, saying to replace the subkey with a new one as in "The signing subkey
   nears its expiry" under "When something fails". A scheduled GitHub workflow is not used for it: GitHub
   turns scheduled workflows off in a public repository after 60 days without activity.
6. **Delete the throwaway home** (`gpgconf --kill gpg-agent`, then remove the directory) and
   every file the steps wrote, once the vault entry and the secrets are in place and checked.

The check job refuses a release when the subkey is within 30 days of its expiry, so the reminder
leaves twice that. A subkey without an expiry would pass the check, but this layout gives it two
years, so that a leaked one does not stay valid for good.

**Who holds the key.** The vault entry is readable by the release managers. When one of them
leaves, their access to the entry ends. If they may have copied the key out of the vault, treat
it as a leak: of the primary, if they copied that, as in "The primary key is suspected leaked"; of
the subkey alone, as in "The signing key in the environment is suspected leaked".

## Every release

1. **Sweep the Go and .NET SDKs** for fixes merged since the last release. Port each one, or
   write down why it does not apply; the list goes into the release notes.
2. **Check `main` is green**, including the schema drift job.
3. **Write the release notes** as `release-notes/<version>.md`, e.g.
   `release-notes/1.0.0-rc.1.md`, and merge them through a PR into `main`. A final needs this
   commit anyway: it may not share a commit with its last release candidate.
4. **Tag the merge commit of the notes PR on `main` and push the tag** - or the current tip of
   `main`, which is always the merge commit of the last PR merged. Not an older commit on
   `main`, and nothing from `next`: see step 5. Publish jobs for one version never overlap:
   one runs, at most one waits, and a newer run replaces a waiting one; different versions
   don't wait for each other. A version is never uploaded twice because Central refuses one it
   has, as does the check job's lookup.

   ```
   git fetch origin
   git tag -a v1.0.0-rc.1 -m 1.0.0-rc.1 origin/main
   git push origin v1.0.0-rc.1
   ```

5. **Approve the upload** under Actions - "Java SDK 1.0 release", once check and build are
   green: every release, candidates included, stops at **publish** until a reviewer other than
   the tag's author approves the `maven-central` deployment. Before approving, the reviewer
   checks that the run's commit is the merge commit of a PR merged into `main`, and approves
   nothing else:

   ```
   gh api repos/oddin-gg/javasdk/commits/<sha>/pulls \
     --jq '.[] | select(.merged_at and .merge_commit_sha == "<sha>") | .base.ref'
   ```

   must print `main` (`next` does not count: its PRs need no approval). That proves the commit
   was reviewed only with `main`'s branch protection settings above on. If it prints nothing,
   ask the other way round; it must print a PR number:

   ```
   gh api --paginate 'repos/oddin-gg/javasdk/pulls?state=closed&base=main&per_page=100' --jq '.[] | select(.merge_commit_sha == "<sha>" and .merged_at) | .number'
   ```

   Being on `main` is not enough: the repository merges by
   rebase, which puts every intermediate commit of a PR on `main`, while the review saw only
   the PR's final state - its merge commit. The whole commit is trusted, not just `.github/`:
   the approved job runs that commit's `mvnw`, POMs and plugins with the token and the key, so
   what makes it trustworthy is that it is the final state of a PR that `main`'s ruleset made
   someone approve.
   The checks inside a tagged `release.yml` are only as trustworthy as that commit.
6. **Check the result:** the version in the portal's Deployments as Published, then on
   `https://repo1.maven.org/maven2/gg/oddin/oddsfeed/odds-feed/` (it shows up within about 30
   minutes), and the GitHub Release with its four files.

## Pre-release checks

The **check** job (`scripts/release-check.sh`, then `scripts/release-key-check.sh`) fails the
release, before anything is built, when:

- the tag is not `v1.MINOR.PATCH` or `v1.MINOR.PATCH-rc.N`;
- the tag, as origin has it, no longer names the commit the run was started for (moved after
  the push, by someone who may bypass the tag ruleset: tags cannot be moved back, so release a
  new version) - the same is asked again right before the upload;
- the tagged commit is not on `main` (on `next` only, the message says so: `next` has no
  required review), or is not the merge commit of a PR merged into `main` (an intermediate
  commit of a rebase-merged PR is on `main`, but was never reviewed as a state of its own; a
  PR merged into `next` counts for nothing);
- any other tag names the same commit;
- `release-notes/<version>.md` is missing, empty or not a plain file in the tagged commit (`gh`
  would follow a link into the public release text);
- another workflow in the tagged commit would also run on the tag;
- Central already has `odds-feed` or `odds-feed-parent` at that version, or does not answer
  clearly within a minute (anything but 404 counts as "has it");
- the signing key is not fit to sign. `scripts/release-key-check.sh` fetches the public key by
  the repository variable `RELEASE_KEY_FINGERPRINT` from both keyservers Central reads,
  `keys.openpgp.org` and `keyserver.ubuntu.com` - it needs no secret - and refuses when the
  variable is not 40 hex digits; when a server cannot be asked within a minute or does not have
  the key; when a server serves another key than the variable names, or a revoked one; or when
  the key has no signing subkey (a primary that signs does not count: it is not in CI), or the
  subkey gpg would sign with - the newest one not revoked and not expired - expires within 30
  days, or none is left; or when the two servers do not serve the same signing subkeys, each
  revoked or not and with the same expiry (after a revocation, a server still holding the old
  copy would pass alone, with the stolen subkey unrevoked). Both servers are asked because after
  a renewal one that still holds the old expiry makes Central refuse the signature; the message
  names the server, the subkey and the date.
  "The signing subkey nears its expiry" below says what to do.

The check job cannot see the secrets, which only the approved publish job reads. So that job,
before it builds, runs `scripts/release-signing-key-check.sh`: it imports `MAVEN_GPG_KEY` into a
throwaway home and refuses unless the secret has a stub primary (a full export, with the primary's
secret, is refused: that is a leak of the primary), the primary's fingerprint is
`RELEASE_KEY_FINGERPRINT`, and it holds the secret of a signing subkey that is neither revoked nor
expired. It names the reason and prints nothing of the key; the passphrase is not needed for it.
A secret that was replaced with another key, or exported wrongly, fails here and signs nothing.

That is the secret alone, and the approval may come days after the check job looked at the
keyservers. So the same step then holds the secret against them as they are now:
`scripts/release-key-check.sh` is asked again, for the subkey gpg would sign with - the newest
usable signing subkey of the secret - and refuses unless both keyservers have that very subkey,
the same, not revoked and not within 30 days of its expiry. A secret exported before its subkey
was revoked, or before the one it holds was published, is refused, even when the key has a newer
subkey that is fit. This asks the keyservers from the publish job, as the check job does.

Right before the upload, and again before the GitHub Release, the run asks GitHub whether the
tag still names the commit it checked and built (`scripts/release-tag-check.sh`), and stops if
it was moved or deleted - a release may have waited days for its approval.

The portal also refuses a version it already has, which covers the minutes before a published
version appears in the repository. On every push to `next`, `scripts/release-check-test.sh`
runs each of these cases against a scratch repository and a stub Central,
`scripts/release-key-check-test.sh` runs the key check's against stub keyservers and keys it
makes, `scripts/release-signing-key-check-test.sh` runs the secret's check against keys it
makes, and `scripts/release-workflow-test.sh` holds `release.yml` and the workflows it calls to its policy:
the release and key checks always run, the secret's key check before the upload, the upload only after check and build and only in the approved
environment, no secrets or environment anywhere else, no cache there, the tag checked
again first, and the bundle kept after a failed or cancelled upload too. It also compares `release.yml` with its pinned form in
`scripts/release-workflow.json`: a change to the release workflow updates that file too
(`scripts/release-workflow-test.sh --update`), so the reviewer sees it. `scripts/release-bundle-test.sh` runs the signed release build with a throwaway
key against a stand-in portal and checks the bundle.

## When something fails

Tags cannot be moved or deleted, so a failed release is never retried under the same tag: the
next attempt is a new version - the next release candidate number, or the next patch.

- **Before publish:** nothing left the repository, but the tag stays. Fix what failed, write
  the release notes for the next version, merge them, and tag that merge commit - a commit of
  its own, since two tags on one commit are refused.
- **During publish:** look the deployment up in the portal. Failed validation published
  nothing: release the next version as above. If it shows Published (the wait timed out, or the
  run was cancelled or ended some other way), the version is out and a re-run would be refused:
  make the GitHub Release by hand, from a `central-bundle.zip` in the root of a checkout of the
  tag (it has `release-notes/<version>.md`), with no `bundle/` directory left from an earlier
  attempt. Then, with `gh` logged in (or `GH_TOKEN` set) as someone who may create releases, and
  `unzip` installed:

  ```
  VERSION=<version> FINAL=<true|false> GITHUB_REF_NAME=v<version> GH_REPO=oddin-gg/javasdk scripts/release-github.sh
  ```

  `FINAL` is `true` for a final and `false` for a release candidate. The zip comes from one of
  two places. A run that has no artifact may still have published: the artifact's own upload can
  fail, a run can be cancelled or hit its timeout before that step, and artifacts expire, so the
  portal decides, not the artifact.

  1. **The run's artifact.** Every attempt of the publish job keeps its bundle as
     `central-bundle-<attempt>`, also after a failed or cancelled publish, since the plugin writes
     the bundle before it uploads it. A later attempt never replaces an earlier one's: take the
     bundle of the attempt whose upload Central accepted, normally the first (a later attempt's
     upload is refused, and its bundle is not what Central has). Download it into the checkout's
     root - from the run's page under Actions (a zip around `central-bundle.zip`, to unzip once)
     or with `gh run download <run id> -n central-bundle-<attempt> -D .` - so that
     `central-bundle.zip` lies there. Artifacts are kept for the repository's retention period
     (90 days unless changed), so do this soon.
  2. **Central itself**, when there is no artifact for the attempt: once the files show on
     `https://repo1.maven.org/maven2/gg/oddin/oddsfeed/odds-feed/<version>/` (within about 30
     minutes of Published), put the jar and the POM, each with its `.asc`, into the layout the
     script reads, from the checkout's root:

     ```
     version=<version>
     dir=gg/oddin/oddsfeed/odds-feed/$version
     mkdir -p "$dir"
     for name in "odds-feed-$version.jar" "odds-feed-$version.pom"; do
       for file in "$name" "$name.asc"; do
         curl -fsSLo "$dir/$file" "https://repo1.maven.org/maven2/$dir/$file"
       done
     done
     python3 -m zipfile -c central-bundle.zip gg && rm -r gg
     ```

     These are the files Central serves, so the release page and Central agree by construction.
- **GitHub Release failed:** re-run the failed job; the bundle is kept as the run's
  `central-bundle-<attempt>` artifact of the publish that succeeded, which the job finds by name
  from that job's output. A draft the failed run left is never touched by the re-run,
  which works only on the release it creates; delete the draft afterwards, by its id, not
  by the tag (which would also find the published release):

  ```
  gh api 'repos/oddin-gg/javasdk/releases?per_page=100' --jq '.[] | select(.draft and .tag_name == "<tag>") | .id'
  gh api -X DELETE repos/oddin-gg/javasdk/releases/<id>
  ```
- **A published version is defective.** Central is immutable: a version cannot be deleted or
  replaced, and nothing is retried under its number. The fix is the next version, released as
  above with its notes saying what it fixes. Mark the defective one in its GitHub Release:
  GitHub's immutable releases lock the files and the tag, not the text - the title and the
  notes of a published release can still be edited, so say there which version fixes it - and
  the same goes in the next version's `release-notes/<version>.md`. Tell the clients through
  the usual channel. The release managers named as the reviewers of the `maven-central`
  environment decide that a version is defective and that the next one goes out.
- **The signing subkey nears its expiry** - the reminder, or the check job refusing a release
  whose subkey expires within 30 days or has expired. Do not extend it: make a new signing
  subkey, put that one into `MAVEN_GPG_KEY`, and revoke the old one, so that a subkey that leaked
  unnoticed does not outlive its own two years. The fingerprint stays, so nothing changes for
  anyone who checks signatures. The order keeps every release signing with a subkey the keyservers
  show as valid: the new subkey is published before the secret changes, and the old one is revoked
  only once the secret holds the new one and no publish job that read the old secret is still
  running.
  1. On a machine you trust, import the primary from the vault entry into a throwaway home:

     ```
     export GNUPGHOME=$(mktemp -d)
     gpg --import <the key file of the vault entry>
     ```

  2. Make the new subkey, two years from today (it asks for the passphrase from the vault
     entry), and note its fingerprint, the newer of the two:

     ```
     gpg --quick-add-key <primary fingerprint> ed25519 sign 2y
     gpg --list-keys --with-subkey-fingerprints <primary fingerprint>
     ```

  3. Upload the public key to both keyservers, as in "The signing key", step 2 (the verification
     is done once). The old subkey stays valid, so releases go on signing with it. Until both
     serve the same keys the check job refuses the release, naming the one that does not; upload
     again.
  4. `gpg --armor --export-secret-subkeys '<new subkey fingerprint>!'` (the `!` exports that one
     subkey alone) is the new `MAVEN_GPG_KEY`; replace the secret. The passphrase is unchanged.
     The publish job refuses a secret whose subkey the keyservers do not have yet, so this
     comes after step 3. A run still waiting for its approval takes the new secret when it is
     approved.
  5. When no publish job is running (Actions - "Java SDK 1.0 release"; a running one holds the
     old secret, and Central checks its signatures against what the keyservers say of the old
     subkey), revoke the old subkey with the primary, giving "Key is superseded" as the reason
     (2), and upload the public key to both keyservers again:

     ```
     gpg --edit-key <primary fingerprint>
     gpg> key <old subkey fingerprint>
     gpg> revkey
     gpg> save
     ```

  6. Put the new subkey's expiry in the line "Signing subkey expires" above, by a PR, and move
     the reminder to 60 days before it. Replace the key file in the vault entry with `gpg --armor
     --export-secret-keys <primary fingerprint>`, so that the entry has the new subkey.
  7. Run `RELEASE_KEY_FINGERPRINT=<primary fingerprint> scripts/release-key-check.sh`; it must
     pass. Delete the throwaway home and its files.
- **The signing key in the environment is suspected leaked** - `MAVEN_GPG_KEY` or its
  passphrase was shown or read, or an export of the subkey is lost. The primary is not in it: the
  subkey is revoked and replaced, under the same fingerprint, so `RELEASE_KEY_FINGERPRINT` and
  what clients check signatures against stay as they are.
  1. As in the renewal, import the primary from the vault entry into a throwaway home, and
     revoke the subkey with it, giving "Key has been compromised" as the reason:

     ```
     gpg --edit-key <primary fingerprint>
     gpg> key <subkey fingerprint>
     gpg> revkey
     gpg> save
     ```

  2. Make the new subkey, `gpg --quick-add-key <primary fingerprint> ed25519 sign 2y`. The
     passphrase may have gone with the key: change it with `gpg --change-passphrase <primary
     fingerprint>`, and in the vault entry. Until the new secret is in place, the publish job
     refuses a release, naming the revoked subkey: nothing is signed with it.
  3. Upload the public key, with the revocation and the new subkey, to both keyservers, as in
     "The signing key", step 2. Central then refuses a signature by the old subkey.
  4. `gpg --armor --export-secret-subkeys '<new subkey fingerprint>!'` (the `!` exports that one
     subkey alone, not the revoked one) is the new `MAVEN_GPG_KEY`; replace it, and
     `MAVEN_GPG_PASSPHRASE` if it changed. Put the new subkey's expiry in the line "Signing subkey
     expires" above and move the reminder, and replace the key file in the vault entry, as in the
     renewal.
  5. Delete the throwaway home and its files.

  Versions already on Central stay as they are. A signature the revoked subkey made still
  verifies, but gpg warns that the subkey was revoked, with the reason.

  The key and the portal token are secrets of the same environment, so whatever exposed one may
  have exposed the other. If the key left in a way that could not have read the environment (a
  stolen backup of the key file, say), replacing the subkey is enough. If the environment's secrets
  may have been read - a workflow run or log that showed them, someone with access to the
  environment, a compromised job - rotate the portal token too, at the same time: generate a
  new user token in the Central Portal, replace `CENTRAL_TOKEN_USERNAME` and
  `CENTRAL_TOKEN_PASSWORD` in the environment, and revoke the old token in the portal. The old
  token alone lets whoever holds it upload versions to the `gg.oddin` namespace.
- **The primary key is suspected leaked** - the vault entry or a copy of its key file was read
  by someone who should not have, or the primary and its passphrase were lost together. Whoever
  has the primary can make subkeys that Central accepts, so the key is replaced, not just the
  subkey:
  1. Revoke it on both keyservers at once, with the revocation certificate from the vault entry.
     The file's key block starts with `:-----BEGIN PGP PUBLIC KEY BLOCK-----`; remove that colon
     first. Then, in a throwaway home into which the vault entry's key file is imported:

     ```
     gpg --import <primary fingerprint>.rev
     gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <primary fingerprint>
     gpg --export <primary fingerprint> | curl -T - https://keys.openpgp.org
     ```

  2. Make a new key, as in "The signing key", with a new vault entry (and the old one deleted),
     new `MAVEN_GPG_KEY` and `MAVEN_GPG_PASSPHRASE`, and its fingerprint in
     `RELEASE_KEY_FINGERPRINT`. Until that variable is changed, the check job refuses every
     release, as it should: the key it asks for is revoked. Put the new date in the line
     "Signing subkey expires" above and set the reminder again.
  3. Rotate the portal token too, as above, if the environment's secrets may have been read as
     well. Tell the clients, through the usual channel, which fingerprint releases are signed
     with from now on.

  Versions already on Central stay as they are.
- Never reuse a version Central has.

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
