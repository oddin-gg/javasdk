#!/usr/bin/env bash
#
# The approval check a v1.* tag passes before release.yml builds anything. The branch ruleset of
# main requires a pull request with one approval and a code owners' review, but it neither
# dismisses an approval when the pull request changes nor asks for the approval of its latest
# push, so a pull request can merge on the approval of an earlier version of itself. This check
# closes that gap at the release: every pull request merged into main since the previous release
# must have been approved on GitHub for its final commit by a release manager who did not write
# any of it. scripts/release-approval-check-test.sh runs it against a stub gh on every push.
#
# What it protects against, and what it does not. The release managers are trusted: they approve
# the publish in the maven-central environment and hold the signing key. The check is against a
# change by anyone else with write access that merged on an approval of an earlier version of
# itself. It does not detect a release manager who pushes commits under another identity and
# approves them: GitHub attributes a commit by its e-mail address and does not say who pushed it
# into a pull request, so that is out of scope. Nor can it protect itself: a tag runs this script,
# scripts/release-managers and release.yml from the tagged commit, so a change to the release
# controls since the previous release is for the approver of the publish to look at, with
# git diff <previous release> <tag> -- .github scripts/release-* RELEASING.md
#
#   scripts/release-approval-check.sh <commit>
#
# Run in a clone with every branch and tag (fetch-depth: 0), for the tagged commit, with gh (with
# GH_TOKEN and GITHUB_REPOSITORY). The commits checked are those after the previous release: the
# nearest v1.* tag reachable from it whose GitHub Release is published and no draft, a release
# candidate's included; a tag whose release never came out is passed over. Before the first
# release, those after the commit in scripts/release-approval-baseline, read from the tagged
# commit. Each commit is looked up for the pull request merged into main that left it; none, a
# direct push, fails as well. GitHub can answer with none for a commit shortly after its pull
# request merged, so before a commit counts as a direct push it is asked about again, up to
# APPROVAL_RETRIES times (default 4) after APPROVAL_RETRY_WAIT seconds each (default 10), and then
# looked for among the closed pull requests of main as the merge commit of one. Of each such pull request, its final head is asked, and its reviews:
# one APPROVED, for that head, by a release manager (a login in scripts/release-managers, read from
# the tagged commit like the baseline, compared without regard to case: this job has no secret to
# read the team with, and the repository is public, so anyone with a read-only account can leave an
# approval) who is neither the pull request's author nor the author or committer of any of its
# commits (pulls/{n}/commits), so whoever pushed to it cannot approve it. Every pull request that
# fails is named, with the approvals it had. The calls to GitHub are one per commit, two per pull
# request (a third, for its commits, when a release manager approved its final commit), and one per
# tag looked at until a release is found, plus the further pages of a long list, the rounds for a
# commit without a pull request, and, for those still without, the list of the closed pull requests
# of main. A call that
# fails, or does not answer within APPROVAL_TIMEOUT seconds (default 60), stops the release: no
# answer is never a pass.
set -euo pipefail

label=${1:?usage: release-approval-check.sh <commit>}
repository=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY names the repository whose pull requests count}
timeout=${APPROVAL_TIMEOUT:-60}
baseline_file=scripts/release-approval-baseline
managers_file=scripts/release-managers
# how many more rounds, each after a wait of so many seconds, a commit without a pull request gets
# before it counts as a direct push (the tests set the wait to 0)
retries=${APPROVAL_RETRIES:-4}
retry_wait=${APPROVAL_RETRY_WAIT:-10}

fail() {
  echo "$label: $*" >&2
  exit 1
}

[[ "$retries" =~ ^[0-9]+$ && "$retry_wait" =~ ^[0-9]+$ ]] || fail "APPROVAL_RETRIES and APPROVAL_RETRY_WAIT must be whole numbers"

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

