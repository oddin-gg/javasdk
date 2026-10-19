#!/usr/bin/env bash
#
# Refuses an upload whose MAVEN_GPG_KEY is not the key that was checked. The check job
# (scripts/release-key-check.sh) looks at the public key RELEASE_KEY_FINGERPRINT names; what signs
# is the secret of the maven-central environment, which the approved publish job alone can read,
# and nothing else ties the two. scripts/release-signing-key-check-test.sh runs this against keys
# made for the test on every push.
#
#   scripts/release-signing-key-check.sh
#
# MAVEN_GPG_KEY is imported into a throwaway home, and must be what RELEASING.md says it is: a
# subkey export, its primary a stub (gpg --export-secret-subkeys), so one that carries the
# primary's secret is refused; the key of RELEASE_KEY_FINGERPRINT, not another; and it must hold
# the secret of a signing subkey that is neither revoked nor expired. The passphrase is not
# needed to list a key and is not read. Nothing of the key is ever printed.
#
# That is the secret alone. A secret exported before its subkey was revoked, or before the one it
# holds was published, still looks fine by itself, so it is then held against the keyservers as
# they are now - this runs in the publish job, after the approval, which can be days after the
# check job: scripts/release-key-check.sh asks both for the subkey gpg would sign with, the newest
# usable one of the secret, and refuses unless both have it, the same, not revoked and not within
# 30 days of its expiry. Needs curl too, and the keyservers' URLs, as release-key-check.sh takes
# them (KEYS_OPENPGP_URL, KEYSERVER_UBUNTU_URL, KEYSERVER_TIMEOUT), for the test.
# Needs gpg, python3 and curl.
set -euo pipefail

fail() {
  echo "signing key: $*" >&2
  exit 1
}

for tool in gpg python3 curl; do
  command -v "$tool" > /dev/null || fail "needs $tool"
done

fingerprint=${RELEASE_KEY_FINGERPRINT:-}
[[ "$fingerprint" =~ ^[0-9A-Fa-f]{40}$ ]] \
  || fail "the repository variable RELEASE_KEY_FINGERPRINT is not set to the 40 hex digits of the release key's primary key (no spaces); set it as RELEASING.md says"
fingerprint=${fingerprint^^}
[ -n "${MAVEN_GPG_KEY:-}" ] || fail "the secret MAVEN_GPG_KEY is empty"

work=$(mktemp -d)
chmod 700 "$work"
export GNUPGHOME=$work
trap 'gpgconf --kill gpg-agent 2> /dev/null || true; rm -rf "$work"' EXIT

# what gpg says about the import is a key id and a user ID at most, never the key
printf '%s\n' "$MAVEN_GPG_KEY" | gpg --batch --no-tty --import > "$work/import.log" 2>&1 \
  || fail "the secret MAVEN_GPG_KEY is not a key gpg can import: $(tr '\n' ' ' < "$work/import.log")"
gpg --batch --no-tty --list-secret-keys --with-colons > "$work/colons"

signs_with=$(python3 -I - "$fingerprint" "$work/colons" <<'PY'
import sys, time
wanted, path = sys.argv[1:]
now = time.time()
fail = lambda message: sys.exit("signing key: " + message)

keys = []
target = None
for line in open(path):
    f = line.rstrip("\n").split(":") + [""] * 15
    if f[0] == "sec":
        target = {"validity": f[1], "expires": int(f[6] or 0), "stub": f[14] == "#", "subs": []}
        keys.append(target)
    elif f[0] == "ssb" and keys:
        target = {"validity": f[1], "expires": int(f[6] or 0), "created": int(f[5] or 0), "stub": f[14] in ("#", ">"), "caps": f[11]}
        keys[-1]["subs"].append(target)
    elif f[0] == "fpr" and target is not None and "fpr" not in target:
        target["fpr"] = f[9]

if not keys:
    fail("the secret MAVEN_GPG_KEY holds no secret key")
if len(keys) != 1:
    fail("the secret MAVEN_GPG_KEY holds %d secret keys, not one: %s" % (len(keys), ", ".join(k["fpr"] for k in keys)))
key = keys[0]
if key["fpr"] != wanted:
    fail("the secret MAVEN_GPG_KEY is the key %s, not %s of RELEASE_KEY_FINGERPRINT" % (key["fpr"], wanted))
if not key["stub"]:
    fail("the secret MAVEN_GPG_KEY carries the secret of the primary key %s: it must be the export of the subkeys alone, "
         "gpg --export-secret-subkeys, as RELEASING.md says; replace the secret and treat the primary as leaked" % wanted)
usable = [s for s in key["subs"]
          if "s" in s["caps"] and not s["stub"] and s["validity"] not in ("r", "e") and not (s["expires"] and s["expires"] <= now)]
if not usable:
    fail("the secret MAVEN_GPG_KEY has no secret signing subkey that is neither revoked nor expired; "
         "export the subkeys again as RELEASING.md says")
# gpg signs with the newest of them
print(max(usable, key=lambda s: s["created"])["fpr"])
PY
) || exit 1
echo "signing key: MAVEN_GPG_KEY is a subkey export of $fingerprint, signing with $signs_with" >&2

# and that subkey, on both keyservers as they are now; the secret is not handed on
env -u MAVEN_GPG_KEY RELEASE_KEY_FINGERPRINT="$fingerprint" RELEASE_SIGNING_SUBKEY="$signs_with" \
  "$(dirname "$0")/release-key-check.sh"
