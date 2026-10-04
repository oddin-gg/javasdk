#!/usr/bin/env bash
#
# Runs scripts/release-check.sh against a scratch repository and a stub Central, and fails unless
# every tag below is accepted, with the version and kind it should get and the Central lookups it
# should make, or refused for the reason it should be.
# next.yml runs it on every push, so a change that lets a check pass everything shows up before
# a tag relies on it. Needs git, curl, python3 and yq.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
check=$root/scripts/release-check.sh
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
      -*) git -C "$repo" rm -q "${spec#-}" ;;
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
  if (cd "$repo" && GITHUB_OUTPUT=$work/out CENTRAL_URL=$url CENTRAL_TIMEOUT=2 bash "$check" "$tag") 2> "$work/err"; then
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
release v1.0.7 ".github/workflows/old.yml=on: {push: {tags: ['v1.*']}}"
commit -.github/workflows/old.yml
release v1.0.8 ".github/workflows/old.yml=on: [push, pull_request]"
commit -.github/workflows/old.yml
release v1.0.9 ".github/workflows/old.yml=on: create"
commit -.github/workflows/old.yml
release v1.0.10
git -C "$repo" update-ref refs/remotes/origin/next HEAD

# on main only
git -C "$repo" checkout -q main
release v1.1.0
git -C "$repo" update-ref refs/remotes/origin/main HEAD

# on neither
git -C "$repo" checkout -q -b feature
release v1.2.0

expect accept v1.0.0-rc.1 "$absent" 1.0.0-rc.1 false
expect accept v1.0.0 "$absent" 1.0.0 true
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
for tag in v1.0.6 v1.0.7 v1.0.8 v1.0.9; do
  expect refuse "$tag" "$absent" ".github/workflows/old.yml reacts to tags as well"
done
expect refuse v1.0.0 "$central/200" "Central already has odds-feed-parent 1.0.0"
expect refuse v1.0.0 "$central/404-200" "Central already has odds-feed 1.0.0"
expect refuse v1.0.0 "$central/500" "could not tell whether Central has odds-feed-parent 1.0.0 (500"
expect refuse v1.0.0 "$central/404-500" "could not tell whether Central has odds-feed 1.0.0 (500"
expect refuse v1.0.0 "$silent" "could not tell whether Central has odds-feed-parent 1.0.0 (no answer"
expect refuse v1.0.0 "$central/stall" "could not tell whether Central has odds-feed-parent 1.0.0 (no answer"

if [ "$failures" -ne 0 ]; then
  echo "$failures release check case(s) failed" >&2
  exit 1
fi
echo "every release check case passed"
