#!/usr/bin/env bash
#
# The approval check a v1.* tag passes before release.yml builds anything. The branch ruleset of
# main requires a pull request with one approval and a code owners' review, but it neither
# dismisses an approval when the pull request changes nor asks for the approval of its latest
# push, so a pull request can merge on the approval of an earlier version of itself. This check
# closes that gap at the release: every pull request merged into main since the previous release
# must have been approved on GitHub for its final commit, by someone other than its author.
# scripts/release-approval-check-test.sh runs it against a stub gh on every push.
#
#   scripts/release-approval-check.sh <commit>
#
# Run in a clone with every branch and tag (fetch-depth: 0), for the tagged commit, with gh (with
# GH_TOKEN and GITHUB_REPOSITORY). The commits checked are those after the previous release: the
# nearest v1.* tag reachable from it whose GitHub Release is published and no draft, a release
# candidate's included; a tag whose release never came out is passed over. Before the first
# release, those after the commit in scripts/release-approval-baseline, read from the tagged
# commit. Each commit is looked up for the pull request merged into main that left it; none, a
# direct push, fails as well. Of each such pull request, its final head is asked, and its reviews:
# one APPROVED, for that head, by a user other than its author. Every pull request that fails is
# named, with the approvals it had. The calls to GitHub are one per commit, two per pull request
# and one per tag looked at until a release is found, plus the further pages of a long list. A
# call that fails, or does not answer within APPROVAL_TIMEOUT seconds (default 60), stops the
# release: no answer is never a pass.
set -euo pipefail

label=${1:?usage: release-approval-check.sh <commit>}
repository=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY names the repository whose pull requests count}
timeout=${APPROVAL_TIMEOUT:-60}
baseline_file=scripts/release-approval-baseline

fail() {
  echo "$label: $*" >&2
  exit 1
}

limit=()
if command -v timeout > /dev/null; then
  limit=(timeout "$timeout")
fi
ask() {
  ${limit[@]+"${limit[@]}"} gh api "$@"
}
# one line of text from GitHub, which anyone may write, without a character that moves a log line
plain() {
  printf '%s' "$1" | tr -d '[:cntrl:]'
}

# git may only see part of the history in a shallow clone, and would then check fewer commits
[ "$(git rev-parse --is-shallow-repository)" = false ] || fail "this clone is shallow; it needs every commit and tag (fetch-depth: 0)"
commit=$(git rev-parse --verify -q "$label^{commit}") || fail "no such commit here"
label=${commit:0:12}
parent=$(git rev-parse --verify -q "$commit^") || fail "the commit has no parent, so there is nothing before it to start from"

# Whether the tag's release was published: GitHub has a release for it that is no draft. A tag stays
# for good once pushed, also one whose check refused it or whose upload failed; such a tag is no
# release, and the pull requests before it still have to be asked about. 404 is the one answer that
# means "no release"; anything else, a timeout included, stops the release.
published() {
  local answer
  if answer=$(ask "repos/$repository/releases/tags/$1" --jq '.draft' 2>&1); then
    case "$answer" in
      false) return 0 ;;
      true) return 1 ;;
      *) fail "GitHub gave \"$answer\" for whether the release of $1 is a draft" ;;
    esac
  fi
  case "$answer" in
    *"HTTP 404"*) return 1 ;;
    *) fail "could not ask GitHub whether $1 has a published release: $answer" ;;
  esac
}

# The start: the nearest v1.* tag among those the commit descends from, itself left out, whose
# release was published, found by walking back from the nearest.
start= since=
candidates=$(for tag in $(git for-each-ref --merged="$parent" --format='%(refname:strip=2)' 'refs/tags/v1.*'); do
  echo "$(git rev-list --count "refs/tags/$tag..$commit"):$tag"
done | sort -n)
for entry in $candidates; do
  tag=${entry#*:}
  if published "$tag"; then
    start=$(git rev-parse --verify -q "refs/tags/$tag^{commit}") || fail "$tag does not name a commit"
    since=$tag
    break
  fi
  echo "$label: $tag has no published release, so it is no start" >&2
done
if [ -z "$start" ]; then
  # no release before this one; the baseline, from the tagged commit, which a clone of that commit has too
  raw=$(git show "$commit:$baseline_file" 2> /dev/null) || fail "$baseline_file is missing: set the baseline, the commit main pointed to when the rule began"
  lines=$(printf '%s\n' "$raw" | grep -v '^[[:space:]]*\(#\|$\)' || true)
  case "$lines" in
    unset) fail "$baseline_file still holds the placeholder: set the baseline to the commit main pointed to when the rule began (the rename of next to main)" ;;
    *[!0-9a-f]* | "") fail "$baseline_file must hold one full SHA after its comment lines: set the baseline" ;;
  esac
  [ "${#lines}" -eq 40 ] || fail "$baseline_file must hold one full SHA (40 hex digits): set the baseline"
  start=$(git rev-parse --verify -q "$lines^{commit}") || fail "the baseline $lines is no commit in this clone"
  git merge-base --is-ancestor "$start" "$commit" || fail "the baseline ${start:0:12} is not an ancestor of the commit"
  since="the baseline ${start:0:12}"
