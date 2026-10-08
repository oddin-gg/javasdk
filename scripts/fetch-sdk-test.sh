#!/usr/bin/env bash
#
# Runs the download path of scripts/fetch-sdk.sh - its fetch_sdk, with the real curl - against a
# stub server on 127.0.0.1 that serves https with a throwaway certificate, and checks what ends up
# in a scratch local repository: the jar and POM when both match their digests, through a
# redirect as a GitHub release serves them, and nothing at all when either does not, when a
# redirect leads to plain http, or when the release has no file and there is no token for the
# registry. With a token, the registry gets it as basic auth and curl's command line never holds
# it. Another run's download is never touched. No call leaves the machine. next.yml runs it on every push. Needs python3, openssl and curl.
#
# FETCH_SDK_TEST_BASH runs fetch_sdk in another bash, e.g. an old one: the default is bash.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
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

version=9.9.9
token=test-token-not-real
printf 'the jar\n' > "$work/good.jar"
printf '<project>the pom</project>\n' > "$work/good.pom"
digest() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}
{
  echo "$version.jar=$(digest "$work/good.jar")"
  echo "$version.pom=$(digest "$work/good.pom")"
} > "$work/checksums.properties"

openssl req -x509 -newkey rsa:2048 -nodes -keyout "$work/key.pem" -out "$work/cert.pem" -days 1 \
  -subj /CN=127.0.0.1 -addext subjectAltName=IP:127.0.0.1 2>/dev/null

# The stub: one https and one http listener. Each request is logged as "<scheme> <path> <auth>".
# Paths are /<mode>/odds-feed-<version>.<kind>; the mode says how to answer.
cat > "$work/server.py" <<'EOF'
import base64, http.server, ssl, sys, threading

work, token = sys.argv[1], sys.argv[2]
good = {kind: open("%s/good.%s" % (work, kind), "rb").read() for kind in ("jar", "pom")}
ports = {}

class Handler(http.server.BaseHTTPRequestHandler):
    scheme = None

    def log_message(self, *args):
        pass

    def answer(self, status, body=b"", location=None):
        self.send_response(status)
        if location:
            self.send_header("Location", location)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        auth = self.headers.get("Authorization", "-")
        with open(work + "/requests.log", "a") as log:
            log.write("%s %s %s\n" % (self.scheme, self.path, auth))
        _, mode, name = self.path.split("/", 2)
        kind = name.rsplit(".", 1)[-1]
        if mode == "ok":
            self.answer(200, good[kind])
        elif mode == "via-redirect":
            self.answer(302, location="https://127.0.0.1:%d/ok/%s" % (ports["https"], name))
        elif mode == "to-http":
            self.answer(302, location="http://127.0.0.1:%d/ok/%s" % (ports["http"], name))
        elif mode == "bad-jar":
            self.answer(200, b"another jar" if kind == "jar" else good[kind])
        elif mode == "bad-pom":
            self.answer(200, b"<project>another pom</project>" if kind == "pom" else good[kind])
        elif mode == "registry":
            expected = "Basic " + base64.b64encode(("x:" + token).encode()).decode()
            if auth == expected:
                self.answer(200, good[kind])
            else:
                self.answer(401)
        else:
            self.answer(404)

def serve(scheme):
    handler = type(scheme, (Handler,), {"scheme": scheme})
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    if scheme == "https":
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(work + "/cert.pem", work + "/key.pem")
        httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    ports[scheme] = httpd.server_address[1]
    return httpd

servers = [serve("https"), serve("http")]
for httpd in servers:
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
with open(work + "/ports.part", "w") as f:
    f.write("%d %d\n" % (ports["https"], ports["http"]))
import os
os.rename(work + "/ports.part", work + "/ports")
threading.Event().wait()
EOF
python3 "$work/server.py" "$work" "$token" &
server=$!
for _ in $(seq 1 100); do
  [ -f "$work/ports" ] && break
  sleep 0.1
done
if [ ! -f "$work/ports" ]; then
  echo "FAIL the stub server did not start" >&2
  exit 1
fi
read -r https_port http_port < "$work/ports"
base=https://127.0.0.1:$https_port

# curl as fetch_sdk finds it on PATH: the real one, with its command line recorded
real_curl=$(command -v curl)
mkdir -p "$work/bin"
cat > "$work/bin/curl" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$work/curl-args.log"
exec "$real_curl" "\$@"
EOF
chmod +x "$work/bin/curl"

repo=$work/repo/com/oddin/oddsfeed/odds-feed/$version
jar=$repo/odds-feed-$version.jar
pom=$repo/odds-feed-$version.pom

failures=0
fail() {
  echo "FAIL $1" >&2
  failures=$((failures + 1))
}

# fetch <release mode> [token]: runs fetch_sdk with the release at /<mode>, the registry at
# /registry; its output goes to $work/out
fetch() {
  local release=$1 with_token=${2:-}
  : > "$work/requests.log"
  : > "$work/curl-args.log"
  env -u GITHUB_ACTOR -u GH_TOKEN PATH="$work/bin:$PATH" CURL_CA_BUNDLE="$work/cert.pem" \
      GITHUB_TOKEN="$with_token" \
    "${FETCH_SDK_TEST_BASH:-bash}" -c '. "$1"; shift; fetch_sdk "$@"' _ "$root/scripts/fetch-sdk.sh" \
      "$version" "$repo" "$base/$release" "$base/registry" "$work/checksums.properties" \
    > "$work/out" 2>&1
}

