#!/usr/bin/env bash
#
# Runs scripts/release-signing-key-check.sh against keys made here, and fails unless the secret
# of the layout RELEASING.md describes - a subkey export, the primary a stub - passes, and each
# other secret is refused for the reason it should be: the export of the whole key, the primary's
# secret included; another key than RELEASE_KEY_FINGERPRINT names; a key without a signing
# subkey, or with one that is revoked or expired; a secret that is empty, no key or only a public
# key; a variable that is unset or no fingerprint. No refusal may print any of the key. next.yml
# runs it on every push. The secret is also held against the keyservers as they are when it is
# checked - stub ones here, with the public keys as they were or became: a secret exported before
# its subkey was revoked and replaced, or before its subkey was published, is refused. Needs bash,
# curl, gpg and python3.
set -euo pipefail

for tool in curl gpg python3; do
  command -v "$tool" > /dev/null || { echo "needs $tool" >&2; exit 1; }
done

root=$(cd "$(dirname "$0")/.." && pwd)
check=${RELEASE_SIGNING_KEY_CHECK:-$root/scripts/release-signing-key-check.sh}
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

# the stub keyservers of release-key-check-test.sh
python3 "$root/scripts/release-keyserver-stub.py" "$work/port" "$work/www" "$work/requests" &
server=$!
for _ in $(seq 100); do
  [ -s "$work/port" ] && break
  sleep 0.1
done
[ -s "$work/port" ] || { echo "the stub keyservers did not start" >&2; exit 1; }
stub=http://127.0.0.1:$(cat "$work/port")

gpgh() {
  local home=$1
  shift
  GNUPGHOME=$work/homes/$home gpg --batch --no-tty --pinentry-mode loopback --passphrase '' "$@" 2> /dev/null
}
# gen <home> <usage of the primary> [fake time]
gen() {
  local home=$1 usage=$2 when=${3:-} faked=()
  mkdir -m 700 "$work/homes/$home"
  [ -z "$when" ] || faked=(--faked-system-time "$(date -u -d "$when" +%Y%m%dT%H%M%S)")
  gpgh "$home" "${faked[@]}" --quick-gen-key "Release <release@example.invalid>" ed25519 "$usage" never
  gpgh "$home" --list-keys --with-colons | awk -F: '$1 == "fpr" { print $10; exit }' > "$work/homes/$home.fpr"
}
fpr() { cat "$work/homes/$1.fpr"; }
# addsub <home> <algorithm> <usage> <expiry> [fake time]: the new subkey's fingerprint on stdout
addsub() {
  local home=$1 when=${5:-} faked=() before
  [ -z "$when" ] || faked=(--faked-system-time "$(date -u -d "$when" +%Y%m%dT%H%M%S)")
  before=$(subs "$home")
  gpgh "$home" "${faked[@]}" --quick-add-key "$(fpr "$home")" "$2" "$3" "$4"
  subs "$home" | grep -vxF -e "$before" || true
}
subs() {
  gpgh "$1" --list-keys --with-colons | awk -F: '$1 == "sub" { sub_ = 1 } $1 == "fpr" && sub_ { print $10; sub_ = 0 }'
}
revoke() {
  printf 'key %s\nrevkey\ny\n0\n\ny\nsave\n' "$2" | gpgh "$1" --command-fd 0 --edit-key "$(fpr "$1")" > /dev/null
}
# the secrets as the environment could hold them
subkeys() { gpgh "$1" --armor --export-secret-subkeys "$(fpr "$1")"; }
whole() { gpgh "$1" --armor --export-secret-keys "$(fpr "$1")"; }
public() { gpgh "$1" --armor --export "$(fpr "$1")"; }
# what the keyservers serve of a key at this moment, keys.openpgp.org without user IDs: both <label> <home>
both() {
  mkdir -p "$work/www/openpgp/$1" "$work/www/ubuntu/$1"
  gpgh "$2" --armor --export --export-filter keep-uid='uid=none-at-all' "$(fpr "$2")" > "$work/www/openpgp/$1/key"
  public "$2" > "$work/www/ubuntu/$1/key"
}

