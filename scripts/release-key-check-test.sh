#!/usr/bin/env bash
#
# Runs scripts/release-key-check.sh against stub keyservers and keys made here, and fails unless
# a key fit to sign is accepted and every other state is refused, with the server and the reason
# it should name: a subkey about to expire, expired or revoked, a primary that signs but no
# signing subkey, another key than the variable names, a variable not set or not a fingerprint,
# a server without the key, one still serving an old copy of a renewed key, one that stalls or
# fails or answers with something else than a key. keys.openpgp.org serves a key without its user
# IDs when their email is not verified, which gpg handles in ways of its own; its stub serves
# that form. next.yml runs it on every push. Needs bash, curl, gpg and python3.
set -euo pipefail

for tool in curl gpg python3; do
  command -v "$tool" > /dev/null || { echo "needs $tool" >&2; exit 1; }
done

root=$(cd "$(dirname "$0")/.." && pwd)
check=${RELEASE_KEY_CHECK:-$root/scripts/release-key-check.sh}
work=$(mktemp -d)
chmod 700 "$work"
server=
cleanup() {
  local home
  for home in "$work"/homes/*; do
    [ -d "$home" ] && gpgconf --homedir "$home" --kill all 2> /dev/null || true
  done
  if [ -n "$server" ]; then
    kill "$server" 2> /dev/null || true
    wait "$server" 2> /dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT
mkdir -p "$work/homes" "$work/www"

# Stub keyservers, one site per server name. /<server>/<label>/<what the real server is asked>:
# the directory www/<server>/<label> holds a file "key" (answered with 200), "status" (that
# status), or "stall" (404, after 10 seconds); without any, 404. The request must have the form
# the real server takes - by-fingerprint on keys.openpgp.org, the machine-readable lookup of
# 0x<fingerprint> on keyserver.ubuntu.com - with an upper-case fingerprint, or it gets a 400.
# Every request is recorded.
python3 - "$work/port" "$work/www" "$work/requests" <<'EOF' &
import http.server, os, re, sys, time
port_file, www, log = sys.argv[1:]
SHAPES = {
    'openpgp': re.compile(r'^vks/v1/by-fingerprint/[0-9A-F]{40}$'),
    'ubuntu': re.compile(r'^pks/lookup\?op=get&options=mr&search=0x[0-9A-F]{40}$'),
}
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        site, label, rest = self.path[1:].split('/', 2)
        with open(log, 'a') as f:
            f.write('%s %s %s\n' % (site, label, rest))
        body = b''
        if not SHAPES[site].match(rest):
            status = 400
        else:
            folder = os.path.join(www, site, label)
            if os.path.exists(os.path.join(folder, 'key')):
                status = 200
                body = open(os.path.join(folder, 'key'), 'rb').read()
            elif os.path.exists(os.path.join(folder, 'status')):
                status = int(open(os.path.join(folder, 'status')).read())
            elif os.path.exists(os.path.join(folder, 'stall')):
                time.sleep(10)
                status = 404
            else:
                status = 404
        self.send_response(status)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
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
[ -s "$work/port" ] || { echo "the stub keyservers did not start" >&2; exit 1; }
stub=http://127.0.0.1:$(cat "$work/port")

# ---- keys

# gpgh <home> <gpg arguments>: gpg in the named throwaway home, asking nothing
gpgh() {
  local home=$1
  shift
  GNUPGHOME=$work/homes/$home gpg --batch --no-tty --pinentry-mode loopback --passphrase '' "$@" 2> /dev/null
}

# gen <home> <usage of the primary> [fake time]: a key made at the fake time ("-60 days" says
# how far back); its primary's fingerprint goes to $work/homes/<home>.fpr
gen() {
  local home=$1 usage=$2 when=${3:-} faked=()
  mkdir -m 700 "$work/homes/$home"
  [ -z "$when" ] || faked=(--faked-system-time "$(date -u -d "$when" +%Y%m%dT%H%M%S)")
  gpgh "$home" "${faked[@]}" --quick-gen-key "Release <release@example.invalid>" ed25519 "$usage" never
  gpgh "$home" --list-keys --with-colons | awk -F: '$1 == "fpr" { print $10; exit }' > "$work/homes/$home.fpr"
}
fpr() { cat "$work/homes/$1.fpr"; }

# addsub <home> <expiry> [fake time]: a signing subkey, its fingerprint on stdout
addsub() {
  local home=$1 expiry=$2 when=${3:-} faked=()
  [ -z "$when" ] || faked=(--faked-system-time "$(date -u -d "$when" +%Y%m%dT%H%M%S)")
  local before
  before=$(subs "$home")
  gpgh "$home" "${faked[@]}" --quick-add-key "$(fpr "$home")" ed25519 sign "$expiry"
  subs "$home" | grep -vxF -e "$before" || true
}
# subs <home>: the fingerprints of the subkeys, oldest first
subs() {
  gpgh "$1" --list-keys --with-colons | awk -F: '$1 == "sub" { sub_ = 1 } $1 == "fpr" && sub_ { print $10; sub_ = 0 }'
}

# revoke <home> [subkey fingerprint]: the subkey, or the whole key without one
revoke() {
  local home=$1 sub=${2:-} select=
  [ -z "$sub" ] || select="key $sub"
  printf '%s\nrevkey\ny\n0\n\ny\nsave\n' "$select" | gpgh "$home" --command-fd 0 --edit-key "$(fpr "$home")" > /dev/null
}

# the public key as keyserver.ubuntu.com serves it, and as keys.openpgp.org does with no user ID
# verified: no user IDs, and so none of their signatures
full() { gpgh "$1" --armor --export "$(fpr "$1")"; }
nouid() { gpgh "$1" --armor --export --export-filter keep-uid='uid=none-at-all' "$(fpr "$1")"; }

# put <server> <label> <file>, status <server> <label> <status>, stall <server> <label>
put() { mkdir -p "$work/www/$1/$2"; cp "$3" "$work/www/$1/$2/key"; }
status() { mkdir -p "$work/www/$1/$2"; echo "$3" > "$work/www/$1/$2/status"; }
stall() { mkdir -p "$work/www/$1/$2"; touch "$work/www/$1/$2/stall"; }
# both <label> <home>: both servers serve the key of the home, openpgp without user IDs
both() {
  full "$2" > "$work/$1.full"
  nouid "$2" > "$work/$1.nouid"
  put openpgp "$1" "$work/$1.nouid"
  put ubuntu "$1" "$work/$1.full"
}

# the fit key: a primary that only certifies, a signing subkey of two years
gen fit cert
addsub fit 2y > /dev/null

# a subkey of no expiry, a rotation (old one about to expire, new one fit) and its opposite
gen forever cert
addsub forever never > /dev/null
gen rotated cert
addsub rotated 10d > /dev/null
sleep 1.1
addsub rotated 2y > /dev/null
gen rotating cert
addsub rotating 2y > /dev/null
sleep 1.1
addsub rotating 10d > /dev/null

# the day before the margin and the day after
gen near cert
addsub near 10d > /dev/null
gen edge cert
addsub edge 29d > /dev/null
gen outside cert
addsub outside 31d > /dev/null

# ran out five days ago, made 60 days ago with 55 days of life
gen expired cert "-60 days"
addsub expired 55d "-60 days" > /dev/null

# revoked
gen revoked cert
revoke revoked "$(addsub revoked 2y)"
# the older subkey fit and the newer one revoked: gpg would sign with the older one, which is fit
gen revokednew cert
addsub revokednew 2y > /dev/null
sleep 1.1
revoke revokednew "$(addsub revokednew 2y)"
gen revokedkey cert
addsub revokedkey 2y > /dev/null
revoke revokedkey

# a primary that signs, and no subkey at all; and one with an encryption subkey only
gen primaryonly cert,sign
gen encryptonly cert
gpgh encryptonly --quick-add-key "$(fpr encryptonly)" cv25519 encr 2y

# a key whose subkey is revoked after copies of it were taken
gen late cert
addsub late 2y > /dev/null
full late > "$work/late.good.full"
nouid late > "$work/late.good.nouid"
sleep 1.1
revoke late "$(subs late)"
full late > "$work/late.rev.full"
nouid late > "$work/late.rev.nouid"

# a subkey that was revoked and replaced, with a copy taken before and one after
gen swapped cert
oldsub=$(addsub swapped 2y)
full swapped > "$work/swapped.old.full"
nouid swapped > "$work/swapped.old.nouid"
sleep 1.1
revoke swapped "$oldsub"
addsub swapped 2y > /dev/null
full swapped > "$work/swapped.new.full"
nouid swapped > "$work/swapped.new.nouid"

# a subkey of 90 days, renewed to two years: both copies are fit alone
gen lagging cert
lagsub=$(addsub lagging 90d)
full lagging > "$work/lagging.old.full"
nouid lagging > "$work/lagging.old.nouid"
gpgh lagging --quick-set-expire "$(fpr lagging)" 2y "$lagsub"
full lagging > "$work/lagging.new.full"
nouid lagging > "$work/lagging.new.nouid"

# another key, for a fingerprint that is not the served one
gen other cert
addsub other 2y > /dev/null

# a key one copy of which is old: made 60 days ago with a subkey of 7 days, then renewed
gen renewed cert "-60 days"
sub=$(addsub renewed 7d "-60 days")
full renewed > "$work/renewed.old.full"
nouid renewed > "$work/renewed.old.nouid"
gpgh renewed --quick-set-expire "$(fpr renewed)" 2y "$sub"
full renewed > "$work/renewed.new.full"
nouid renewed > "$work/renewed.new.nouid"

# ---- cases

failures=0
cases=0
# run <label> <variable: a value, "unset" or "empty"> <timeout> <"ok", or the texts the refusal has>...
# base_openpgp and base_ubuntu, when set, replace the stub's address for the server
run() {
  local label=$1 variable=$2 limit=$3 out status=0
  shift 3
  cases=$((cases + 1))
  local env=(KEYS_OPENPGP_URL="${base_openpgp:-$stub/openpgp/$label}" KEYSERVER_UBUNTU_URL="${base_ubuntu:-$stub/ubuntu/$label}"
    KEYSERVER_TIMEOUT="$limit")
  case "$variable" in
    unset) env=(-u RELEASE_KEY_FINGERPRINT "${env[@]}") ;;
    empty) env+=(RELEASE_KEY_FINGERPRINT=) ;;
    *) env+=(RELEASE_KEY_FINGERPRINT="$variable") ;;
  esac
  out=$(env "${env[@]}" "$check" 2>&1) || status=$?
  if [ "$1" = ok ]; then
    if [ "$status" -ne 0 ]; then
      echo "FAIL $label: refused ($status), should pass: $out" >&2
      failures=$((failures + 1))
    else
      echo "ok   $label: passes"
    fi
    return
  fi
  if [ "$status" -eq 0 ]; then
    echo "FAIL $label: passed, should be refused for \"$1\"" >&2
    failures=$((failures + 1))
    return
  fi
  local reason
  for reason in "$@"; do
    if ! grep -qF -- "$reason" <<< "$out"; then
      echo "FAIL $label: refused, but not with \"$reason\": $out" >&2
      failures=$((failures + 1))
      return
    fi
  done
  echo "ok   $label: refused: $1"
}

fit=$(fpr fit)
both fit fit
run fit "$fit" 5 ok
if [ "$(grep -c . "$work/requests")" -ne 2 ] \
  || ! grep -qxF "openpgp fit vks/v1/by-fingerprint/$fit" "$work/requests" \
  || ! grep -qxF "ubuntu fit pks/lookup?op=get&options=mr&search=0x$fit" "$work/requests"; then
  echo "FAIL fit: the requests are not the two lookups by fingerprint: $(cat "$work/requests")" >&2
  failures=$((failures + 1))
fi
# the stub's form for keys.openpgp.org really has no user ID, so the cases cover that form
if gpg --list-packets "$work/fit.nouid" 2> /dev/null | grep -q 'user ID packet' \
  || ! gpg --list-packets "$work/fit.nouid" 2> /dev/null | grep -q 'public sub key packet'; then
  echo "FAIL the stub's keys.openpgp.org form must have a subkey and no user ID" >&2
  failures=$((failures + 1))
fi
both fitlower fit
run fitlower "$(tr 'A-F' 'a-f' <<< "$fit")" 5 ok
both forever forever
run forever "$(fpr forever)" 5 ok
both rotated rotated
run rotated "$(fpr rotated)" 5 ok
both outside outside
run outside "$(fpr outside)" 5 ok

both near near
run near "$(fpr near)" 5 "keys.openpgp.org: the signing subkey" "keyserver.ubuntu.com: the signing subkey" "less than 30" "renew it as RELEASING.md says"
both edge edge
run edge "$(fpr edge)" 5 "less than 30"
both rotating rotating
run rotating "$(fpr rotating)" 5 "less than 30"
both expired expired
run expired "$(fpr expired)" 5 "keys.openpgp.org: the signing subkey" "keyserver.ubuntu.com: the signing subkey" "expired on $(date -u -d '-5 days' +%Y-%m-%d)" "no signing subkey of the key"
both revoked revoked
run revoked "$(fpr revoked)" 5 "keys.openpgp.org: the signing subkey" "keyserver.ubuntu.com: the signing subkey" "is revoked"
# the older subkey is the one gpg signs with, and it is fit
both revokednew revokednew
run revokednew "$(fpr revokednew)" 5 ok
both revokedkey revokedkey
run revokedkey "$(fpr revokedkey)" 5 "keys.openpgp.org: the key $(fpr revokedkey) is revoked" "keyserver.ubuntu.com: the key $(fpr revokedkey) is revoked"
both primaryonly primaryonly
run primaryonly "$(fpr primaryonly)" 5 "keys.openpgp.org: the key $(fpr primaryonly) has no signing subkey" "keyserver.ubuntu.com: the key $(fpr primaryonly) has no signing subkey"
both encryptonly encryptonly
run encryptonly "$(fpr encryptonly)" 5 "has no signing subkey"

# a fingerprint that is not the key the servers serve
both mismatch fit
run mismatch "$(fpr other)" 5 "keys.openpgp.org serves $fit, not the key $(fpr other)" "keyserver.ubuntu.com serves $fit, not the key $(fpr other)"

# the variable
both unset fit
run unset unset 5 "RELEASE_KEY_FINGERPRINT" "RELEASING.md"
run empty empty 5 "RELEASE_KEY_FINGERPRINT" "RELEASING.md"
for bad in "${fit:0:39}" "${fit}0" "${fit:0:39}G" "$(sed 's/./& /20' <<< "$fit")" "0x$fit" "${fit:0:8}"; do
  run "malformed-${#bad}-${bad:0:3}" "$bad" 5 "is not set to the 40 hex digits" "RELEASING.md"
done
if [ "$(grep -c '^openpgp malformed\|^ubuntu malformed' "$work/requests")" -ne 0 ]; then
  echo "FAIL a malformed variable still asked a keyserver" >&2
  failures=$((failures + 1))
fi

# a server without the key, whichever it is, and one that cannot say
put openpgp ubuntu404 "$work/fit.nouid"
run ubuntu404 "$fit" 5 "keyserver.ubuntu.com does not have the key $fit" "RELEASING.md"
put ubuntu openpgp404 "$work/fit.full"
run openpgp404 "$fit" 5 "keys.openpgp.org does not have the key $fit" "RELEASING.md"
run neither "$fit" 5 "keys.openpgp.org does not have the key" "keyserver.ubuntu.com does not have the key"
put openpgp ubuntu500 "$work/fit.nouid"
status ubuntu ubuntu500 500
run ubuntu500 "$fit" 5 "keyserver.ubuntu.com: could not ask for the key (500 from"
put ubuntu openpgp503 "$work/fit.full"
status openpgp openpgp503 503
run openpgp503 "$fit" 5 "keys.openpgp.org: could not ask for the key (503 from"
put openpgp ubuntustall "$work/fit.nouid"
stall ubuntu ubuntustall
run ubuntustall "$fit" 2 "keyserver.ubuntu.com: could not ask for the key"
put ubuntu openpgpstall "$work/fit.full"
stall openpgp openpgpstall
run openpgpstall "$fit" 2 "keys.openpgp.org: could not ask for the key"
base_openpgp=http://127.0.0.1:1 base_ubuntu=http://127.0.0.1:1 \
  run silent "$fit" 5 "keys.openpgp.org: could not ask for the key" "keyserver.ubuntu.com: could not ask for the key"

# a copy that is old on one server after a renewal on the other: Central reads both
put openpgp stale "$work/renewed.old.nouid"
put ubuntu stale "$work/renewed.new.full"
run stale "$(fpr renewed)" 5 "keys.openpgp.org: the signing subkey" "expired on $(date -u -d '-53 days' +%Y-%m-%d)" "no signing subkey of the key"
put openpgp stale2 "$work/renewed.new.nouid"
put ubuntu stale2 "$work/renewed.old.full"
run stale2 "$(fpr renewed)" 5 "keyserver.ubuntu.com: the signing subkey" "expired on $(date -u -d '-53 days' +%Y-%m-%d)"
put openpgp renewed "$work/renewed.new.nouid"
put ubuntu renewed "$work/renewed.new.full"
run renewed "$(fpr renewed)" 5 ok

# a revoked copy on one server while the other still serves the key as it was: on the one without
# user IDs gpg marks nothing, the checked signature of the revocation is what says so
put openpgp late.good "$work/late.good.nouid"
put ubuntu late.good "$work/late.good.full"
put openpgp late.rev "$work/late.rev.nouid"
put ubuntu late.rev "$work/late.good.full"
run late.rev "$(fpr late)" 5 "keys.openpgp.org: the signing subkey" "is revoked"
put openpgp late.rev2 "$work/late.good.nouid"
put ubuntu late.rev2 "$work/late.rev.full"
run late.rev2 "$(fpr late)" 5 "keyserver.ubuntu.com: the signing subkey" "is revoked"
run late.good "$(fpr late)" 5 ok

# each server is fit alone, but they do not serve the same: the old copy still has the replaced subkey
# unrevoked while the other has its revocation and the new subkey - the stolen one would pass
put openpgp swapped.fresh "$work/swapped.new.nouid"
put ubuntu swapped.fresh "$work/swapped.new.full"
run swapped.fresh "$(fpr swapped)" 5 ok
put openpgp swapped.old "$work/swapped.old.nouid"
put ubuntu swapped.old "$work/swapped.new.full"
run swapped.old "$(fpr swapped)" 5 "the servers serve different signing subkeys: $oldsub expires" "but revoked on keyserver.ubuntu.com" "is on keyserver.ubuntu.com and not on keys.openpgp.org"
put openpgp swapped.old2 "$work/swapped.new.nouid"
put ubuntu swapped.old2 "$work/swapped.old.full"
run swapped.old2 "$(fpr swapped)" 5 "the servers serve different signing subkeys: $oldsub revoked on keys.openpgp.org but expires" "is on keys.openpgp.org and not on keyserver.ubuntu.com"
# the same subkey with two expiries, both far enough away
put openpgp lagging.old "$work/lagging.old.nouid"
put ubuntu lagging.old "$work/lagging.new.full"
run lagging.old "$(fpr lagging)" 5 "the servers serve different signing subkeys: $lagsub expires" "on keys.openpgp.org but expires"
put openpgp lagging.old2 "$work/lagging.new.nouid"
put ubuntu lagging.old2 "$work/lagging.old.full"
run lagging.old2 "$(fpr lagging)" 5 "the servers serve different signing subkeys: $lagsub expires" "on keyserver.ubuntu.com"
put openpgp lagging.ok "$work/lagging.new.nouid"
put ubuntu lagging.ok "$work/lagging.new.full"
run lagging.ok "$(fpr lagging)" 5 ok

# something that is not a key
echo "<html>Not a key</html>" > "$work/garbage"
put openpgp garbage "$work/garbage"
put ubuntu garbage "$work/fit.full"
run garbage "$fit" 5 "keys.openpgp.org answered with something gpg cannot read"

echo "$cases cases"
if [ "$failures" -ne 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
echo "release-key-check.sh accepts a fit key and refuses every other state"
