#!/usr/bin/env bash
#
# Runs scripts/release-check.sh and scripts/release-tag-check.sh against a scratch repository and
# a stub Central, and fails unless every tag below is accepted, with the version and kind it
# should get and the Central lookups it should make, or refused for the reason it should be.
# next.yml runs it on every push, so a change that lets a check pass everything shows up before
# a tag relies on it. Needs git, curl, python3, yq and jq (the stub gh filters with it).
set -euo pipefail

for tool in git curl python3 yq jq; do
  command -v "$tool" > /dev/null || { echo "needs $tool" >&2; exit 1; }
done

root=$(cd "$(dirname "$0")/.." && pwd)
check=$root/scripts/release-check.sh
tag_check=$root/scripts/release-tag-check.sh
work=$(mktemp -d)
server=
cleanup() {
  if [ -n "$server" ]; then
    kill "$server" 2>/dev/null || true
    wait "$server" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

# Stub Central. The first path segment says how to answer: one status for every request ("404"),
# one for the parent POM and one for odds-feed's ("404-200"), or "stall", which answers 404 only
# after 10 seconds. Every request is recorded, without that first segment.
python3 - "$work/port" "$work/requests" <<'EOF' &
import http.server, os, sys, time
port_file, log = sys.argv[1], sys.argv[2]
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        mode, _, rest = self.path[1:].partition('/')
        with open(log, 'a') as f:
            f.write('/' + rest + '\n')
        if mode == 'stall':
            time.sleep(10)
            status = 404
        elif '-' in mode:
            parent, feed = mode.split('-')
            status = int(parent if '/odds-feed-parent/' in rest else feed)
        else:
            status = int(mode)
        self.send_response(status)
        self.end_headers()
    def log_message(self, *args):
        pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.daemon_threads = True
with open(port_file + '.part', 'w') as f:
    f.write(str(server.server_port))
os.rename(port_file + '.part', port_file)
server.serve_forever()
EOF
server=$!
for _ in $(seq 100); do
  [ -s "$work/port" ] && break
  sleep 0.1
done
[ -s "$work/port" ] || { echo "the stub Central did not start" >&2; exit 1; }
central=http://127.0.0.1:$(cat "$work/port")
absent=$central/404
# nothing listens on port 1
silent=http://127.0.0.1:1

# Stub gh, for the question which pull request left a commit, asked two ways.
# - commits/<sha>/pulls answers from $work/pulls/<sha>: a .json file holds the pull requests, a
#   .fail file makes the call fail, a .stall file makes it answer only after 10 seconds. Without
#   a file the commit is the merge commit of a pull request merged into next, beside an open one.
# - pulls?state=closed&base=<base> answers from $work/pulls/closed-<base>.json (none: no pull
#   requests), and fails while $work/pulls/closed.fail exists.
# Every call is recorded. The answer goes through the caller's --jq, with jq.
mkdir -p "$work/bin" "$work/pulls"
cat > "$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
pulls=${GH_STUB_PULLS:?}
echo "gh $*" >> "$pulls/calls"
endpoint= filter=.
while [ $# -gt 0 ]; do
  case "$1" in
    --jq) filter=$2; shift 2 ;;
    api | --paginate) shift ;;
    *) endpoint=$1; shift ;;
  esac