fi
[ "$start" != "$commit" ] || fail "nothing was merged since $since"

commits=$(git rev-list "$start..$commit")
total=$(printf '%s\n' "$commits" | wc -l)

# The pull requests that left each commit: merged, into main. A commit may also be on open ones.
numbers=()
direct=()
for sha in $commits; do
  found=$(ask --paginate "repos/$repository/commits/$sha/pulls?per_page=100" \
    --jq '.[] | select(.merged_at != null and .base.ref == "main") | .number') \
    || fail "could not ask GitHub which pull request left $sha"
  if [ -z "$found" ]; then
    direct+=("$sha")
    continue
  fi
  for number in $found; do
    [[ "$number" =~ ^[0-9]+$ ]] || fail "GitHub gave $number as a pull request number for $sha"
    numbers+=("$number")
  done
done

failures=0
for sha in ${direct[@]+"${direct[@]}"}; do
  echo "$label: commit ${sha:0:12} ($(plain "$(git log -1 --format=%s "$sha")")) came from no pull request merged into main: a direct push" >&2
  failures=$((failures + 1))
done

pulls=0
for number in $(printf '%s\n' ${numbers[@]+"${numbers[@]}"} | sort -un); do
  pulls=$((pulls + 1))
  info=$(ask "repos/$repository/pulls/$number" --jq '.head.sha, (.user.login // ""), .title') \
    || fail "could not ask GitHub for pull request #$number"
  head=$(printf '%s\n' "$info" | sed -n 1p)
  author=$(printf '%s\n' "$info" | sed -n 2p)
  title=$(plain "$(printf '%s\n' "$info" | sed -n 3p)")
  [[ "$head" =~ ^[0-9a-f]{40}$ ]] || fail "GitHub gave no final commit for pull request #$number: $head"
  [ -n "$author" ] || fail "GitHub gave no author for pull request #$number"
  # a login or "-" for none; every APPROVED review, whatever it was for: the ones that do not count
  # are reported
  approvals=$(ask --paginate "repos/$repository/pulls/$number/reviews?per_page=100" \
    --jq '.[] | select(.state == "APPROVED") | "\(.commit_id // "-") \(.user.login // "-")"') \
    || fail "could not ask GitHub for the reviews of pull request #$number"
  approved=false
  report=
  while read -r reviewed login; do
    [ -n "$reviewed" ] || continue
    if [ "$login" = "$author" ]; then
      report+=$'\n'"    approved by $login on ${reviewed:0:12}: its author does not count"
    elif [ "$reviewed" != "$head" ]; then
      report+=$'\n'"    approved by $login on ${reviewed:0:12}: not the final commit"
    elif [ "$login" = "-" ]; then
      report+=$'\n'"    approved on the final commit by a user GitHub does not name: does not count"
    else
      approved=true
    fi
  done <<< "$approvals"
  if [ "$approved" = false ]; then
    [ -n "$report" ] || report=$'\n'"    no approval at all"
    echo "$label: pull request #$number ($title) by $author has no approval of its final commit ${head:0:12} by someone else:$report" >&2
    failures=$((failures + 1))
  fi
done

[ "$failures" -eq 0 ] || fail "$failures of the pull requests and direct pushes since $since were not approved on their final commit by someone other than the author; the ruleset of main does not require that, so this release does"
echo "$label: $total commit(s) since $since came from $pulls pull request(s), each approved on its final commit by someone other than its author" >&2
