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
# repos/example/repo/ without its query: commits/<sha>/pulls.json, pulls/<n>.json,
# pulls/<n>/reviews.json, pulls/<n>/commits.json and releases/tags/<tag>.json. A .fail file next to it makes the call fail, a .stall one makes it
# answer only after 10 seconds, a .partial one makes a paginated call answer its first page and then
# fail, a .late one (holding N) makes the first N calls answer an empty list, as GitHub does for a
# commit just after its pull request merged, and the later ones as usual, or fail when a .latefail
# is there too; no file at all is a 404. Like gh, it returns a list in pages of
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
if [ -e "$api/$path.late" ]; then
  asked=$(cat "$api/$path.count" 2> /dev/null || echo 0)
  if [ "$asked" -lt "$(cat "$api/$path.late")" ]; then
    echo $((asked + 1)) > "$api/$path.count"
    echo '[]' | jq -r "$filter"
    exit 0
  elif [ -e "$api/$path.latefail" ]; then
    echo "gh: HTTP 502" >&2
    exit 1
  fi
fi
if [ -e "$api/$path.partial" ] && [ "$paginate" = true ]; then
  # the first page, and then the next one fails
  jq --argjson n "$per" '.[:$n]' "$api/$path.json" | jq -r "$filter"
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

# the release managers file at c3: comments, blanks, a mixed-case login and padding, which count as
# they are; a case sets $managers_text to say otherwise
default_managers='# release managers

Bob
  dave
erin
'

# the direct pushes excused at c3: none; a case sets $exceptions_text to say otherwise, with @root@,
# @c1@ and @c2@ for the commits made before it
default_exceptions='# no direct push is excused
'

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
  printf '%s' "${managers_text-$default_managers}" > "$repo/scripts/release-managers"
  local text=${exceptions_text-$default_exceptions} name
  for name in root c1 c2; do
    text=${text//@$name@/${sha[$name]}}
  done
  printf '%s' "$text" > "$repo/scripts/release-approval-exceptions"
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
    jq -n --arg s "$state" --arg c "$commit" --arg l "$login" \
      '{state: $s, commit_id: $c, user: (if $l == "-" then null else {login: $l} end)}'
  done | jq -s . > "$api/pulls/$number/reviews.json"
}

# pr_commits <number> <author:committer>...: the commits of the pull request, - for a login
# GitHub does not give (an e-mail linked to no account)
pr_commits() {
  local number=$1
  shift
  mkdir -p "$api/pulls/$number"
  jq -n '[$ARGS.positional | to_entries[] | (.value | split(":")) as $p
    | {sha: ("c\(.key)aaaaaaaaaaaaaaaaaaaaaaaa"),
       author: (if $p[0] == "-" then null else {login: $p[0]} end),
       committer: (if $p[1] == "-" then null else {login: $p[1]} end)}]' --args "$@" > "$api/pulls/$number/commits.json"
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

# late <commit> <n>: the first n questions for the commit's pull requests are answered with none
late() {
  echo "$2" > "$api/commits/${sha[$1]:-$1}/pulls.late"
}

# merged_as <number> <commit>: the pull request is among the closed ones of main, with the commit
# as its merge commit (the list pulls?state=closed gives, beside one open, one merged into
# another branch and one with another merge commit, which do not count for it)
merged_as() {
  local commit=${sha[$2]:-$2}
  jq --argjson n "$1" --arg c "$commit" '. + [{number: $n, merged_at: "2026-10-01T00:00:00Z", base: {ref: "main"}, merge_commit_sha: $c}]' \
    "$api/pulls.json" > "$work/closed" && mv "$work/closed" "$api/pulls.json"
}

# release <tag> [draft]: GitHub has a release for the tag, published unless it is a draft
release() {
  mkdir -p "$api/releases/tags"
  jq -n --argjson draft "$([ "${2:-}" = draft ] && echo true || echo false)" '{draft: $draft}' > "$api/releases/tags/$1.json"
}

# the two pull requests every passing case starts from: #1 left c1 and c2 (a rebase merge puts
# each commit of a pull request on main), #2 left c3, each approved on its final head by bob
standard() {
  jq -n '[{number: 997, merged_at: null, base: {ref: "main"}, merge_commit_sha: "x"},
    {number: 996, merged_at: "2026-10-01T00:00:00Z", base: {ref: "main"}, merge_commit_sha: null}]' > "$api/pulls.json"
  left c1 1
  left c2 1
  left c3 2
  pull 1 alice "$(head_of 1)"
  pull 2 carol "$(head_of 2)"
  reviews 1 "APPROVED:$(head_of 1):bob"
  reviews 2 "APPROVED:$(head_of 2):bob"
  # alice wrote hers, one of them committed through GitHub's web editor
  pr_commits 1 alice:alice alice:web-flow
  pr_commits 2 carol:carol
}