done
case "$endpoint" in
  */pulls\?state=closed\&base=*)
    base=${endpoint#*base=}
    base=${base%%&*}
    if [ -e "$pulls/closed.fail" ]; then
      echo "gh: HTTP 502" >&2
      exit 1
    fi
    if [ -e "$pulls/closed-$base.json" ]; then
      jq -r "$filter" "$pulls/closed-$base.json"
    else
      echo '[]' | jq -r "$filter"
    fi
    exit 0
    ;;
esac
sha=${endpoint#*/commits/}
sha=${sha%%/*}
if [ -e "$pulls/$sha.fail" ]; then
  echo "gh: HTTP 502" >&2
  exit 1
fi
if [ -e "$pulls/$sha.stall" ]; then
  sleep 10
fi
if [ -e "$pulls/$sha.json" ]; then
  json=$(cat "$pulls/$sha.json")
else
  json='[{"number": 1, "merged_at": "2026-10-01T00:00:00Z", "base": {"ref": "next"}, "merge_commit_sha": "'$sha'"},
         {"number": 2, "merged_at": null, "base": {"ref": "next"}, "merge_commit_sha": "0000000"}]'
fi
printf '%s' "$json" | jq -r "$filter"
EOF
chmod +x "$work/bin/gh"

# pulls <tag> <json, in which SHA stands for the tag's commit and OTHER for the commit before it>
pulls() {
  local sha other
  sha=$(git -C "$repo" rev-parse "$1^{commit}")
  other=$(git -C "$repo" rev-parse "$1^{commit}~1")
  printf '%s' "$2" | sed -e "s/SHA/$sha/g" -e "s/OTHER/$other/g" > "$work/pulls/$sha.json"
}

repo=$work/repo
git init -q -b main "$repo"
git -C "$repo" config user.name test
git -C "$repo" config user.email test@example.invalid
git -C "$repo" config commit.gpgsign false
git -C "$repo" config tag.gpgsign false

# A commit on the current branch, then annotated tags on it. Before the "--": path=content writes
# a file (empty content, an empty file), path@target a symbolic link, -path removes a file.
commit() {
  local spec path
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do
    spec=$1
    shift
    case "$spec" in
      -*) git -C "$repo" --literal-pathspecs rm -q "${spec#-}" ;;
      *=*)
        path=${spec%%=*}
        mkdir -p "$repo/$(dirname "$path")"
        printf '%s' "${spec#*=}" > "$repo/$path"
        ;;
      *@*)
        path=${spec%%@*}
        mkdir -p "$repo/$(dirname "$path")"
        ln -s "${spec#*@}" "$repo/$path"
        ;;
    esac
  done
  [ $# -gt 0 ] && shift
  git -C "$repo" add -A
  git -C "$repo" commit -q --allow-empty -m change
  for tag in "$@"; do
    git -C "$repo" tag -a -m "$tag" "$tag"
  done
}

# a commit with its own release notes and this tag, eligible in every other way
release() {
  commit "release-notes/${1#v}.md=notes" "${@:2}" -- "$1"
}

failures=0
failed() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

# expect accept <tag> <central> <version> <final>
# expect refuse <tag> <central> <what the refusal says>
expect() {
  local want=$1 tag=$2 url=$3 got
  : > "$work/out"
  : > "$work/requests"
  : > "$work/pulls/calls"
  # the run was started for the tag's commit, unless a case says otherwise in $started
  local sha=${started:-$(git -C "$repo" rev-parse -q --verify "$tag^{commit}" || echo none)}
  if (cd "$repo" && PATH=$work/bin:$PATH GH_STUB_PULLS=$work/pulls GITHUB_REPOSITORY=example/repo GITHUB_SHA=$sha \
    GITHUB_OUTPUT=$work/out CENTRAL_URL=$url CENTRAL_TIMEOUT=2 bash "$check" "$tag") 2> "$work/err"; then
    got=accept
  else
    got=refuse
  fi
  if [ "$got" != "$want" ]; then
    failed "$tag: expected $want, got $got: $(cat "$work/err")"
    return
  fi
  if [ "$want" = accept ]; then
    local version=$4 final=$5 lookups
    lookups="/gg/oddin/oddsfeed/odds-feed-parent/$version/odds-feed-parent-$version.pom
/gg/oddin/oddsfeed/odds-feed/$version/odds-feed-$version.pom"
    if ! grep -qx "version=$version" "$work/out" || ! grep -qx "final=$final" "$work/out" \
      || ! grep -qx "commit=$(git -C "$repo" rev-parse "$tag^{commit}")" "$work/out"; then
      failed "$tag: expected version=$version final=$final and its commit, got: $(cat "$work/out")"
      return
    fi
    if ! grep -qF "gh api --paginate repos/example/repo/commits/$(git -C "$repo" rev-parse "$tag^{commit}")/pulls?per_page=100 --jq" "$work/pulls/calls"; then
      failed "$tag: GitHub was not asked for the pull requests of its commit: $(cat "$work/pulls/calls")"
      return
    fi
    if [ "$(cat "$work/requests")" != "$lookups" ]; then
      failed "$tag: expected Central to be asked for exactly
$lookups
but it was asked for
$(cat "$work/requests")"
      return
    fi
  elif ! grep -qF -- "$4" "$work/err"; then
    failed "$tag: refused, but not because \"$4\": $(cat "$work/err")"
    return
  fi
  echo "ok   $want $tag: $(tail -n 1 "$work/err")"
}

# expect_tag accept|refuse <tag> <commit> <what it says> [remote]
# Runs in the checkout, a clone of the scratch repository, asking its origin unless told
# otherwise: the tags that move are origin's, the checkout's stay where they were.
expect_tag() {
  local want=$1 tag=$2 commit=$3 reason=$4 remote=${5:-origin} got
  if (cd "$work/checkout" && bash "$tag_check" "$tag" "$commit" "$remote") 2> "$work/err"; then
    got=accept
  else
    got=refuse
  fi
  if [ "$got" != "$want" ]; then
    failed "tag check $tag: expected $want, got $got: $(cat "$work/err")"
  elif ! grep -qF -- "$reason" "$work/err"; then
    failed "tag check $tag: not \"$reason\": $(cat "$work/err")"
  else
    echo "ok   $want tag check $tag: $(tail -n 1 "$work/err")"
  fi
}

release_workflow='on:
  push:
    tags:
      - v1.*
'
commit ".github/workflows/release.yml=$release_workflow" \
  ".github/workflows/next.yml=on: {push: {branches: [next]}, pull_request: {branches: [next]}, workflow_call: {}}"

# on next
git -C "$repo" checkout -q -b next
release v1.0.0-rc.1
release v1.0.0
for tag in v1.0.0-rc.0 v1.01.0 v1.0 v1.0.0-beta.1 v1.0.0-rc.1x v2.0.0 v0.0.58 1.0.0; do
  release "$tag"
done
commit release-notes/1.0.1.md=notes -- v1.0.1 v1.0.1-rc.1
commit release-notes/1.0.2.md=notes -- v1.0.2 latest
commit -- v1.0.3
commit release-notes/1.0.4.md= -- v1.0.4
commit release-notes/1.0.5.md@/proc/self/environ -- v1.0.5
release v1.0.6 ".github/workflows/old.yml=$release_workflow"
commit -.github/workflows/old.yml
release v1.0.7 ".github/workflows/old.yml=on: {push: {branches: [next], tags: ['v1.*']}}"
commit -.github/workflows/old.yml
release v1.0.8 ".github/workflows/old.yml=on: [push, pull_request]"
commit -.github/workflows/old.yml
release v1.0.9 ".github/workflows/old.yml=on: create"
commit -.github/workflows/old.yml
release v1.0.10
release v1.0.11 ".github/workflows/old.yml=on: {release: {types: [published]}}"
commit -.github/workflows/old.yml
release v1.0.12 ".github/workflows/old.yml=on: {push: {branches: [next], tags-ignore: [nightly]}}"
commit -.github/workflows/old.yml
release v1.0.13 ".github/workflows/old.yaml=$release_workflow"
commit -.github/workflows/old.yaml
release v1.0.14 ".github/workflows/ü.yml=$release_workflow"
commit "-.github/workflows/ü.yml"
release v1.0.15 ".github/workflows/nex[t].yml=$release_workflow"
commit "-.github/workflows/nex[t].yml"
release v1.0.16
for tag in v1.0.17 v1.0.18 v1.0.19 v1.0.20 v1.0.21 v1.0.22 v1.0.23 v1.0.24; do
  release "$tag"
done
git -C "$repo" update-ref refs/remotes/origin/next HEAD
# an intermediate commit of a pull request: on next, but its merge commit is a later one
pulls v1.0.17 '[{"number": 3, "merged_at": "2026-10-01T00:00:00Z", "base": {"ref": "next"}, "merge_commit_sha": "OTHER"}]'
pulls v1.0.18 '[{"number": 4, "merged_at": null, "base": {"ref": "next"}, "merge_commit_sha": "SHA"}]'
pulls v1.0.19 '[{"number": 5, "merged_at": "2026-10-01T00:00:00Z", "base": {"ref": "feature"}, "merge_commit_sha": "SHA"}]'
# GitHub as documented: for a commit not on main, commits/<sha>/pulls lists only open pull
# requests; the merged one is found in the closed pull requests of next
for tag in v1.0.22 v1.0.23 v1.0.24; do
  pulls "$tag" '[{"number": 7, "merged_at": null, "base": {"ref": "next"}, "merge_commit_sha": "0000000"}]'
done
v1022=$(git -C "$repo" rev-parse "v1.0.22^{commit}")
printf '[{"number": 8, "merged_at": "2026-10-01T00:00:00Z", "base": {"ref": "next"}, "merge_commit_sha": "%s"},
  {"number": 9, "merged_at": null, "base": {"ref": "next"}, "merge_commit_sha": "%s"}]' \
  "$v1022" "$(git -C "$repo" rev-parse "v1.0.23^{commit}")" > "$work/pulls/closed-next.json"
touch "$work/pulls/$(git -C "$repo" rev-parse "v1.0.20^{commit}").fail"
touch "$work/pulls/$(git -C "$repo" rev-parse "v1.0.21^{commit}").stall"

# on main only
git -C "$repo" checkout -q main
release v1.1.0
pulls v1.1.0 '[{"number": 6, "merged_at": "2026-10-01T00:00:00Z", "base": {"ref": "main"}, "merge_commit_sha": "SHA"}]'
git -C "$repo" update-ref refs/remotes/origin/main HEAD

# on neither
git -C "$repo" checkout -q -b feature
release v1.2.0

expect accept v1.0.0-rc.1 "$absent" 1.0.0-rc.1 false
expect accept v1.0.0 "$absent" 1.0.0 true
# the run started for the annotated tag's own object: the same tag
started=$(git -C "$repo" rev-parse v1.0.0) expect accept v1.0.0 "$absent" 1.0.0 true
# the run started for another commit: the tag moved before the check read it
started=$(git -C "$repo" rev-parse "v1.0.0-rc.1^{commit}") expect refuse v1.0.0 "$absent" \
  "the tag moved since this run started: it names $(git -C "$repo" rev-parse "v1.0.0^{commit}"), the run was started for $(git -C "$repo" rev-parse "v1.0.0-rc.1^{commit}"); push it again"
expect accept v1.1.0 "$absent" 1.1.0 true
expect accept v1.0.10 "$absent" 1.0.10 true

for tag in v1.0.0-rc.0 v1.01.0 v1.0 v1.0.0-beta.1 v1.0.0-rc.1x v2.0.0 v0.0.58 1.0.0; do
  expect refuse "$tag" "$absent" "neither v1.MINOR.PATCH nor v1.MINOR.PATCH-rc.N"
done
expect refuse v1.0.99 "$absent" "no such tag"
expect refuse v1.2.0 "$absent" "is on neither next nor main"
expect refuse v1.0.1 "$absent" "other tags name"
expect refuse v1.0.2 "$absent" "other tags name"
expect refuse v1.0.3 "$absent" "release-notes/1.0.3.md is missing"
expect refuse v1.0.4 "$absent" "release-notes/1.0.4.md is empty"
expect refuse v1.0.5 "$absent" "release-notes/1.0.5.md is not a plain file"
for tag in v1.0.6 v1.0.7 v1.0.8 v1.0.9 v1.0.11 v1.0.12; do
  expect refuse "$tag" "$absent" ".github/workflows/old.yml reacts to tags as well"
done
expect refuse v1.0.13 "$absent" ".github/workflows/old.yaml reacts to tags as well"
expect refuse v1.0.14 "$absent" "a workflow name outside A-Z a-z 0-9 . _ -"
expect refuse v1.0.15 "$absent" ".github/workflows/nex[t].yml: a workflow name outside"
# the odd names removed again, and next.yml still there: the literal removal hit only them
expect accept v1.0.16 "$absent" 1.0.16 true
git -C "$repo" cat-file -e "v1.0.16:.github/workflows/next.yml" 2> /dev/null \
  || failed "removing nex[t].yml took next.yml with it"
expect refuse v1.0.17 "$absent" "is not the merge commit of a pull request merged into next or main"
expect refuse v1.0.18 "$absent" "is not the merge commit of a pull request merged into next or main"
expect refuse v1.0.19 "$absent" "is not the merge commit of a pull request merged into next or main"
expect refuse v1.0.20 "$absent" "could not ask GitHub which pull request left"
expect accept v1.0.22 "$absent" 1.0.22 true
grep -qF "repos/example/repo/pulls?state=closed&base=next&per_page=100" "$work/pulls/calls" \
  || failed "v1.0.22: the fallback did not ask for the closed pull requests of next: $(cat "$work/pulls/calls")"
expect refuse v1.0.23 "$absent" "is not the merge commit of a pull request merged into next or main"
for base in next main; do
  grep -qF "repos/example/repo/pulls?state=closed&base=$base&per_page=100" "$work/pulls/calls" \
    || failed "v1.0.23: the fallback did not ask for the closed pull requests of $base: $(cat "$work/pulls/calls")"
done
touch "$work/pulls/closed.fail"
expect refuse v1.0.24 "$absent" "could not ask GitHub for the pull requests merged into next"
rm "$work/pulls/closed.fail"
if command -v timeout > /dev/null; then
  expect refuse v1.0.21 "$absent" "could not ask GitHub which pull request left"
else
  echo "skip v1.0.21: no timeout command here to stop a stalled GitHub (CI has one)"
fi
expect refuse v1.0.0 "$central/200" "Central already has odds-feed-parent 1.0.0"
expect refuse v1.0.0 "$central/404-200" "Central already has odds-feed 1.0.0"
expect refuse v1.0.0 "$central/500" "could not tell whether Central has odds-feed-parent 1.0.0 (500"
expect refuse v1.0.0 "$central/404-500" "could not tell whether Central has odds-feed 1.0.0 (500"
expect refuse v1.0.0 "$silent" "could not tell whether Central has odds-feed-parent 1.0.0 (no answer"
expect refuse v1.0.0 "$central/stall" "could not tell whether Central has odds-feed-parent 1.0.0 (no answer"

# the tag as the remote has it, right before the upload
released=$(git -C "$repo" rev-parse "v1.0.0^{commit}")
other=$(git -C "$repo" rev-parse "v1.1.0^{commit}")
git -C "$repo" tag lightweight "$released"
git clone -q "$repo" "$work/checkout"
expect_tag accept v1.0.0 "$released" "still names $released"
expect_tag accept lightweight "$released" "still names $released"
git -C "$repo" tag -f -a -m moved v1.0.0 "$other" > /dev/null
git -C "$repo" tag -f lightweight "$other" > /dev/null
expect_tag refuse v1.0.0 "$released" "now names $other"
expect_tag refuse lightweight "$released" "now names $other"
git -C "$repo" tag -d v1.0.0 > /dev/null
expect_tag refuse v1.0.0 "$released" "no longer on"
expect_tag refuse v1.0.0 "$released" "could not ask" "$work/nowhere"
# what moved was origin's tag only: the checkout still has the old one
[ "$(git -C "$work/checkout" rev-parse "v1.0.0^{commit}")" = "$released" ] \
  || failed "the checkout's own v1.0.0 moved, so the cases above did not test the remote"

if [ "$failures" -ne 0 ]; then
  echo "$failures release check case(s) failed" >&2
  exit 1
fi
echo "every release check case passed"
