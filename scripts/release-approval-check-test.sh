#!/usr/bin/env bash
#
# Runs scripts/release-approval-check.sh against a scratch repository and a stub gh, and fails
# unless it passes the releases whose every pull request was approved on its final commit by
# someone other than its author, and refuses the others for the reason it should: an approval of
# an earlier commit only, of the author only, a dismissed or other review, a commit no pull
# request merged into main left, a call to GitHub that fails or stalls, a baseline that is not
# set. Then each rule is broken once in a copy of the script, and the case for that rule must
# fail on the copy, so a rule that stopped checking anything shows up too. next.yml runs it on
# every push. Needs git and jq (the stub gh filters with it).
set -euo pipefail

for tool in git jq; do
  command -v "$tool" > /dev/null || { echo "needs $tool" >&2; exit 1; }
done

root=$(cd "$(dirname "$0")/.." && pwd)
real=$root/scripts/release-approval-check.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
api=$work/api
repo=$work/repo

# Stub gh. A call is answered from the file of its endpoint in $GH_STUB_API, the path after
# repos/example/repo/ without its query: commits/<sha>/pulls.json, pulls/<n>.json and
# pulls/<n>/reviews.json. A .fail file next to it makes the call fail, a .stall one makes it
# answer only after 10 seconds, no file at all is a 404. Like gh, it returns a list in pages of
# per_page (30 without one) and all of them only with --paginate, running the --jq filter on each
# page. Every call is recorded.
mkdir -p "$work/bin"
cat > "$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
api=${GH_STUB_API:?}
echo "gh $*" >> "$api/calls"
paginate=false endpoint= filter=.
while [ $# -gt 0 ]; do
  case "$1" in
    --jq) filter=$2; shift 2 ;;
    --paginate) paginate=true; shift ;;
    api) shift ;;
    *) endpoint=$1; shift ;;
  esac