failures=0
failed() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

script=$real
timeout=5
# a directory put before the others on the PATH of the script under test, when a case sets it
stubs=
# expect pass|fail <text>...: runs the script under test for c3; the text must be in what it says,
# unless it starts with ! and then must not be
expect() {
  local want=$1 text got=pass
  shift
  : > "$api/calls"
  (cd "$repo" && PATH=${stubs:+$stubs:}$work/bin:$PATH GH_STUB_API=$api GITHUB_REPOSITORY=example/repo APPROVAL_TIMEOUT=$timeout APPROVAL_RETRY_WAIT=0 \
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
--paginate pulls/1/commits?per_page=100
pulls/2
--paginate pulls/2/reviews?per_page=100
--paginate pulls/2/commits?per_page=100"
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
  # a release manager, writing as another case than GitHub gives the login in
  pull 2 Erin "$(head_of 2)"
  reviews 2 "APPROVED:$(head_of 2):erin"
  expect fail "pull request #2 (Pull request 2) by Erin" "approved by erin on $(head_of 2 | cut -c1-12): its author does not count" \
    "!pull request #1" || return 1
}

case_other_reviews() {
  new_repo root
  standard
  reviews 2 "COMMENTED:$(head_of 2):bob" "CHANGES_REQUESTED:$(head_of 2):dave" "DISMISSED:$(head_of 2):erin"
  expect fail "pull request #2 (Pull request 2) by carol" "no approval at all" || return 1
}

case_not_release_manager() {
  new_repo root
  standard
  reviews 2 "APPROVED:$(head_of 2):mallory"
  expect fail "pull request #2 (Pull request 2) by carol" "approved by mallory on $(head_of 2 | cut -c1-12): not a release manager" || return 1
  if asked | grep -q "pulls/2/commits"; then
    reason="asked for the commits of a pull request nobody who counts approved: $(asked)"
    return 1
  fi
}

# bob, a release manager, wrote a commit of the pull request, or committed one: his approval is no
# other person's review; erin's, another release manager's, is
case_pusher_author() {
  new_repo root
  standard
  pr_commits 2 carol:carol BOB:carol
  expect fail "pull request #2 (Pull request 2) by carol" "approved by bob on $(head_of 2 | cut -c1-12): bob wrote or committed a commit of the pull request" || return 1
}

case_pusher_committer() {
  new_repo root
  standard
  pr_commits 2 carol:carol carol:bob
  expect fail "approved by bob on $(head_of 2 | cut -c1-12): bob wrote or committed a commit of the pull request" || return 1
}

case_other_manager_approves() {
  new_repo root
  standard
  pr_commits 2 carol:carol bob:bob
  reviews 2 "APPROVED:$(head_of 2):bob" "APPROVED:$(head_of 2):erin"
  expect pass "came from 2 pull request(s)" || return 1
}

# a commit whose author or committer GitHub links to no account: who pushed cannot be told
case_unlinked_author() {
  new_repo root
  standard
  pr_commits 2 carol:carol -:carol
  expect fail "pull request #2 (Pull request 2) by carol" "who pushed to the pull request cannot be told (commit c1aaaaaaaaaa has an author or committer GitHub links to no account)" || return 1
}

case_unlinked_committer() {
  new_repo root
  standard
  pr_commits 2 carol:carol carol:-
  expect fail "who pushed to the pull request cannot be told" || return 1
}

# the committer of an edit made on github.com is web-flow, GitHub's own account, with the editor as
# author: it can approve nothing, so it names nobody who could, and the commit is no reason to fail
case_web_edit() {
  new_repo root
  standard
  pr_commits 2 carol:web-flow
  expect pass "came from 2 pull request(s)" || return 1
}

# more than a page of commits: the last one, the 101st, is bob's
case_many_commits() {
  new_repo root
  standard
  local entries=() i
  for i in $(seq 100); do
    entries+=(carol:carol)
  done
  pr_commits 2 "${entries[@]}" bob:bob
  expect fail "approved by bob on $(head_of 2 | cut -c1-12): bob wrote or committed a commit of the pull request" || return 1
}

# GitHub lists 250 commits of a pull request at most; a pull request that has that many may have more
case_commit_limit() {
  new_repo root
  standard
  local entries=() i
  for i in $(seq 250); do
    entries+=(carol:carol)
  done
  pr_commits 2 "${entries[@]}"
  expect fail "who pushed to the pull request cannot be told (it has 250 commits, as many as GitHub lists at most)" || return 1
}

case_no_commits() {
  new_repo root
  standard
  pr_commits 2
  expect fail "who pushed to the pull request cannot be told (GitHub lists no commit of it)" || return 1
}

case_managers_empty() {
  managers_text='# nobody yet
'
  new_repo root
  unset managers_text
  standard
  expect fail "scripts/release-managers lists no release manager" || return 1
}

case_managers_missing() {
  new_repo root
  standard
  git -C "$repo" rm -q scripts/release-managers
  git -C "$repo" commit -q -m "no managers"
  sha[c3]=$(git -C "$repo" rev-parse HEAD)
  left c3 2
  expect fail "scripts/release-managers is missing" || return 1
}

case_managers_bad_line() {
  managers_text='bob
bob smith
'
  new_repo root
  unset managers_text
  standard
  expect fail "scripts/release-managers has a line that is no GitHub login: bob smith" || return 1
}

# a pull request GitHub gives no author for (a deleted account), approved by a release manager
case_no_author() {
  new_repo root
  standard
  jq 'del(.user)' "$api/pulls/2.json" > "$work/pull" && mv "$work/pull" "$api/pulls/2.json"
  expect fail "pull request #2 (Pull request 2) by nobody GitHub names" "GitHub names no author of the pull request" "!pull request #1" || return 1
}

# an approval of the final commit by a user GitHub does not name (a deleted account)
case_approval_no_login() {
  new_repo root
  standard
  reviews 2 "APPROVED:$(head_of 2):-"
  expect fail "pull request #2 (Pull request 2) by carol" "by a user GitHub does not name: does not count" || return 1
}

# a title is text anyone may write: control characters, an escape sequence and a line break are
# not carried to the log, which would let it forge a line or a workflow command
case_title_controls() {
  new_repo root
  standard
  pull 2 carol "$(head_of 2)" $'Fix\r\x1b[31mred\x1b[0m\tend\n::error::forged'
  reviews 2 "APPROVED:$(head_of 200):bob"
  expect fail "pull request #2 (Fix[31mred[0mend::error::forged) by carol" || return 1
  if [ -n "$(LC_ALL=C tr -d '[:print:]\n' < "$work/err")" ]; then
    reason="the refusal carries a control character: $(cat "$work/err" | od -c | head -5)"
    return 1
  fi
}

# the commits of the pull request: the call fails, stalls, or fails after a clean first page
case_api_error_commits() {
  new_repo root
  standard
  : > "$api/pulls/2/commits.fail"
  expect fail "could not ask GitHub for the commits of pull request #2" || return 1
}

case_api_stall_commits() {
  new_repo root
  standard
  : > "$api/pulls/2/commits.stall"
  timeout=1
  local status=0
  expect fail "could not ask GitHub for the commits of pull request #2" || status=1
  timeout=5
  return $status
}

# a full first page of carol's commits, which do not name bob, then the second page, with bob's
# commit, fails: the commits seen are not all of them, and bob's approval must not pass
case_commits_partial() {
  new_repo root
  standard
  local entries=() i
  for i in $(seq 100); do
    entries+=(carol:carol)
  done
  pr_commits 2 "${entries[@]}" bob:bob
  : > "$api/pulls/2/commits.partial"
  expect fail "could not ask GitHub for the commits of pull request #2" || return 1
}

# one commit left by two pull requests merged into main: both are checked
case_two_pulls_one_commit() {
  new_repo root
  standard
  left c3 2 3
  pull 3 frank "$(head_of 3)"
  pr_commits 3 frank:frank
  reviews 3 "APPROVED:$(head_of 300):bob"
  expect fail "pull request #3 (Pull request 3) by frank" "approved by bob on $(head_of 300 | cut -c1-12): not the final commit" \
    "!pull request #2" || return 1
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
  # asked once, and four times more, in rounds, before it counts; the merged pull requests of main,
  # the other way round, once
  if [ "$(asked | grep -c "^--paginate commits/${sha[c2]}/pulls")" -ne 5 ] || [ "$(asked | grep -c '^--paginate pulls?state=closed&base=main')" -ne 1 ]; then
    reason="did not ask five times for the commit and once for the merged pull requests: $(asked)"
    return 1
  fi
  if [ "$(grep -c "asking again" "$work/err")" -ne 4 ]; then
    reason="did not say it asked again four times: $(cat "$work/err")"
    return 1
  fi
}

# GitHub lists no pull request for a commit just after its pull request merged: the first questions
# are answered with none, and the commit is no direct push once a later answer has the pull request
case_late_association() {
  new_repo root
  standard
  late c3 2
  expect pass "GitHub lists no pull request yet for 1 commit(s); asking again" "came from 2 pull request(s)" "!direct push" || return 1
  if [ "$(asked | grep -c "^--paginate commits/${sha[c3]}/pulls")" -ne 3 ]; then
    reason="did not ask three times for the commit: $(asked)"
    return 1
  fi
  if asked | grep -q 'pulls?state=closed'; then
    reason="asked for the merged pull requests of main though every commit had its pull request by then: $(asked)"
    return 1
  fi
}

# the last round that counts: the fifth answer, after four rounds, has the pull request
case_late_last_round() {
  new_repo root
  standard
  late c2 4
  expect pass "came from 2 pull request(s)" || return 1
}

# the fifth answer is the first with the pull request, one too late: a direct push
case_late_too_long() {
  new_repo root
  standard
  late c2 5
  expect fail "commit ${sha[c2]:0:12} (c2) came from no pull request merged into main: a direct push" || return 1
}

# two commits still without a pull request share the wait of a round
case_late_two_commits() {
  new_repo root
  standard
  late c2 1
  late c3 1
  # a sleep that waits for nothing and says it was called
  mkdir -p "$work/sleep"
  printf '#!/usr/bin/env bash\necho "$*" >> "%s/sleeps"\n' "$api" > "$work/sleep/sleep"
  chmod +x "$work/sleep/sleep"
  stubs=$work/sleep
  local status=0
  expect pass "came from 2 pull request(s)" || status=1
  stubs=
  [ "$status" -eq 0 ] || return 1
  if [ "$(grep -c . "$api/sleeps")" -ne 1 ]; then
    reason="did not wait once for both commits: $(cat "$api/sleeps")"
    return 1
  fi
}

# the second question for a commit fails: that is no empty answer
case_late_then_error() {
  new_repo root
  standard
  late c3 1
  : > "$api/commits/${sha[c3]}/pulls.latefail"
  expect fail "could not ask GitHub which pull request left ${sha[c3]}" "!a direct push" || return 1
}

# GitHub never lists the pull request for the last commit of a rebase merge, but the closed pull
# requests of main have it by its merge commit: it is judged like any other
case_merge_commit_fallback() {
  new_repo root
  standard
  left c3
  merged_as 2 c3
  expect pass "GitHub lists no pull request for ${sha[c3]:0:12}, but the merged pull requests of main have one" \
    "came from 2 pull request(s)" "!direct push" || return 1
}

case_merge_commit_fallback_judged() {
  new_repo root
  standard
  left c3
  merged_as 2 c3
  reviews 2 "APPROVED:$(head_of 200):bob"
  expect fail "pull request #2 (Pull request 2) by carol has no approval of its final commit" || return 1
}

# the other way round finds the merge commit only: an intermediate commit of that pull request is a
# direct push for it, as are a commit of an open pull request and of one merged into another branch
case_merge_commit_fallback_only_merge_commit() {
  new_repo root
  standard
  left c2
  left c3
  merged_as 2 c3
  expect fail "commit ${sha[c2]:0:12} (c2) came from no pull request merged into main: a direct push" "!commit ${sha[c3]:0:12}" || return 1
}

case_merge_commit_fallback_not_merged() {
  new_repo root
  standard
  left c3
  jq --arg c "${sha[c3]}" '. + [{number: 2, merged_at: null, base: {ref: "main"}, merge_commit_sha: $c},
    {number: 3, merged_at: "2026-10-01T00:00:00Z", base: {ref: "next"}, merge_commit_sha: $c}]' \
    "$api/pulls.json" > "$work/closed" && mv "$work/closed" "$api/pulls.json"
  expect fail "commit ${sha[c3]:0:12} (c3) came from no pull request merged into main: a direct push" || return 1
}

