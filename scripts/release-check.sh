#!/usr/bin/env bash
#
# The checks a v1.* tag passes before release.yml builds anything. Central never takes a version
# back; besides these, every upload, release candidates included, waits for a reviewer of the
# maven-central environment. scripts/release-check-test.sh runs them against a scratch repository
# on every push.
#
#   scripts/release-check.sh <tag>
#
# Run in a clone with every branch and tag (fetch-depth: 0). It reads the tagged commit from
# git, not from the working tree. Writes version=, final= and commit= to $GITHUB_OUTPUT when
# that is set, to stdout otherwise. Needs curl, gh (with GH_TOKEN and GITHUB_REPOSITORY), python3
# and yq (mikefarah's, on GitHub's runners). CENTRAL_URL replaces the Central repository and
# CENTRAL_TIMEOUT the time limit in seconds of each lookup, Central's and GitHub's, for the test.
set -euo pipefail

tag=${1:?usage: release-check.sh <tag>}
central=${CENTRAL_URL:-https://repo1.maven.org/maven2}
timeout=${CENTRAL_TIMEOUT:-60}
repository=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY names the repository whose pull requests count}

fail() {
  echo "$tag: $*" >&2
  exit 1
}

# v1.2.3 is a final, v1.2.3-rc.4 a release candidate; nothing else is a release tag
if [[ ! "$tag" =~ ^v(1\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*))(-rc\.([1-9][0-9]*))?$ ]]; then
  fail "neither v1.MINOR.PATCH nor v1.MINOR.PATCH-rc.N"
fi
version=${tag#v}
if [ -n "${BASH_REMATCH[4]}" ]; then
  final=false
else
  final=true
fi

commit=$(git rev-parse --verify -q "refs/tags/$tag^{commit}") || fail "no such tag here"

# only what was reviewed and merged
on_branch=
for branch in next main; do
  ref=refs/remotes/origin/$branch
  if git rev-parse -q --verify "$ref" >/dev/null; then
    if git merge-base --is-ancestor "$commit" "$ref"; then
      on_branch=$branch
      break
    else
      # 1 means "not an ancestor"; anything else is git failing, which must not read as either
      status=$?
      [ "$status" -eq 1 ] || fail "git merge-base failed ($status) for $branch"
    fi
  fi
done
[ -n "$on_branch" ] || fail "$commit is on neither next nor main"

# On next or main is not enough: the repository merges by rebase, which puts every intermediate
# commit of a pull request on the branch, while the review saw only the final state. That state
# is the pull request's merge commit, the last commit it left on the branch. So the tagged
# commit must be the merge commit of a pull request merged into next or main.
limit=()
if command -v timeout > /dev/null; then
  limit=(timeout "$timeout")
fi
merged_into() {
  ${limit[@]+"${limit[@]}"} gh api --paginate "$1" \
    --jq ".[] | select(.merged_at != null and .merge_commit_sha == \"$commit\") | .base.ref"
}
bases=$(merged_into "repos/$repository/commits/$commit/pulls?per_page=100") \
  || fail "could not ask GitHub which pull request left $commit"
# GitHub documents that for a commit not on the default branch (main) this endpoint returns only
# open pull requests; checked on 2026-10-04, it returns the merged ones for next as well. Should
# it come to match its documentation, ask the other way round: the pull requests merged into
# next and main, for the one whose merge commit this is.
if ! printf '%s\n' "$bases" | grep -qxE 'next|main'; then
  for base in next main; do
    bases=$(merged_into "repos/$repository/pulls?state=closed&base=$base&per_page=100") \
      || fail "could not ask GitHub for the pull requests merged into $base"
    if printf '%s\n' "$bases" | grep -qx "$base"; then
      break
    fi
  done
fi
printf '%s\n' "$bases" | grep -qxE 'next|main' \
  || fail "$commit is not the merge commit of a pull request merged into next or main"

# one commit, one release (NEXT.md, section 9); any other tag is a question nobody answered
tags=$(git tag --points-at "$commit")
[ "$tags" = "$tag" ] || fail "other tags name $commit: $(echo "$tags" | grep -vxF "$tag" | tr '\n' ' ')"

# the GitHub Release is made from them after Central has the version; missing then, they would
# leave a published version without its release. A plain file: gh follows a link, and one to
# /proc/self/environ would put its token into the public release.
notes=release-notes/$version.md
entry=$(git ls-tree "$commit" -- "$notes")
[ -n "$entry" ] || fail "$notes is missing"
case "$entry" in
  "100644 blob "* | "100755 blob "*) ;;
  *) fail "$notes is not a plain file: ${entry%%$'\t'*}" ;;
esac
[ "$(git cat-file -s "$commit:$notes")" -gt 0 ] || fail "$notes is empty"

# release.yml must be the only workflow a tag starts: anything else would publish too, unchecked.
# Parsed, not grepped, so inline YAML counts too: a push trigger with tags, or with no branch
# filter at all (which runs on tags as well), and create and release events.
command -v yq >/dev/null || fail "needs yq to read the workflows"
# Read NUL-delimited, never split or globbed; a name git would have to quote is refused outright.
while IFS= read -r -d '' workflow; do
  case "$workflow" in
    *.yml | *.yaml) ;;
    *) continue ;;
  esac
  # in the C locale, where A-Z is those 26 letters and no others
  (LC_ALL=C && [[ "$workflow" =~ ^[A-Za-z0-9._/-]+$ ]]) || fail "$workflow: a workflow name outside A-Z a-z 0-9 . _ -"
  [ "$workflow" != .github/workflows/release.yml ] || continue
  triggers=$(git show "$commit:$workflow" | yq -o=json '.on') || fail "could not read $workflow"
  if ! printf '%s' "$triggers" | python3 -c '
import json, sys
on = json.load(sys.stdin)
if isinstance(on, str):
    on = [on]
if isinstance(on, list):
    on = {event: None for event in on}
if not isinstance(on, dict):
    sys.exit(0)
if "create" in on or "release" in on:
    sys.exit(1)
if "push" in on:
    push = on["push"] or {}
    if "tags" in push or "tags-ignore" in push:
        sys.exit(1)
    if "branches" not in push and "branches-ignore" not in push:
        sys.exit(1)
'; then
    fail "$workflow reacts to tags as well; only release.yml may"
  fi
done < <(git ls-tree -z --name-only "$commit" .github/workflows/)

# 404 is the one answer that means "not there"; anything else stops the release
for artifact in odds-feed-parent odds-feed; do
  url=$central/gg/oddin/oddsfeed/$artifact/$version/$artifact-$version.pom
  status=$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout "$timeout" --max-time "$timeout" "$url") \
    || status="no answer"
  case "$status" in
    404) ;;
    200) fail "Central already has $artifact $version" ;;
    *) fail "could not tell whether Central has $artifact $version ($status from $url)" ;;
  esac
done

echo "$tag: $version on $on_branch, final: $final" >&2
{
  echo "version=$version"
  echo "final=$final"
  echo "commit=$commit"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"