done
path=${endpoint#repos/example/repo/}
path=${path%%\?*}
per=30
case "$endpoint" in
  *per_page=*) per=${endpoint#*per_page=}; per=${per%%&*} ;;
esac
if [ -e "$api/$path.fail" ]; then
  echo "gh: HTTP 502" >&2
  exit 1
fi
if [ -e "$api/$path.stall" ]; then
  sleep 10
fi
if [ ! -e "$api/$path.json" ]; then
  echo "gh: HTTP 404 ($path)" >&2
  exit 1
fi
file=$api/$path.json
if [ "$(jq -r type "$file")" != array ]; then
  jq -r "$filter" "$file"
  exit 0
fi
if [ "$paginate" = true ]; then
  length=$(jq length "$file")
  for ((start = 0; start == 0 || start < length; start += per)); do
    jq --argjson s "$start" --argjson n "$per" '.[$s:$s + $n]' "$file" | jq -r "$filter"
  done
else
  jq --argjson n "$per" '.[:$n]' "$file" | jq -r "$filter"
fi
STUB
chmod +x "$work/bin/gh"

declare -A sha

# new_repo <baseline>: the commits root, c1, c2 and c3, c3 the tagged one, and the baseline file
# at c3 as the line given: unset, the commit of this name, or the text as it is
new_repo() {
  rm -rf "$repo" "$api"
  mkdir -p "$api"
  git init -q -b main "$repo"
  git -C "$repo" config user.name test
  git -C "$repo" config user.email test@example.invalid
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config tag.gpgsign false
  local name line
  for name in root c1 c2; do
    echo "$name" > "$repo/file"
    git -C "$repo" add -A
    git -C "$repo" commit -q -m "$name"
    sha[$name]=$(git -C "$repo" rev-parse HEAD)
  done
  line=${sha[${1}]:-$1}
  mkdir -p "$repo/scripts"
  printf '# the commit main pointed to when the rule began\n%s\n' "$line" > "$repo/scripts/release-approval-baseline"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m c3
  sha[c3]=$(git -C "$repo" rev-parse HEAD)
}

# a made-up commit of a pull request, the n-th
head_of() { printf '%-40x' "$1" | tr ' ' 0; }

# pull <number> <author> <head> [title]: the pull request as pulls/<n> gives it
pull() {
  mkdir -p "$api/pulls"
  jq -n --arg head "$3" --arg author "$2" --arg title "${4:-Pull request $1}" \
    '{head: {sha: $head}, user: {login: $author}, title: $title}' > "$api/pulls/$1.json"
}

# reviews <number> <state:commit:login>...: the reviews, oldest first
reviews() {
  local number=$1 entry
  shift
  mkdir -p "$api/pulls/$number"
  for entry in "$@"; do
    IFS=: read -r state commit login <<< "$entry"
    jq -n --arg s "$state" --arg c "$commit" --arg l "$login" '{state: $s, commit_id: $c, user: {login: $l}}'
  done | jq -s . > "$api/pulls/$number/reviews.json"
}

# left <commit> <number>...: the pull requests merged into main that left the commit, beside one
# that is open and one merged into another branch, which do not count
left() {
  local commit=${sha[$1]:-$1}
  shift
  mkdir -p "$api/commits/$commit"
  jq -n '[$ARGS.positional[] | tonumber | {number: ., merged_at: "2026-10-01T00:00:00Z", base: {ref: "main"}}]
    + [{number: 999, merged_at: null, base: {ref: "main"}}, {number: 998, merged_at: "2026-10-01T00:00:00Z", base: {ref: "next"}}]' \
    --args "$@" > "$api/commits/$commit/pulls.json"
}

# the two pull requests every passing case starts from: #1 left c1 and c2 (a rebase merge puts
# each commit of a pull request on main), #2 left c3, each approved on its final head by bob
standard() {
  left c1 1
  left c2 1
  left c3 2
  pull 1 alice "$(head_of 1)"
  pull 2 carol "$(head_of 2)"
  reviews 1 "APPROVED:$(head_of 1):bob"
  reviews 2 "APPROVED:$(head_of 2):bob"
}

failures=0
failed() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

script=$real
timeout=5
# expect pass|fail <text>...: runs the script under test for c3; the text must be in what it says,
# unless it starts with ! and then must not be
expect() {
  local want=$1 text got=pass
  shift
  : > "$api/calls"
  (cd "$repo" && PATH=$work/bin:$PATH GH_STUB_API=$api GITHUB_REPOSITORY=example/repo APPROVAL_TIMEOUT=$timeout \
    bash "$script" "${sha[c3]}") 2> "$work/err" || got=fail
  if [ "$got" != "$want" ]; then
    reason="expected $want, got $got: $(cat "$work/err")"
    return 1
  fi
  for text in "$@"; do
    if [ "${text#!}" != "$text" ]; then
      if grep -qF -- "${text#!}" "$work/err"; then
        reason="said \"${text#!}\": $(cat "$work/err")"
        return 1
      fi
    elif ! grep -qF -- "$text" "$work/err"; then
      reason="did not say \"$text\": $(cat "$work/err")"
      return 1
    fi
  done
}

# the endpoints asked, in order, as the script asked them
asked() {
  sed -e 's/^gh api //' -e 's/ --jq .*//' -e 's#repos/example/repo/##' "$api/calls"
}

# --- the cases: each builds its state and returns 1 unless the script behaves as it should

case_all_approved() {
  new_repo root
  standard
  # #1 has an approval of its author and of an earlier commit beside bob's, #2 one comment
  reviews 1 "APPROVED:$(head_of 1):alice" "APPROVED:$(head_of 100):dave" "APPROVED:$(head_of 1):bob"
  expect pass "3 commit(s) since the baseline" "came from 2 pull request(s)" || return 1
  # one question per commit, newest first; two per pull request, once each, however many commits
  local want
  want="--paginate commits/${sha[c3]}/pulls?per_page=100
--paginate commits/${sha[c2]}/pulls?per_page=100
--paginate commits/${sha[c1]}/pulls?per_page=100
pulls/1
--paginate pulls/1/reviews?per_page=100
pulls/2
--paginate pulls/2/reviews?per_page=100"
  if [ "$(asked)" != "$want" ]; then
    reason="asked
$(asked)
not
$want"
    return 1
  fi
}

case_earlier_commit() {
  new_repo root
  standard
  reviews 2 "APPROVED:$(head_of 200):bob"
  expect fail "pull request #2 (Pull request 2) by carol has no approval of its final commit" \
    "approved by bob on $(head_of 200 | cut -c1-12): not the final commit" "!pull request #1" || return 1
}

case_author_only() {
  new_repo root
  standard
  reviews 2 "APPROVED:$(head_of 2):carol"
  expect fail "pull request #2 (Pull request 2) by carol" "approved by carol on $(head_of 2 | cut -c1-12): its author does not count" \
    "!pull request #1" || return 1
}

case_other_reviews() {
  new_repo root
  standard
  reviews 2 "COMMENTED:$(head_of 2):bob" "CHANGES_REQUESTED:$(head_of 2):dave" "DISMISSED:$(head_of 2):erin"
  expect fail "pull request #2 (Pull request 2) by carol" "no approval at all" || return 1
}

case_no_review() {
  new_repo root
  standard
  reviews 2
  expect fail "pull request #2" "no approval at all" || return 1
}

case_direct_push() {
  new_repo root
  standard
  left c2
  expect fail "commit ${sha[c2]:0:12} (c2) came from no pull request merged into main: a direct push" || return 1
}

case_only_other_base() {
  new_repo root
  standard
  # merged into next, or still open: not into main
  mkdir -p "$api/commits/${sha[c1]}"
  jq -n '[{number: 998, merged_at: "2026-10-01T00:00:00Z", base: {ref: "next"}}, {number: 999, merged_at: null, base: {ref: "main"}}]' \
    > "$api/commits/${sha[c1]}/pulls.json"
  expect fail "commit ${sha[c1]:0:12} (c1) came from no pull request merged into main" || return 1
}

case_every_failure_named() {
  new_repo root
  standard
  left c2 3
  pull 3 frank "$(head_of 3)" 'Fix: ::error::injected'
  reviews 3
  reviews 2 "APPROVED:$(head_of 200):bob"
  expect fail "pull request #2 (Pull request 2) by carol" "pull request #3 (Fix: ::error::injected) by frank" \
    "2 of the pull requests and direct pushes" || return 1
}

case_previous_tag() {
  new_repo unset
  git -C "$repo" tag -a -m rc v1.0.0-rc.1 "${sha[c1]}"
  # c1 and what is before it were released; the tag of this very release is on c3, and a 0.x tag
  # and one that is not a version are no start
  git -C "$repo" tag -a -m this v1.0.0-rc.2 "${sha[c3]}"
  git -C "$repo" tag -a -m old v0.0.60 "${sha[c2]}"
  git -C "$repo" tag -a -m other vtest "${sha[c2]}"
  standard
  rm "$api/commits/${sha[c1]}/pulls.json"
  expect pass "2 commit(s) since v1.0.0-rc.1" "came from 2 pull request(s)" || return 1
  if asked | grep -q "${sha[c1]}\|${sha[root]}"; then
    reason="asked for a commit the previous release had: $(asked)"
    return 1
  fi
}

case_latest_tag() {
  new_repo unset
  git -C "$repo" tag -a -m rc1 v1.0.0-rc.1 "${sha[c1]}"
  git -C "$repo" tag -a -m rc2 v1.0.0-rc.2 "${sha[c2]}"
  standard
  rm "$api/commits/${sha[c1]}/pulls.json" "$api/commits/${sha[c2]}/pulls.json"
  expect pass "1 commit(s) since v1.0.0-rc.2" "came from 1 pull request(s)" || return 1
}

case_baseline() {
  new_repo c1
  standard
  rm "$api/commits/${sha[c1]}/pulls.json"
  expect pass "2 commit(s) since the baseline ${sha[c1]:0:12}" || return 1
}

case_placeholder() {
  new_repo unset
  standard
  expect fail "still holds the placeholder" "set the baseline" || return 1
}

case_baseline_malformed() {
  new_repo abc123
  standard
  expect fail "must hold one full SHA" "set the baseline" || return 1
}

case_baseline_missing() {
  new_repo root
  standard
  git -C "$repo" rm -q scripts/release-approval-baseline
  git -C "$repo" commit -q -m "no baseline"
  sha[c3]=$(git -C "$repo" rev-parse HEAD)
  left c3 2
  expect fail "scripts/release-approval-baseline is missing: set the baseline" || return 1
}

case_baseline_not_ancestor() {
  new_repo root
  standard
  git -C "$repo" checkout -q -b side "${sha[c1]}"
  echo side > "$repo/other"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m side
  local other
  other=$(git -C "$repo" rev-parse HEAD)
  git -C "$repo" checkout -q main
  printf '%s\n' "$other" > "$repo/scripts/release-approval-baseline"
  git -C "$repo" commit -q -am "baseline elsewhere"
  sha[c3]=$(git -C "$repo" rev-parse HEAD)
  left c3 2
  expect fail "the baseline ${other:0:12} is not an ancestor" || return 1
}

case_api_error_commit() {
  new_repo root
  standard
  : > "$api/commits/${sha[c2]}/pulls.fail"
  expect fail "could not ask GitHub which pull request left ${sha[c2]}" || return 1
}

case_api_error_pull() {
  new_repo root
  standard
  : > "$api/pulls/2.fail"
  expect fail "could not ask GitHub for pull request #2" || return 1
}

case_api_error_reviews() {
  new_repo root
  standard
  : > "$api/pulls/2/reviews.fail"
  expect fail "could not ask GitHub for the reviews of pull request #2" || return 1
}

case_api_stall() {
  new_repo root
  standard
  : > "$api/pulls/2/reviews.stall"
  timeout=1
  local status=0
  expect fail "could not ask GitHub for the reviews of pull request #2" || status=1
  timeout=5
  return $status
}

case_no_head() {
  new_repo root
  standard
  jq 'del(.head)' "$api/pulls/2.json" > "$work/pull" && mv "$work/pull" "$api/pulls/2.json"
  expect fail "GitHub gave no final commit for pull request #2" || return 1
}

# more than a page of pull requests for one commit: the merged one is the 101st
case_many_pulls() {
  new_repo root
  standard
  jq -n '[range(1000; 1100) | {number: ., merged_at: null, base: {ref: "main"}}]
    + [{number: 2, merged_at: "2026-10-01T00:00:00Z", base: {ref: "main"}}]' > "$api/commits/${sha[c3]}/pulls.json"
  expect pass "came from 2 pull request(s)" || return 1
}

# more than a page of reviews: the approval is the 101st
case_many_reviews() {
  new_repo root
  standard
  local entries=() i
  for i in $(seq 100); do
    entries+=("APPROVED:$(head_of 300):reviewer$i")
  done
  reviews 2 "${entries[@]}" "APPROVED:$(head_of 2):bob"
  expect pass "came from 2 pull request(s)" || return 1
}

case_shallow() {
  new_repo root
  standard
  local clone=$work/shallow got=pass
  rm -rf "$clone"
  git clone -q --depth 1 "file://$repo" "$clone"
  (cd "$clone" && PATH=$work/bin:$PATH GH_STUB_API=$api GITHUB_REPOSITORY=example/repo bash "$script" "${sha[c3]}") 2> "$work/err" || got=fail
  if [ "$got" != fail ] || ! grep -qF "this clone is shallow" "$work/err"; then
    reason="a shallow clone was not refused as one: $got, $(cat "$work/err")"
    return 1
  fi
}

reason=
all_cases="case_all_approved case_earlier_commit case_author_only case_other_reviews case_no_review
case_direct_push case_only_other_base case_every_failure_named case_previous_tag case_latest_tag
case_baseline case_placeholder case_baseline_malformed case_baseline_missing case_baseline_not_ancestor
case_api_error_commit case_api_error_pull case_api_error_reviews case_api_stall case_no_head
case_many_pulls case_many_reviews case_shallow"
for name in $all_cases; do
  if "$name"; then
    echo "ok   ${name#case_}"
  else
    failed "${name#case_}: $reason"
  fi
done

# Each rule broken once, in a copy of the script: the case for it must then fail. A break names
# text that occurs exactly once in the script, so it lands where it is meant to.
# breaks <case> <text> <replacement> <the rule>
breaks() {
  local name=$1 old=$2 new=$3 rule=$4 copy=$work/mutated.sh
  if ! python3 - "$real" "$copy" "$old" "$new" <<'PY'
import sys
source, copy, old, new = sys.argv[1:]
text = open(source).read()
if text.count(old) != 1:
    sys.exit("%d times, not once, in the script: %s" % (text.count(old), old))
open(copy, "w").write(text.replace(old, new))
PY
  then
    failed "could not break the copy for \"$rule\""
    return
  fi
  script=$copy
  reason=
  if "case_$name"; then
    failed "the case $name passed on a copy that $rule"
  else
    echo "ok   $name fails when the script $rule (${reason:0:90})"
  fi
  script=$real
}

breaks earlier_commit 'elif [ "$reviewed" != "$head" ]; then' 'elif false; then' \
  "takes an approval of any commit"
breaks author_only 'if [ "$login" = "$author" ]; then' 'if false; then' \
  "takes the author's own approval"
breaks direct_push 'direct+=("$sha")' ':' \
  "lets a direct push pass"
breaks previous_tag "'refs/tags/v1.*'" "'refs/tags/v1.nothing*'" \
  "ignores the previous release tag"
breaks latest_tag 'if [ -z "$nearest" ] || [ "$count" -lt "$nearest" ]; then' 'if [ -z "$nearest" ] || [ "$count" -gt "$nearest" ]; then' \
  "starts from the oldest release tag, not the nearest"
breaks baseline 'start=$(git rev-parse --verify -q "$lines^{commit}") ||' 'start=$(git rev-list --max-parents=0 "$commit") ||' \
  "ignores the baseline"
breaks placeholder '    unset) fail "$baseline_file still holds the placeholder: set the baseline to the commit main pointed to when the rule began (the rename of next to main)" ;;' \
  '    unset) lines=$(git rev-list --max-parents=0 "$commit") ;;' \
  "accepts the placeholder"
breaks api_error_commit '|| fail "could not ask GitHub which pull request left $sha"' '|| true' \
  "goes on after a failed question for a commit"
breaks api_error_reviews '|| fail "could not ask GitHub for the reviews of pull request #$number"' '|| true' \
  "goes on after a failed question for the reviews"
breaks api_stall 'limit=(timeout "$timeout")' 'limit=()' \
  "waits for GitHub without a limit"
breaks many_pulls 'ask --paginate "repos/$repository/commits/$sha/pulls?per_page=100"' 'ask "repos/$repository/commits/$sha/pulls?per_page=100"' \
  "reads the first page of a commit's pull requests only"
breaks many_reviews 'ask --paginate "repos/$repository/pulls/$number/reviews?per_page=100"' 'ask "repos/$repository/pulls/$number/reviews?per_page=100"' \
  "reads the first page of the reviews only"
breaks other_reviews 'select(.state == "APPROVED") | ' '' \
  "counts a review that is not an approval"
breaks all_approved '| sort -un)' '| sort -n)' \
  "asks about a pull request once for each of its commits"
breaks direct_push '.merged_at != null and ' '' \
  "takes an open pull request for the one that merged"
breaks only_other_base ' and .base.ref == "main"' '' \
  "takes a pull request merged into another branch"
breaks shallow '[ "$(git rev-parse --is-shallow-repository)" = false ] ||' 'true ||' \
  "checks a shallow clone"

if [ "$failures" -ne 0 ]; then
  echo "$failures failures" >&2
  exit 1
fi
echo "the approval check refuses what it should"