gen fit cert
addsub fit ed25519 sign 2y > /dev/null
gen other cert
addsub other ed25519 sign 2y > /dev/null
gen nosigning cert
addsub nosigning cv25519 encr 2y > /dev/null
gen primaryonly cert,sign
gen revoked cert
addsub revoked ed25519 sign 2y > /dev/null
revoke revoked "$(subs revoked)"
gen expired cert "-60 days"
addsub expired ed25519 sign 7d "-60 days" > /dev/null
# the older subkey fit, the newer revoked: still a signing subkey that is fit
gen older cert
addsub older ed25519 sign 2y > /dev/null
sleep 1.1
addsub older ed25519 sign 2y > /dev/null
revoke older "$(subs older | tail -1)"

# a secret exported before its subkey was revoked and replaced: the keyservers have the revocation
# and the new subkey by now
gen rotated cert
oldsub=$(addsub rotated ed25519 sign 2y)
subkeys rotated > "$work/rotated.subkeys"
sleep 1.1
revoke rotated "$oldsub"
addsub rotated ed25519 sign 2y > /dev/null
both exported-before-revocation rotated
# a secret exported after a new subkey, which the keyservers do not have yet
gen unpublished cert
addsub unpublished ed25519 sign 2y > /dev/null
both exported-before-publication unpublished
sleep 1.1
newsub=$(addsub unpublished ed25519 sign 2y)
subkeys unpublished > "$work/unpublished.subkeys"

# a secret whose subkey is about to expire while a newer one is fit, both on the keyservers: the
# secret holds the older (exported before the newer was made), and the newer. The date is worked out
# as the key is made, so that midnight in between does not move it.
gen nearold cert
oldnear=$(addsub nearold ed25519 sign 10d)
oldnear_date=$(date -u -d '+10 days' +%Y-%m-%d)
subkeys nearold > "$work/nearold.older"
sleep 1.1
addsub nearold ed25519 sign 2y > /dev/null
subkeys nearold > "$work/nearold.both"
both older-expiring nearold
both newer-held nearold
# a secret exported while its subkey was valid, the keyservers showing it expired since: made 60 days
# ago with a year of life, then given a day from 30 days ago
gen shownexpired cert "-60 days"
expiredsub=$(addsub shownexpired ed25519 sign 365d "-60 days")
subkeys shownexpired > "$work/shownexpired.subkeys"
gpgh shownexpired --faked-system-time "$(date -u -d '-30 days' +%Y%m%dT%H%M%S)" --quick-set-expire "$(fpr shownexpired)" 1d "$expiredsub"
expired_date=$(date -u -d '-29 days' +%Y-%m-%d)
both expired-on-servers shownexpired

failures=0
# run <label> <secret file, "unset" or "empty"> <variable value, "unset" or "empty"> <"ok", or the texts the refusal has>...
run() {
  local label=$1 secret=$2 variable=$3 out status=0
  shift 3
  local env=() assign=()
  case "$variable" in
    unset) env+=(-u RELEASE_KEY_FINGERPRINT) ;;
    empty) assign+=(RELEASE_KEY_FINGERPRINT=) ;;
    *) assign+=(RELEASE_KEY_FINGERPRINT="$variable") ;;
  esac
  case "$secret" in
    unset) env+=(-u MAVEN_GPG_KEY) ;;
    empty) assign+=(MAVEN_GPG_KEY=) ;;
    *) assign+=(MAVEN_GPG_KEY="$(cat "$secret")") ;;
  esac
  env+=("${assign[@]}")
  out=$(env "${env[@]}" KEYS_OPENPGP_URL="$stub/openpgp/$label" KEYSERVER_UBUNTU_URL="$stub/ubuntu/$label" KEYSERVER_TIMEOUT=5 \
    "$check" 2>&1) || status=$?
  # nothing of the key in what it says, whatever it decides: no armor, and no line of the body of
  # the secret it was given
  if [ -f "$secret" ] && { grep -q 'BEGIN PGP\|PRIVATE KEY' <<< "$out" || grep -qFx -f <(sed -n '/^$/,/^=/{/^[^=]/p}' "$secret" | grep .) <<< "$out"; }; then
    echo "FAIL $label: the output prints key material: $out" >&2
    failures=$((failures + 1))
    return
  fi
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