# refused <what> <release mode> <message> [token]: fails with the message, and leaves nothing
refused() {
  local what=$1 mode=$2 message=$3
  rm -rf "$work/repo"
  if fetch "$mode" "${4:-}"; then
    fail "$what: fetch_sdk succeeded: $(cat "$work/out")"
  elif ! grep -qF -- "$message" "$work/out"; then
    fail "$what: fetch_sdk failed, but not with \"$message\": $(cat "$work/out")"
  elif [ -n "$(ls -A "$repo" 2>/dev/null)" ]; then
    fail "$what: refused, but left $(ls -A "$repo" | tr '\n' ' ')in the local repository"
  else
    echo "ok   $what: refused, nothing installed"
  fi
}

# installed <what>: both files are there, as served, with no download left beside them
installed() {
  if ! cmp -s "$jar" "$work/good.jar" || ! cmp -s "$pom" "$work/good.pom"; then
    fail "$1: the jar and POM are not the ones served: $(cat "$work/out")"
  elif [ "$(ls -A "$repo" | tr '\n' ' ')" != "odds-feed-$version.jar odds-feed-$version.pom " ]; then
    fail "$1: the local repository holds $(ls -A "$repo" | tr '\n' ' ')"
  else
    echo "ok   $1: installed"
  fi
}

# the release's files, through a redirect to https as GitHub serves them
rm -rf "$work/repo"
if fetch via-redirect; then installed "a release behind an https redirect"
else fail "a release behind an https redirect: $(cat "$work/out")"; fi
if grep -q '^http ' "$work/requests.log"; then fail "plain http was asked for something"; fi

# already there and matching: nothing asked
if fetch via-redirect && [ ! -s "$work/requests.log" ] && grep -q "^ok jar" "$work/out" && grep -q "^ok pom" "$work/out"; then
  echo "ok   files already there are checked, not fetched again"
else
  fail "files already there: $(cat "$work/out"); requests: $(cat "$work/requests.log")"
fi

# one changed in place is fetched again
printf 'changed\n' >> "$pom"
if fetch ok && grep -q "^fetched pom" "$work/out" && grep -q "^ok jar" "$work/out"; then installed "a POM changed in place, fetched again"
else fail "a POM changed in place: $(cat "$work/out")"; fi

# downloads of other runs: one an interrupted run left hours ago is removed, one another run is
# writing now is left alone, and neither is moved into place
stale=$repo/.odds-feed-$version.jar.part-stale1
running=$repo/.odds-feed-$version.pom.part-other1
printf 'left behind\n' > "$stale"
touch -t 202001010000 "$stale"
printf 'another run\n' > "$running"
rm -f "$pom"
if ! fetch ok || ! grep -q "^ok jar" "$work/out" || ! grep -q "^fetched pom" "$work/out"; then
  fail "other runs' downloads: $(cat "$work/out")"
elif [ -e "$stale" ]; then
  fail "a download an interrupted run left behind was not removed"
elif [ "$(cat "$running" 2>/dev/null)" != "another run" ]; then
  fail "another run's download was touched"
else
  rm -f "$running"
  installed "other runs' downloads, never moved into place"
fi

refused "a redirect to plain http" to-http "could not fetch $base/to-http/odds-feed-$version.jar"
if grep -q '^http ' "$work/requests.log"; then fail "a redirect to plain http was followed"; fi
refused "a jar that does not match its digest" bad-jar "jar for odds-feed $version from $base/bad-jar/odds-feed-$version.jar does not match"
refused "a POM that does not match its digest, the jar matching" bad-pom "pom for odds-feed $version from $base/bad-pom/odds-feed-$version.pom does not match"
refused "no release file and no token" missing "instead needs GITHUB_TOKEN"
if grep -q ' /registry/' "$work/requests.log"; then fail "the registry was asked without a token"; fi
refused "no release file and a token the registry refuses" missing "could not fetch $base/registry/odds-feed-$version.jar (status: 401)" wrong-token

# no release file and a token: the registry's files, the token sent as basic auth and never on
# curl's command line
rm -rf "$work/repo"
if fetch missing "$token"; then installed "no release file, the registry with a token"
else fail "no release file, the registry with a token: $(cat "$work/out")"; fi
if [ "$(grep -c ' /registry/.* Basic ' "$work/requests.log")" != 2 ]; then
  fail "the registry did not get the token on both requests: $(cat "$work/requests.log")"
fi
if grep -qF -- "$token" "$work/curl-args.log"; then
  fail "the token was on curl's command line"
elif [ ! -s "$work/curl-args.log" ]; then
  fail "curl's command lines were not recorded, so the token check proved nothing"
else
  echo "ok   the token reached the registry, and never curl's command line"
fi
if grep ' /missing/' "$work/requests.log" | grep -q ' Basic '; then
  fail "the release was sent the token"
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures fetch-sdk.sh cases failed" >&2
  exit 1
fi
echo "every fetch-sdk.sh case passed"
