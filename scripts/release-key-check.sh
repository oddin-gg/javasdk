#!/usr/bin/env bash
#
# Refuses a release whose signing key is not fit to sign: the key Central will check the
# signatures against must be on both keyservers it reads, and must have a signing subkey that is
# not revoked and not about to expire. A subkey that ran out in CI is found by the upload, after
# a reviewer approved it; this finds it before anything is built. scripts/release-key-check-test.sh
# runs it against stub keyservers on every push.
#
#   scripts/release-key-check.sh
#
# RELEASE_KEY_FINGERPRINT, the repository variable of that name, is the 40 hex digits of the
# release key's primary key, which never goes into GitHub (RELEASING.md): the public key is
# fetched by it, so this needs no secret. Run by the check job, which has no key. Needs curl,
# gpg and python3.
#
# Both keyservers are asked, because Central reads both: after a renewal, a server that still
# holds the old expiry makes Central refuse the signature. The primary's fingerprint must be the
# variable's, and the key must have a signing subkey - the primary alone signing does not count,
# since the layout keeps the primary out of CI. The subkey gpg would sign with, the newest one
# not revoked or expired, must have no expiry or one at least 30 days away.
#
# keys.openpgp.org serves only the user IDs whose email its owner verified, and none otherwise;
# gpg lists such a key, but shows no key flags for its primary, marks every key of it invalid and
# says nothing of a revoked subkey. So the primary's flags are not read, and revocations are read
# from their own signatures, which are checked (--with-sig-check).
#
# KEYS_OPENPGP_URL and KEYSERVER_UBUNTU_URL replace the keyservers' base URLs and KEYSERVER_TIMEOUT
# the time limit in seconds of each request, for the test.
set -euo pipefail

openpgp=${KEYS_OPENPGP_URL:-https://keys.openpgp.org}
ubuntu=${KEYSERVER_UBUNTU_URL:-https://keyserver.ubuntu.com}
timeout=${KEYSERVER_TIMEOUT:-60}

fail() {
  echo "release key: $*" >&2
  exit 1
}

for tool in curl gpg python3; do
  command -v "$tool" > /dev/null || fail "needs $tool"
done

fingerprint=${RELEASE_KEY_FINGERPRINT:-}
[[ "$fingerprint" =~ ^[0-9A-Fa-f]{40}$ ]] \
  || fail "the repository variable RELEASE_KEY_FINGERPRINT is not set to the 40 hex digits of the release key's primary key (no spaces); set it as RELEASING.md says"
fingerprint=${fingerprint^^}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"

# the key as one server serves it, listed by gpg in a throwaway home; the refusals, one per line
check_server() {
  local name=$1 url=$2 status
  status=$(curl -sS -o "$work/$name.key" -w '%{http_code}' --connect-timeout "$timeout" --max-time "$timeout" "$url" 2> "$work/$name.err") \
    || { echo "$name: could not ask for the key ($(tr '\n' ' ' < "$work/$name.err")), at $url"; return; }
  case "$status" in
    200) ;;
    404) echo "$name does not have the key $fingerprint: upload it, as RELEASING.md says"; return ;;
    *) echo "$name: could not ask for the key ($status from $url)"; return ;;
  esac
  if ! GNUPGHOME=$work gpg --batch --no-tty --show-keys --with-colons --with-sig-check "$work/$name.key" \
    > "$work/$name.colons" 2> "$work/$name.err"; then
    echo "$name answered with something gpg cannot read as a public key: $(tr '\n' ' ' < "$work/$name.err")"
    return
  fi
  python3 -I - "$name" "$fingerprint" "$work/$name.colons" <<'EOF' || echo "$name: could not read the key gpg listed"
import sys, time
name, wanted, path = sys.argv[1:]
now = time.time()
horizon = now + 30 * 86400

def date(seconds):
    return time.strftime("%Y-%m-%d", time.gmtime(seconds))

def number(field):
    return int(field) if field else 0

keys = []
target = None
for line in open(path):
    f = line.rstrip("\n").split(":") + [""] * 12
    kind = f[0]
    if kind == "pub":
        target = {"primary": True, "validity": f[1], "created": number(f[5]), "expires": number(f[6]),
                  "caps": f[11], "revoked": False, "subs": []}
        keys.append(target)
    elif kind == "sub" and keys:
        target = {"primary": False, "validity": f[1], "created": number(f[5]), "expires": number(f[6]),
                  "caps": f[11], "revoked": False}
        keys[-1]["subs"].append(target)
    elif kind == "fpr" and target is not None and "fpr" not in target:
        target["fpr"] = f[9]
    elif kind == "rev" and f[1] == "!" and target is not None:
        # a checked revocation: 0x20 of the key, 0x28 of a subkey; those of a user ID do not count
        if f[10].startswith("20x" if target["primary"] else "28x"):
            target["revoked"] = True

problems = []
mine = [k for k in keys if k.get("fpr") == wanted]
if not mine:
    problems.append("%s serves %s, not the key %s" % (name, ", ".join(k.get("fpr", "?") for k in keys) or "no key", wanted))
else:
    key = mine[0]
    if key["revoked"] or key["validity"] == "r":
        problems.append("%s: the key %s is revoked: make a new key as RELEASING.md says" % (name, wanted))
    elif key["expires"] and key["expires"] <= now:
        problems.append("%s: the key %s expired on %s: the layout gives the primary no expiry, see RELEASING.md" % (name, wanted, date(key["expires"])))
    elif key["expires"] and key["expires"] < horizon:
        problems.append("%s: the key %s expires on %s: the layout gives the primary no expiry, see RELEASING.md" % (name, wanted, date(key["expires"])))
    else:
        signing = [s for s in key["subs"] if "s" in s["caps"]]
        if not signing:
            problems.append("%s: the key %s has no signing subkey (the primary signing does not count: it stays out of CI); add one as RELEASING.md says" % (name, wanted))
        else:
            usable, gone = [], []
            for s in signing:
                if s["revoked"] or s["validity"] == "r":
                    gone.append("%s: the signing subkey %s is revoked" % (name, s["fpr"]))
                elif s["expires"] and s["expires"] <= now:
                    gone.append("%s: the signing subkey %s expired on %s" % (name, s["fpr"], date(s["expires"])))
                else:
                    usable.append(s)
            if not usable:
                problems += gone
                problems.append("%s: no signing subkey of the key %s is left: renew it or add one as RELEASING.md says" % (name, wanted))
            else:
                # gpg signs with the newest subkey that is neither revoked nor expired
                best = max(usable, key=lambda s: s["created"])
                if best["expires"] and best["expires"] < horizon:
                    days = int((best["expires"] - now) // 86400)
                    problems.append("%s: the signing subkey %s expires on %s, in %d days, less than 30: renew it as RELEASING.md says"
                                    % (name, best["fpr"], date(best["expires"]), days))
for p in problems:
    print(p)
EOF
}

refusals=$(
  check_server keys.openpgp.org "$openpgp/vks/v1/by-fingerprint/$fingerprint"
  check_server keyserver.ubuntu.com "$ubuntu/pks/lookup?op=get&options=mr&search=0x$fingerprint"
)
if [ -n "$refusals" ]; then
  echo "release key: the signing key $fingerprint is not fit to sign a release:" >&2
  sed 's/^/  /' <<< "$refusals" >&2
  exit 1
fi
echo "release key: $fingerprint is on keys.openpgp.org and keyserver.ubuntu.com, with a signing subkey fit to sign" >&2