subkeys fit > "$work/fit.subkeys"
whole fit > "$work/fit.whole"
subkeys other > "$work/other.subkeys"
whole other > "$work/other.whole"
subkeys nosigning > "$work/nosigning.subkeys"
subkeys primaryonly > "$work/primaryonly.subkeys"
subkeys revoked > "$work/revoked.subkeys"
subkeys expired > "$work/expired.subkeys"
subkeys older > "$work/older.subkeys"
public fit > "$work/fit.public"
echo "not a key" > "$work/garbage"

fit=$(fpr fit)
both fit fit
both lowercase fit
both older older
run fit "$work/fit.subkeys" "$fit" ok
run lowercase "$work/fit.subkeys" "$(tr 'A-F' 'a-f' <<< "$fit")" ok
run older "$work/older.subkeys" "$(fpr older)" ok
# the secret of one subkey, the keyservers having revoked it and made another since; and a secret
# whose newest subkey the keyservers do not have yet
run exported-before-revocation "$work/rotated.subkeys" "$(fpr rotated)" \
  "keys.openpgp.org: the subkey $oldsub MAVEN_GPG_KEY signs with is revoked" \
  "keyserver.ubuntu.com: the subkey $oldsub MAVEN_GPG_KEY signs with is revoked"
run exported-before-publication "$work/unpublished.subkeys" "$(fpr unpublished)" \
  "keys.openpgp.org: the key $(fpr unpublished) has no signing subkey $newsub" \
  "keyserver.ubuntu.com: the key $(fpr unpublished) has no signing subkey $newsub"
# the subkey the secret signs with is about to expire, however fit the newer one on the keyservers
# is: the secret must be replaced; and it is fit when the secret holds the newer one
run older-expiring "$work/nearold.older" "$(fpr nearold)" \
  "keys.openpgp.org: the subkey $oldnear MAVEN_GPG_KEY signs with expires on $oldnear_date, in less than 30 days" \
  "keyserver.ubuntu.com: the subkey $oldnear MAVEN_GPG_KEY signs with expires on $oldnear_date, in less than 30 days"
run newer-held "$work/nearold.both" "$(fpr nearold)" ok
# the keyservers show it expired
run expired-on-servers "$work/shownexpired.subkeys" "$(fpr shownexpired)" \
  "keys.openpgp.org: the subkey $expiredsub MAVEN_GPG_KEY signs with expired on $expired_date" \
  "keyserver.ubuntu.com: the subkey $expiredsub MAVEN_GPG_KEY signs with expired on $expired_date"
# more than one secret key in the import: a valid subkey export, and another key whole
both two-keys fit
both two-keys-reversed fit
cat "$work/fit.subkeys" "$work/other.whole" > "$work/two.keys"
run two-keys "$work/two.keys" "$fit" "holds 2 secret keys, not one"
cat "$work/other.whole" "$work/fit.subkeys" > "$work/two.reversed.keys"
run two-keys-reversed "$work/two.reversed.keys" "$fit" "holds 2 secret keys, not one"
run whole "$work/fit.whole" "$fit" "carries the secret of the primary key $fit" "gpg --export-secret-subkeys"
run other "$work/other.subkeys" "$fit" "is the key $(fpr other), not $fit of RELEASE_KEY_FINGERPRINT"
run nosigning "$work/nosigning.subkeys" "$(fpr nosigning)" "no secret signing subkey"
run primaryonly "$work/primaryonly.subkeys" "$(fpr primaryonly)" "holds no secret key"
run revoked "$work/revoked.subkeys" "$(fpr revoked)" "no secret signing subkey that is neither revoked nor expired"
run expired "$work/expired.subkeys" "$(fpr expired)" "no secret signing subkey that is neither revoked nor expired"
run unset "$work/fit.subkeys" unset "RELEASE_KEY_FINGERPRINT" "RELEASING.md"
run empty "$work/fit.subkeys" empty "RELEASE_KEY_FINGERPRINT" "RELEASING.md"
run malformed "$work/fit.subkeys" "${fit:0:39}" "RELEASE_KEY_FINGERPRINT"
run nosecret unset "$fit" "MAVEN_GPG_KEY is empty"
run emptysecret empty "$fit" "MAVEN_GPG_KEY is empty"
run garbage "$work/garbage" "$fit" "is not a key gpg can import"
run public "$work/fit.public" "$fit" "holds no secret key"

if [ "$failures" -ne 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
echo "release-signing-key-check.sh accepts the subkey export and refuses every other secret"
