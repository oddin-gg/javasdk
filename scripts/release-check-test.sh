#!/usr/bin/env bash
#
# Runs scripts/release-check.sh against a scratch repository and a stub Central, and fails
# unless every tag below is accepted or refused as it should be. next.yml runs it on every push,
# so a change that lets a check pass everything shows up before a tag relies on it.
# Needs git, curl and python3.
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

# Stub Central: the first path segment is the status it answers with, so CENTRAL_URL picks it.
python3 - "$work/port" <<'EOF' &
import http.server, os, sys
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(int(self.path.split('/')[1]))
        self.end_headers()
    def log_message(self, *args):
        pass
server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
with open(sys.argv[1] + '.part', 'w') as f:
    f.write(str(server.server_port))
os.rename(sys.argv[1] + '.part', sys.argv[1])
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
present=$central/200
broken=$central/500
# nothing listens on port 1
silent=http://127.0.0.1:1

repo=$work/repo
git init -q -b main "$repo"
git -C "$repo" config user.name test
git -C "$repo" config user.email test@example.invalid
git -C "$repo" config commit.gpgsign false
git -C "$repo" config tag.gpgsign false

# a commit on the current branch, with these files as given (path=content; empty content
# makes an empty file), and the tags after them
commit() {
  local spec
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do
    spec=$1
    shift
    mkdir -p "$repo/$(dirname "${spec%%=*}")"
    printf '%s' "${spec#*=}" > "$repo/${spec%%=*}"
  done
  [ $# -gt 0 ] && shift
  git -C "$repo" add -A
  git -C "$repo" commit -q --allow-empty -m change
  for tag in "$@"; do
    git -C "$repo" tag -a -m "$tag" "$tag"
  done
}

failures=0
expect() {
  local want=$1 tag=$2 url=$3 final=${4:-} got
  : > "$work/out"
  if (cd "$repo" && GITHUB_OUTPUT=$work/out CENTRAL_URL=$url bash "$check" "$tag") 2> "$work/err"; then
    got=accept
  else
    got=refuse
  fi
  if [ "$got" != "$want" ]; then
    echo "FAIL $tag: expected $want, got $got: $(cat "$work/err")" >&2
    failures=$((failures + 1))
  elif [ -n "$final" ] && ! grep -qx "final=$final" "$work/out"; then
    echo "FAIL $tag: expected final=$final, got: $(cat "$work/out")" >&2
    failures=$((failures + 1))
  else
    echo "ok   $want $tag: $(tail -n 1 "$work/err")"
  fi
}

workflow='on:
  push:
    tags:
      - v1.*
'
commit ".github/workflows/release.yml=$workflow" ".github/workflows/next.yml=on: push"

# on next
git -C "$repo" checkout -q -b next
commit release-notes/1.0.0-rc.1.md=notes -- v1.0.0-rc.1
commit release-notes/1.0.0.md=notes -- v1.0.0
commit release-notes/1.0.1.md=notes -- v1.0.1 v1.0.1-rc.1
commit release-notes/1.0.2.md=notes -- v1.0.2 latest
commit -- v1.0.3
commit release-notes/1.0.4.md= -- v1.0.4
commit release-notes/1.0.5.md=notes ".github/workflows/old.yml=$workflow" -- v1.0.5
git -C "$repo" update-ref refs/remotes/origin/next HEAD

# on main only
git -C "$repo" checkout -q main
commit release-notes/1.1.0.md=notes -- v1.1.0
git -C "$repo" update-ref refs/remotes/origin/main HEAD

# on neither
git -C "$repo" checkout -q -b feature
commit release-notes/1.2.0.md=notes -- v1.2.0

expect accept v1.0.0-rc.1 "$absent" false
expect accept v1.0.0 "$absent" true
expect accept v1.1.0 "$absent" true

for tag in v1.0.0-rc.0 v1.01.0 v1.0 v1.0.0-beta.1 v1.0.0-rc.1x v2.0.0 v0.0.58 1.0.0; do
  expect refuse "$tag" "$absent"
done
expect refuse v1.0.9 "$absent" # no such tag
expect refuse v1.2.0 "$absent" # on neither next nor main
expect refuse v1.0.1 "$absent" # a second release tag on the commit
expect refuse v1.0.2 "$absent" # any other tag on the commit
expect refuse v1.0.3 "$absent" # no release notes
expect refuse v1.0.4 "$absent" # empty release notes
expect refuse v1.0.5 "$absent" # another workflow reacts to tags
expect refuse v1.0.0 "$present"
expect refuse v1.0.0 "$broken"
expect refuse v1.0.0 "$silent"

if [ "$failures" -ne 0 ]; then
  echo "$failures release check case(s) failed" >&2
  exit 1
fi
echo "every release check case passed"
