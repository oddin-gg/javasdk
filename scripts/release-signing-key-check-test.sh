#!/usr/bin/env bash
#
# Runs scripts/release-signing-key-check.sh against keys made here, and fails unless the secret
# of the layout RELEASING.md describes - a subkey export, the primary a stub - passes, and each
# other secret is refused for the reason it should be: the export of the whole key, the primary's
# secret included; another key than RELEASE_KEY_FINGERPRINT names; a key without a signing
# subkey, or with one that is revoked or expired; a secret that is empty, no key or only a public
# key; a variable that is unset or no fingerprint. No refusal may print any of the key. next.yml
# runs it on every push. Needs bash, gpg and python3.
set -euo pipefail

for tool in gpg python3; do
  command -v "$tool" > /dev/null || { echo "needs $tool" >&2; exit 1; }
done

root=$(cd "$(dirname "$0")/.." && pwd)
check=${RELEASE_SIGNING_KEY_CHECK:-$root/scripts/release-signing-key-check.sh}
work=$(mktemp -d)
chmod 700 "$work"
cleanup() {
  local home
  for home in "$work"/homes/*; do
    [ -d "$home" ] && gpgconf --homedir "$home" --kill all 2> /dev/null || true
  done
  rm -rf "$work"
}
trap cleanup EXIT
mkdir -p "$work/homes"

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
# addsub <home> <algorithm> <usage> <expiry> [fake time]
addsub() {
  local home=$1 when=${5:-} faked=()
  [ -z "$when" ] || faked=(--faked-system-time "$(date -u -d "$when" +%Y%m%dT%H%M%S)")
  gpgh "$home" "${faked[@]}" --quick-add-key "$(fpr "$home")" "$2" "$3" "$4"
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

gen fit cert
addsub fit ed25519 sign 2y
gen other cert
addsub other ed25519 sign 2y
gen nosigning cert
addsub nosigning cv25519 encr 2y
gen primaryonly cert,sign
gen revoked cert
addsub revoked ed25519 sign 2y
revoke revoked "$(subs revoked)"
gen expired cert "-60 days"
addsub expired ed25519 sign 7d "-60 days"
# the older subkey fit, the newer revoked: still a signing subkey that is fit
gen older cert
addsub older ed25519 sign 2y
sleep 1.1
addsub older ed25519 sign 2y
revoke older "$(subs older | tail -1)"

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
  # nothing of the key in what it says: no armor, and no line of the body of the one it was given
  if [ -f "$secret" ] && { grep -q 'BEGIN PGP\|PRIVATE KEY' <<< "$out" || grep -qFx -f <(sed -n '/^$/,/^=/{/^[^=]/p}' "$secret" | grep .) <<< "$out"; }; then
    echo "FAIL $label: the refusal prints key material: $out" >&2
    failures=$((failures + 1))
    return
  fi
  echo "ok   $label: refused: $1"
}

subkeys fit > "$work/fit.subkeys"
whole fit > "$work/fit.whole"
subkeys other > "$work/other.subkeys"
subkeys nosigning > "$work/nosigning.subkeys"
subkeys primaryonly > "$work/primaryonly.subkeys"
subkeys revoked > "$work/revoked.subkeys"
subkeys expired > "$work/expired.subkeys"
subkeys older > "$work/older.subkeys"
public fit > "$work/fit.public"
echo "not a key" > "$work/garbage"

fit=$(fpr fit)
run fit "$work/fit.subkeys" "$fit" ok
run lowercase "$work/fit.subkeys" "$(tr 'A-F' 'a-f' <<< "$fit")" ok
run older "$work/older.subkeys" "$(fpr older)" ok
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
