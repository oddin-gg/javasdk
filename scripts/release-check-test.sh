#!/usr/bin/env bash
#
# Runs scripts/release-check.sh against a scratch repository and a stub registry, and fails
# unless every tag below is accepted or refused as it should be. CI runs it on every push.
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

# Stub registry: the first path segment is the status it answers with, so REGISTRY_URL picks it.
python3 - "$work/port" <<'EOF' &
import http.server, os, sys
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        status = int(self.path.split('/')[1])
        self.send_response(status)
        if status == 302:
            self.send_header('Location', 'http://127.0.0.1:1/blob')
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
[ -s "$work/port" ] || { echo "the stub registry did not start" >&2; exit 1; }
registry=http://127.0.0.1:$(cat "$work/port")
absent=$registry/404
# nothing listens on port 1
silent=http://127.0.0.1:1

repo=$work/repo
git init -q "$repo"
git -C "$repo" config user.name test
git -C "$repo" config user.email test@example.invalid
git -C "$repo" config commit.gpgsign false
git -C "$repo" config tag.gpgsign false

# a commit with these tags on it
commit() {
  git -C "$repo" commit -q --allow-empty -m change
  for tag in "$@"; do
    git -C "$repo" tag -a -m "$tag" "$tag"
  done
}

failures=0
expect() {
  local want=$1 tag=$2 url=$3 version=${4:-} got
  : > "$work/out"
  if (cd "$repo" && GITHUB_OUTPUT=$work/out REGISTRY_URL=$url GITHUB_TOKEN=token bash "$check" "$tag") 2> "$work/err"; then
    got=accept
  else
    got=refuse
  fi
  if [ "$got" != "$want" ]; then
    echo "FAIL $tag ($url): expected $want, got $got: $(cat "$work/err")" >&2
    failures=$((failures + 1))
  elif [ -n "$version" ] && ! grep -qx "version=$version" "$work/out"; then
    echo "FAIL $tag: expected version=$version, got: $(cat "$work/out")" >&2
    failures=$((failures + 1))
  else
    echo "ok   $want $tag: $(tail -n 1 "$work/err")"
  fi
}

commit v0.0.58
commit v0.0.59-rc1
commit v0.0.60 v0.0.60-rc1
commit v0.0.61 latest
commit v0.0v.62
commit v0.0.63.1

expect accept v0.0.58 "$absent" 0.0.58
expect accept v0.0.59-rc1 "$absent" 0.0.59-rc1
for status in 200 302 401 500; do
  expect refuse v0.0.58 "$registry/$status"
done
expect refuse v0.0.58 "$silent"

for tag in v0.0v.62 v0.0.63.1 v0.0.058 v0.0.58-rc0 v0.0.58-rc.1 v1.0.0 0.0.58; do
  expect refuse "$tag" "$absent"
done
expect refuse v0.0.99 "$absent" # no such tag
expect refuse v0.0.60 "$absent" # a second release tag on the commit
expect refuse v0.0.61 "$absent" # any other tag on the commit

if [ "$failures" -ne 0 ]; then
  echo "$failures release check case(s) failed" >&2
  exit 1
fi
echo "every release check case passed"
