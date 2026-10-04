#!/usr/bin/env bash
#
# Runs scripts/release-check.sh against a scratch repository and a stub registry, and fails
# unless every tag below is accepted, with its version and the one POM the registry should be
# asked for, or refused for the reason it should be. CI runs it on every push.
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

# Stub registry. The first path segment is the status it answers with, or "stall", which answers
# 404 only after 10 seconds. Every request is recorded, without that first segment.
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
        else:
            status = int(mode)
        self.send_response(status)
        if status == 302:
            self.send_header('Location', 'http://127.0.0.1:1/blob')
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
failed() {
  echo "FAIL $*" >&2
  failures=$((failures + 1))
}

# expect accept <tag> <registry> <version> [clone]
# expect refuse <tag> <registry> <what the refusal says> [clone]
# The check runs in the clone if one is given, asking its origin; in the scratch repository,
# asking itself, otherwise.
expect() {
  local want=$1 tag=$2 url=$3 dir=${5:-$repo} remote got
  remote=$([ -n "${5:-}" ] && echo origin || echo "$repo")
  : > "$work/out"
  : > "$work/requests"
  if (cd "$dir" && GITHUB_OUTPUT=$work/out REGISTRY_URL=$url REGISTRY_TIMEOUT=2 RELEASE_REMOTE=$remote \
    GITHUB_TOKEN=token bash "$check" "$tag") 2> "$work/err"; then
    got=accept
  else
    got=refuse
  fi
  if [ "$got" != "$want" ]; then
    failed "$tag: expected $want, got $got: $(cat "$work/err")"
    return
  fi
  if [ "$want" = accept ]; then
    local version=$4
    if ! grep -qx "version=$version" "$work/out"; then
      failed "$tag: expected version=$version, got: $(cat "$work/out")"
      return
    fi
    if [ "$(cat "$work/requests")" != "/com/oddin/oddsfeed/odds-feed/$version/odds-feed-$version.pom" ]; then
      failed "$tag: the registry was asked for $(cat "$work/requests"), not odds-feed $version's POM"
      return
    fi
  elif ! grep -qF -- "$4" "$work/err"; then
    failed "$tag: refused, but not because \"$4\": $(cat "$work/err")"
    return
  fi
  echo "ok   $want $tag: $(tail -n 1 "$work/err")"
}

malformed="v0.0v.62 v0.0.63.1 v0.0.058 v0.0.58-rc0 v0.0.58-rc.1 v1.0.0 0.0.58"
commit v0.0.58
commit v0.0.59-rc1
commit v0.0.60 v0.0.60-rc1
commit v0.0.61 latest
for tag in $malformed; do
  commit "$tag"
done
commit v0.0.64
commit v0.0.65

expect accept v0.0.58 "$absent" 0.0.58
expect accept v0.0.59-rc1 "$absent" 0.0.59-rc1
expect refuse v0.0.58 "$registry/200" "GitHub Packages already has odds-feed 0.0.58"
expect refuse v0.0.58 "$registry/302" "GitHub Packages already has odds-feed 0.0.58"
for status in 401 500; do
  expect refuse v0.0.58 "$registry/$status" "could not tell whether GitHub Packages has odds-feed 0.0.58 ($status"
done
expect refuse v0.0.58 "$silent" "could not tell whether GitHub Packages has odds-feed 0.0.58 (no answer"
expect refuse v0.0.58 "$registry/stall" "could not tell whether GitHub Packages has odds-feed 0.0.58 (no answer"

for tag in $malformed; do
  expect refuse "$tag" "$absent" "neither v0.MINOR.PATCH nor v0.MINOR.PATCH-rcN"
done
expect refuse v0.0.99 "$absent" "no such tag"
expect refuse v0.0.60 "$absent" "other tags name"
expect refuse v0.0.61 "$absent" "other tags name"

# a run whose tag was moved or deleted on GitHub after it was pushed
git clone -q "$repo" "$work/clone"
expect accept v0.0.64 "$absent" 0.0.64 "$work/clone"
git -C "$repo" tag -f -a -m moved v0.0.64 "v0.0.58^{commit}" > /dev/null
git -C "$repo" tag -d v0.0.65 > /dev/null
expect refuse v0.0.64 "$absent" "now names $(git -C "$repo" rev-parse "v0.0.58^{commit}")" "$work/clone"
expect refuse v0.0.65 "$absent" "no longer on origin" "$work/clone"
git -C "$work/clone" remote set-url origin "$work/nowhere"
expect refuse v0.0.64 "$absent" "could not ask origin" "$work/clone"

if [ "$failures" -ne 0 ]; then
  echo "$failures release check case(s) failed" >&2
  exit 1
fi
echo "every release check case passed"