lower() {
  printf '%s' "$1" | LC_ALL=C tr 'A-Z' 'a-z'
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

# The release managers, from the tagged commit: one login a line, comments and blank lines aside.
# An empty or unreadable list would leave nobody who can approve, so it stops the release here.
raw=$(git show "$commit:$managers_file" 2> /dev/null) || fail "$managers_file is missing: it lists the release managers, whose approval counts"
managers=$(printf '%s\n' "$raw" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | grep -v '^\(#\|$\)' || true)
managers=$(lower "$managers")
[ -n "$managers" ] || fail "$managers_file lists no release manager"
while IFS= read -r login; do
  [[ "$login" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]] || fail "$managers_file has a line that is no GitHub login: $login"
done <<< "$managers"
manager() {
  printf '%s\n' "$managers" | grep -qxF -- "$(lower "$1")"
}

commits=$(git rev-list "$start..$commit")
total=$(printf '%s\n' "$commits" | wc -l)

# The pull requests that left each commit: merged, into main. A commit may also be on open ones.
# GitHub can answer with none for a commit shortly after its pull request merged, so a commit with
# none is asked about again, in rounds with a wait before each (the wait is one for all the commits
# still without, so it stays below RETRIES x RETRY_WAIT whatever their number), and then the other
# way round: the pull requests merged into main, for one whose merge commit this is. That finds
# only the merge commit of a pull request: the one commit of a squash merge, the last of a rebase
# merge, whose other commits it cannot find (their hashes are the rebase's, not the pull request's).
# So the rounds are the fix, and the other way round the second chance for the last commit. A
# question that fails is no empty answer: it stops the release.
associated() {
  ask --paginate "repos/$repository/commits/$1/pulls?per_page=100" \
    --jq '.[] | select(.merged_at != null and .base.ref == "main") | .number'
}
numbers=()
record() {
  local number
  for number in $2; do
    [[ "$number" =~ ^[0-9]+$ ]] || fail "GitHub gave $number as a pull request number for $1"
    numbers+=("$number")
  done
}
pending=($commits)
round=0
while :; do
  still=()
  for sha in ${pending[@]+"${pending[@]}"}; do
    found=$(associated "$sha") || fail "could not ask GitHub which pull request left $sha"
    if [ -z "$found" ]; then
      still+=("$sha")
      continue
    fi
    record "$sha" "$found"
  done
  pending=(${still[@]+"${still[@]}"})
  [ "${#pending[@]}" -gt 0 ] && [ "$round" -lt "$retries" ] || break
  round=$((round + 1))
  echo "$label: GitHub lists no pull request yet for ${#pending[@]} commit(s); asking again in $retry_wait s (round $round of $retries)" >&2
  sleep "$retry_wait"
done
direct=()
if [ "${#pending[@]}" -gt 0 ]; then
  merged=$(ask --paginate "repos/$repository/pulls?state=closed&base=main&per_page=100" \
    --jq '.[] | select(.merged_at != null and .base.ref == "main" and .merge_commit_sha != null) | "\(.merge_commit_sha) \(.number)"') \
    || fail "could not ask GitHub for the pull requests merged into main"
  for sha in "${pending[@]}"; do
    found=$(printf '%s\n' "$merged" | awk -v sha="$sha" '$1 == sha { print $2 }')
    if [ -z "$found" ]; then
      direct+=("$sha")
      continue
    fi
    echo "$label: GitHub lists no pull request for ${sha:0:12}, but the merged pull requests of main have one with it as the merge commit" >&2
    record "$sha" "$found"
  done
fi

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
  title=$(plain "$(printf '%s\n' "$info" | sed -n '3,$p')")
  [[ "$head" =~ ^[0-9a-f]{40}$ ]] || fail "GitHub gave no final commit for pull request #$number: $head"
  # a login or "-" for none; every APPROVED review, whatever it was for: the ones that do not count
  # are reported
  approvals=$(ask --paginate "repos/$repository/pulls/$number/reviews?per_page=100" \
    --jq '.[] | select(.state == "APPROVED") | "\(.commit_id // "-") \(.user.login // "-")"') \
    || fail "could not ask GitHub for the reviews of pull request #$number"
  approved=false
  report=
  pushers=
  pushers_unknown=
  while read -r reviewed login; do
    [ -n "$reviewed" ] || continue
    if [ "$login" = "-" ]; then
      report+=$'\n'"    approved on ${reviewed:0:12} by a user GitHub does not name: does not count"
    elif [ -z "$author" ]; then
      report+=$'\n'"    approved by $login on ${reviewed:0:12}: GitHub names no author of the pull request, so who may approve it is unknown"
    elif [ "$(lower "$login")" = "$(lower "$author")" ]; then
      report+=$'\n'"    approved by $login on ${reviewed:0:12}: its author does not count"
    elif ! manager "$login"; then
      report+=$'\n'"    approved by $login on ${reviewed:0:12}: not a release manager"
    elif [ "$reviewed" != "$head" ]; then
      report+=$'\n'"    approved by $login on ${reviewed:0:12}: not the final commit"
    else
      # a release manager's approval of the final commit: unless they pushed to the pull request.
      # Asked once, and only now; the commits of a pull request are at most 250 to the API.
      if [ -z "$pushers" ] && [ -z "$pushers_unknown" ]; then
        listed=$(ask --paginate "repos/$repository/pulls/$number/commits?per_page=100" \
          --jq '.[] | "\(.sha[0:12]) \(.author.login // "-") \(.committer.login // "-")"') \
          || fail "could not ask GitHub for the commits of pull request #$number"
        count=$(printf '%s\n' "$listed" | grep -c . || true)
        if [ "$count" -eq 0 ]; then
          pushers_unknown="GitHub lists no commit of it"
        elif [ "$count" -ge 250 ]; then
          pushers_unknown="it has $count commits, as many as GitHub lists at most"
        elif unlinked=$(printf '%s\n' "$listed" | awk '$2 == "-" || $3 == "-" { print $1; exit }') && [ -n "$unlinked" ]; then
          pushers_unknown="commit $unlinked has an author or committer GitHub links to no account"
        else
          pushers=$(printf '%s\n' "$listed" | awk '{ print $2; print $3 }')
          pushers=$(lower "$pushers")
        fi
      fi
      if [ -n "$pushers_unknown" ]; then
        report+=$'\n'"    approved by $login on ${reviewed:0:12}: who pushed to the pull request cannot be told ($pushers_unknown)"
      elif printf '%s\n' "$pushers" | grep -qxF -- "$(lower "$login")"; then
        report+=$'\n'"    approved by $login on ${reviewed:0:12}: $login wrote or committed a commit of the pull request"
      else
        approved=true
      fi
    fi
  done <<< "$approvals"
  if [ "$approved" = false ]; then
    [ -n "$report" ] || report=$'\n'"    no approval at all"
    echo "$label: pull request #$number ($title) by ${author:-nobody GitHub names} has no approval of its final commit ${head:0:12} by a release manager who did not write it:$report" >&2
    failures=$((failures + 1))
  fi
done

[ "$failures" -eq 0 ] || fail "$failures of the pull requests and direct pushes since $since were not approved on their final commit by a release manager who did not write it; the ruleset of main does not require that, so this release does"
echo "$label: $total commit(s) since $since came from $pulls pull request(s), each approved on its final commit by a release manager who did not write it" >&2