# the question for the merged pull requests of main fails: no answer is not "none"
case_merge_commit_fallback_error() {
  new_repo root
  standard
  left c3
  : > "$api/pulls.fail"
  expect fail "could not ask GitHub for the pull requests merged into main" "!a direct push" || return 1
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

# --- the direct pushes excused (scripts/release-approval-exceptions)

# c2 is a direct push, listed with its reason: the release goes on, and says that and why. The file
# has comments, blank lines, a CRLF line end, padding and a reason of several words, as it may
exceptions_listed='# the direct pushes excused

  @c2@   the first line of the reason,  and more of it  '$'\r''

'
case_exception_listed() {
  exceptions_text=$exceptions_listed
  new_repo root
  unset exceptions_text
  standard
  left c2
  expect pass "commit ${sha[c2]:0:12} (c2) is a direct push, excused by scripts/release-approval-exceptions: the first line of the reason,  and more of it" \
    "came from 2 pull request(s)" "1 direct push(es) excused" "!came from no pull request" || return 1
  # the reason ends where its words do
  if ! grep -q 'and more of it$' "$work/err"; then
    reason="the reason keeps its padding: $(cat "$work/err")"
    return 1
  fi
}

# the last line without its line end counts too
case_exception_no_line_end() {
  exceptions_text='@c2@ because'
  new_repo root
  unset exceptions_text
  standard
  left c2
  expect pass "excused by scripts/release-approval-exceptions: because" || return 1
}

# another commit is listed: c2 is refused
case_exception_unlisted() {
  exceptions_text='@c1@ read it
'
  new_repo root
  unset exceptions_text
  standard
  left c2
  expect fail "commit ${sha[c2]:0:12} (c2) came from no pull request merged into main: a direct push" "!excused" || return 1
}

# every direct push is named: one excused, one not
case_exception_one_of_two() {
  exceptions_text='@c2@ read it
'
  new_repo root
  unset exceptions_text
  standard
  left c1
  left c2
  expect fail "commit ${sha[c1]:0:12} (c1) came from no pull request merged into main: a direct push" \
    "commit ${sha[c2]:0:12} (c2) is a direct push, excused" "1 of the pull requests and direct pushes" || return 1
}

# a commit that came from a pull request is judged by it, listed or not: #1 has no approval
case_exception_listed_with_pull_request() {
  exceptions_text='@c2@ it was a pull request
'
  new_repo root
  unset exceptions_text
  standard
  reviews 1 "APPROVED:$(head_of 100):bob"
  expect fail "pull request #1 (Pull request 1) by alice has no approval of its final commit" "!excused" || return 1
}

# a commit listed that is not in the range - the baseline itself, and one that is no commit here -
# is ignored
case_exception_outside_range() {
  exceptions_text='@root@ excused for an earlier release
0123456789abcdef0123456789abcdef01234567 not even in this clone
'
  new_repo root
  unset exceptions_text
  standard
  expect pass "came from 2 pull request(s)" "!excused" || return 1
}

case_exception_no_reason() {
  exceptions_text='@c2@
'
  new_repo root
  unset exceptions_text
  standard
  left c2
  expect fail "scripts/release-approval-exceptions has a line that is no full commit SHA, whitespace and a reason: ${sha[c2]}" "!excused" || return 1
}

case_exception_blank_reason() {
  exceptions_text='@c2@   '$'\t''
'
  new_repo root
  unset exceptions_text
  standard
  left c2
  expect fail "scripts/release-approval-exceptions has a line that is no full commit SHA" || return 1
}

# a short SHA, an upper case one and a line that is no SHA: each stops the release, though no commit
# of the range is a direct push
case_exception_bad_sha() {
  local text
  for text in '0123456 short' 'ABCDEF0123456789ABCDEF0123456789ABCDEF01 upper case' 'because it was fine' '@c2@x not a separate word'; do
    exceptions_text=$text
    new_repo root
    unset exceptions_text
    standard
    expect fail "scripts/release-approval-exceptions has a line that is no full commit SHA" || { reason="$text: $reason"; return 1; }
  done
}

# a bad line stops the release when it comes after a good one, too
case_exception_bad_line_after_good() {
  exceptions_text='@c2@ read it
oops
'
  new_repo root
  unset exceptions_text
  standard
  left c2
  expect fail "scripts/release-approval-exceptions has a line that is no full commit SHA, whitespace and a reason: oops" || return 1
}

case_exception_file_missing() {
  new_repo root
  standard
  git -C "$repo" rm -q scripts/release-approval-exceptions
  git -C "$repo" commit -q -m "no exceptions"
  sha[c3]=$(git -C "$repo" rev-parse HEAD)
  left c3 2
  expect fail "scripts/release-approval-exceptions is missing" || return 1
}

# a reason is text anyone with a reviewed pull request writes, still not carried to the log as controls
case_exception_reason_controls() {
  exceptions_text='@c2@ read'$'\x1b''[31m it::error::forged
'
  new_repo root
  unset exceptions_text
  standard
  left c2
  expect pass "excused by scripts/release-approval-exceptions: read[31m it::error::forged" || return 1
  if [ -n "$(LC_ALL=C tr -d '[:print:]\n' < "$work/err")" ]; then
    reason="the output carries a control character: $(od -c "$work/err" | head -5)"
    return 1
  fi
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
  release v1.0.0-rc.1
  rm "$api/commits/${sha[c1]}/pulls.json"
  expect pass "2 commit(s) since v1.0.0-rc.1" "came from 2 pull request(s)" || return 1
  if asked | grep -q "${sha[c1]}\|${sha[root]}\|v1.0.0-rc.2\|v0.0.60\|vtest"; then
    reason="asked for a commit the previous release had, or a tag that is no start: $(asked)"
    return 1
  fi
  if [ "$(asked | grep -c '^releases/')" -ne 1 ]; then
    reason="asked for more than the one release: $(asked)"
    return 1
  fi
}

case_latest_tag() {
  new_repo unset
  git -C "$repo" tag -a -m rc1 v1.0.0-rc.1 "${sha[c1]}"
  git -C "$repo" tag -a -m rc2 v1.0.0-rc.2 "${sha[c2]}"
  standard
  release v1.0.0-rc.1
  release v1.0.0-rc.2
  rm "$api/commits/${sha[c1]}/pulls.json" "$api/commits/${sha[c2]}/pulls.json"
  expect pass "1 commit(s) since v1.0.0-rc.2" "came from 1 pull request(s)" || return 1
}

# rc.2 was refused by its checks, or its upload failed: it has no release, and stays as a tag. The
# pull requests since rc.1, the last release, are still asked about, those before rc.2 included.
case_refused_tag() {
  new_repo unset
  git -C "$repo" tag -a -m rc1 v1.0.0-rc.1 "${sha[c1]}"
  git -C "$repo" tag -a -m rc2 v1.0.0-rc.2 "${sha[c2]}"
  standard
  release v1.0.0-rc.1
  pull 1 alice "$(head_of 1)"
  reviews 1 "APPROVED:$(head_of 100):bob"
  rm "$api/commits/${sha[c1]}/pulls.json"
  expect fail "v1.0.0-rc.2 has no published release, so it is no start" "pull request #1 (Pull request 1) by alice" \
    "!pull request #2" || return 1
}

# a release that is still a draft is no release either
case_draft_release() {
  new_repo unset
  git -C "$repo" tag -a -m rc1 v1.0.0-rc.1 "${sha[c1]}"
  git -C "$repo" tag -a -m rc2 v1.0.0-rc.2 "${sha[c2]}"
  standard
  release v1.0.0-rc.1
  release v1.0.0-rc.2 draft
  reviews 1 "APPROVED:$(head_of 100):bob"
  rm "$api/commits/${sha[c1]}/pulls.json"
  expect fail "v1.0.0-rc.2 has no published release" "pull request #1 (Pull request 1) by alice" || return 1
}

# tags, but no release of any: the baseline decides
case_no_release_at_all() {
  new_repo c1
  git -C "$repo" tag -a -m rc1 v1.0.0-rc.1 "${sha[c1]}"
  git -C "$repo" tag -a -m rc2 v1.0.0-rc.2 "${sha[c2]}"
  standard
  rm "$api/commits/${sha[c1]}/pulls.json"
  expect pass "v1.0.0-rc.2 has no published release" "v1.0.0-rc.1 has no published release" \
    "2 commit(s) since the baseline ${sha[c1]:0:12}" || return 1
}

case_api_error_release() {
  new_repo root
  git -C "$repo" tag -a -m rc1 v1.0.0-rc.1 "${sha[c1]}"
  git -C "$repo" tag -a -m rc2 v1.0.0-rc.2 "${sha[c2]}"
  standard
  release v1.0.0-rc.1
  : > "$api/releases/tags/v1.0.0-rc.2.fail"
  expect fail "could not ask GitHub whether v1.0.0-rc.2 has a published release" "!since v1.0.0-rc.1" || return 1
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
all_cases="case_all_approved case_earlier_commit case_author_only case_other_reviews case_not_release_manager
case_pusher_author case_pusher_committer case_other_manager_approves case_unlinked_author case_unlinked_committer
case_web_edit case_many_commits case_commit_limit case_no_commits case_managers_empty case_managers_missing
case_managers_bad_line case_no_author case_approval_no_login case_title_controls case_api_error_commits
case_api_stall_commits case_commits_partial case_two_pulls_one_commit case_no_review
case_direct_push case_late_association case_late_last_round case_late_too_long case_late_two_commits
case_late_then_error case_merge_commit_fallback case_merge_commit_fallback_judged
case_merge_commit_fallback_only_merge_commit case_merge_commit_fallback_not_merged case_merge_commit_fallback_error
case_only_other_base case_exception_listed case_exception_no_line_end case_exception_unlisted case_exception_one_of_two
case_exception_listed_with_pull_request case_exception_outside_range case_exception_no_reason case_exception_blank_reason
case_exception_bad_sha case_exception_bad_line_after_good case_exception_file_missing case_exception_reason_controls
case_every_failure_named case_previous_tag case_latest_tag
case_refused_tag case_draft_release case_no_release_at_all case_api_error_release case_baseline case_placeholder case_baseline_malformed case_baseline_missing case_baseline_not_ancestor
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
breaks author_only 'elif [ "$(lower "$login")" = "$(lower "$author")" ]; then' 'elif false; then' \
  "takes the author's own approval"
breaks author_only 'elif [ "$(lower "$login")" = "$(lower "$author")" ]; then' 'elif [ "$login" = "$author" ]; then' \
  "compares the author's login with the case it is written in"
breaks not_release_manager 'elif ! manager "$login"; then' 'elif false; then' \
  "takes an approval of anyone, not of a release manager"
breaks not_release_manager 'managers=$(lower "$managers")' 'managers=$managers' \
  "compares the release managers' logins with the case they are written in"
breaks pusher_author "pushers=\$(printf '%s\\n' \"\$listed\" | awk '{ print \$2; print \$3 }')" "pushers=\$(printf '%s\\n' \"\$listed\" | awk '{ print \$3 }')" \
  "does not count the author of a commit as one who pushed"
breaks pusher_committer "pushers=\$(printf '%s\\n' \"\$listed\" | awk '{ print \$2; print \$3 }')" "pushers=\$(printf '%s\\n' \"\$listed\" | awk '{ print \$2 }')" \
  "does not count the committer of a commit as one who pushed"
breaks pusher_author '          pushers=$(lower "$pushers")' '          :' \
  "compares the logins of those who pushed with the case they are written in"
breaks unlinked_author '$2 == "-" || $3 == "-" { print $1; exit }' '$2 == "-" && $3 == "-" { print $1; exit }' \
  "goes on when only one of author and committer is unlinked"
breaks unlinked_committer '$2 == "-" || $3 == "-" { print $1; exit }' '$2 == "-" { print $1; exit }' \
  "goes on when the committer is unlinked"
breaks title_controls "title=\$(plain \"\$(printf '%s\\n' \"\$info\" | sed -n '3,\$p')\")" "title=\$(printf '%s\\n' \"\$info\" | sed -n '3,\$p')" \
  "prints a title as it is"
breaks commits_partial '|| fail "could not ask GitHub for the commits of pull request #$number"' '|| true' \
  "goes on after the question for the commits failed after its first page"
breaks api_error_commits '|| fail "could not ask GitHub for the commits of pull request #$number"' '|| true' \
  "goes on after the question for the commits failed"
breaks api_stall_commits 'limit=(timeout "$timeout")' 'limit=()' \
  "waits for the commits without a limit"
breaks two_pulls_one_commit '  for number in $2; do' '  for number in ${2%%$'"'"'\n'"'"'*}; do' \
  "takes the first of the pull requests that left a commit only"
breaks many_commits 'ask --paginate "repos/$repository/pulls/$number/commits?per_page=100"' 'ask "repos/$repository/pulls/$number/commits?per_page=100"' \
  "reads the first page of the commits only"
breaks commit_limit '[ "$count" -ge 250 ]' '[ "$count" -ge 251 ]' \
  "trusts a list of 250 commits to be the whole"
breaks no_commits '[ "$count" -eq 0 ]' '[ "$count" -eq 99999 ]' \
  "takes an empty list of commits for one nobody pushed"
breaks managers_empty '[ -n "$managers" ] || fail' 'true || fail' \
  "goes on with no release manager listed"
breaks managers_bad_line '[[ "$login" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]] ||' 'true ||' \
  "takes any line of the list for a login"
breaks no_author 'elif [ -z "$author" ]; then' 'elif false; then' \
  "approves a pull request GitHub names no author of"
breaks approval_no_login '    if [ "$login" = "-" ]; then' '    if false; then' \
  "takes an approval by a user GitHub does not name"
breaks direct_push 'direct+=("$sha")' ':' \
  "lets a direct push pass"
breaks direct_push 'retries=${APPROVAL_RETRIES:-4}' 'retries=${APPROVAL_RETRIES:-3}' \
  "asks once less than it should before it counts a direct push"
breaks late_association 'retries=${APPROVAL_RETRIES:-4}' 'retries=${APPROVAL_RETRIES:-0}' \
  "takes a commit GitHub lists no pull request for yet for a direct push at once"
breaks late_last_round 'retries=${APPROVAL_RETRIES:-4}' 'retries=${APPROVAL_RETRIES:-3}' \
  "gives up one round too early"
breaks late_too_long 'retries=${APPROVAL_RETRIES:-4}' 'retries=${APPROVAL_RETRIES:-5}' \
  "keeps asking one round too long"
breaks late_two_commits '  sleep "$retry_wait"' '  for _ in "${pending[@]}"; do sleep "$retry_wait"; done' \
  "waits once for each commit still without a pull request"
breaks late_then_error 'found=$(associated "$sha") || fail "could not ask GitHub which pull request left $sha"' 'found=$(associated "$sha" || true)' \
  "takes a failed question for none"
breaks merge_commit_fallback '    found=$(printf '"'"'%s\n'"'"' "$merged" | awk -v sha="$sha" '"'"'$1 == sha { print $2 }'"'"')' '    found=' \
  "does not look at the merged pull requests of main for a commit with none"
breaks merge_commit_fallback_not_merged 'select(.merged_at != null and .base.ref == "main" and .merge_commit_sha != null)' 'select(.base.ref == "main" and .merge_commit_sha != null)' \
  "takes an open pull request for the one that merged, looking the other way round"
breaks merge_commit_fallback_not_merged 'select(.merged_at != null and .base.ref == "main" and .merge_commit_sha != null)' 'select(.merged_at != null and .merge_commit_sha != null)' \
  "takes a pull request merged into another branch, looking the other way round"
breaks merge_commit_fallback_error '    || fail "could not ask GitHub for the pull requests merged into main"' '    || merged=' \
  "takes a failed question for the merged pull requests of main for none"
breaks previous_tag "'refs/tags/v1.*'" "'refs/tags/v1.nothing*'" \
  "ignores the previous release tag"
breaks latest_tag 'done | sort -n)' 'done | sort -rn)' \
  "starts from the oldest release tag, not the nearest"
breaks refused_tag '  if published "$tag"; then' '  if true; then' \
  "takes the nearest tag for a release without asking whether it was published"
breaks draft_release '      true) return 1 ;;' '      true) return 0 ;;' \
  "takes a draft release for a published one"
breaks api_error_release '    *) fail "could not ask GitHub whether $1 has a published release: $answer" ;;' '    *) return 1 ;;' \
  "takes any failed question about a release for no release"
breaks no_release_at_all '    *"HTTP 404"*) return 1 ;;' '    *"HTTP 404"*) return 0 ;;' \
  "takes a missing release for a published one"
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
breaks many_pulls 'ask --paginate "repos/$repository/commits/$1/pulls?per_page=100"' 'ask "repos/$repository/commits/$1/pulls?per_page=100"' \
  "reads the first page of a commit's pull requests only"
breaks many_reviews 'ask --paginate "repos/$repository/pulls/$number/reviews?per_page=100"' 'ask "repos/$repository/pulls/$number/reviews?per_page=100"' \
  "reads the first page of the reviews only"
breaks other_reviews 'select(.state == "APPROVED") | ' '' \
  "counts a review that is not an approval"
breaks all_approved '| sort -un)' '| sort -n)' \
  "asks about a pull request once for each of its commits"
breaks direct_push 'select(.merged_at != null and .base.ref == "main") | .number' 'select(.base.ref == "main") | .number' \
  "takes an open pull request for the one that merged"
breaks only_other_base 'select(.merged_at != null and .base.ref == "main") | .number' 'select(.merged_at != null) | .number' \
  "takes a pull request merged into another branch"
breaks exception_listed '        excused+=("$sha")' '        direct+=("$sha")' \
  "excuses no direct push"
breaks exception_unlisted '      if [ -n "$(excuse "$sha")" ]; then' '      if true; then' \
  "excuses every direct push"
breaks exception_listed_with_pull_request '    if [ -z "$found" ]; then
      still+=("$sha")' '    if [ -z "$found" ] || [ -n "$(excuse "$sha")" ]; then
      still+=("$sha")' \
  "excuses a listed commit though a pull request left it"
breaks exception_outside_range 'commits=$(git rev-list "$start..$commit")' 'commits=$(git rev-list "$start..$commit")
for listed in $(printf '"'"'%s'"'"' "$exceptions" | cut -d" " -f1); do
  printf '"'"'%s\n'"'"' "$commits" | grep -qx "$listed" || fail "$listed is not in the range"
done' \
  "refuses a listed commit that is not in the range"
breaks exception_no_reason '^([0-9a-f]{40})[[:space:]]+([^[:space:]].*)$' '^([0-9a-f]{40})[[:space:]]*(.*)$' \
  "takes a line with no reason"
breaks exception_bad_sha '^([0-9a-f]{40})[[:space:]]+' '^([0-9a-f]{4,41})[[:space:]]+' \
  "takes a short SHA"
breaks exception_bad_sha '    || fail "$exceptions_file has a line that is no full commit SHA, whitespace and a reason: $(plain "$line")"' '    || continue' \
  "skips a line that is no SHA and a reason"
breaks exception_bad_line_after_good '  case "$line" in "" | "#"*) continue ;; esac' '  case "$line" in "" | "#"* | *[!0-9a-f\ ]*) continue ;; esac' \
  "skips a line with a character no SHA has"
breaks exception_file_missing '|| fail "$exceptions_file is missing: it lists the direct pushes a release manager has read and excused"' '|| raw=' \
  "goes on without the list"
breaks exception_reason_controls 'is a direct push, excused by $exceptions_file: $(plain "$(excuse "$sha")")' 'is a direct push, excused by $exceptions_file: $(excuse "$sha")' \
  "prints a reason as it is"
breaks exception_listed 'reason=${reason%"${reason##*[![:space:]]}"}' ':' \
  "keeps the padding after a reason"
breaks shallow '[ "$(git rev-parse --is-shallow-repository)" = false ] ||' 'true ||' \
  "checks a shallow clone"

if [ "$failures" -ne 0 ]; then
  echo "$failures failures" >&2
  exit 1
fi
echo "the approval check refuses what it should"
