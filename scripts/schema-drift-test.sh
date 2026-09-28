#!/usr/bin/env bash
#
# Tests scripts/schema-drift.sh against a small schema repository built here, one dated commit per
# rule, so every outcome - up to date, due, stale, needs a decision, broken pin - is seen once.
#
#   scripts/schema-drift-test.sh
#
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
repo=$work/schema
day=86400
base=1790000000 # a fixed start, so ages do not depend on today

git init -q -b main "$repo"
git -C "$repo" config user.email test@example.invalid
git -C "$repo" config user.name test
mkdir -p "$repo/schema/feed" "$repo/test/fixtures/feed"

commit() { # commit <days after base> <message>
  git -C "$repo" add -A
  GIT_COMMITTER_DATE="@$((base + $1 * day)) +0000" GIT_AUTHOR_DATE="@$((base + $1 * day)) +0000" \
    git -C "$repo" commit -q -m "$2"
  git -C "$repo" rev-parse HEAD
}

xsd() { # xsd <file> <attributes of the type>
  cat >"$repo/schema/feed/$1" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
    <!-- the message -->
    <xs:element name="alive">
        <xs:annotation><xs:documentation>Sent every few seconds.</xs:documentation></xs:annotation>
        <xs:complexType>
$2
        </xs:complexType>
    </xs:element>
</xs:schema>
EOF
}

a_product='            <xs:attribute name="product" type="xs:int" use="required"/>'
a_subscribed='            <xs:attribute name="subscribed" type="xs:int"/>'
a_node='            <xs:attribute name="node" type="xs:int"/>'

xsd alive.xsd "$a_product"
echo '<alive product="1"/>' >"$repo/test/fixtures/feed/alive.xml"
c0=$(commit 0 "base")

xsd alive.xsd "$a_product
$a_subscribed"
c1=$(commit 1 "add an optional attribute")

sed -i.bak 's/Sent every few seconds./Sent every second or so./; s/<!-- the message -->/<!-- the alive message -->/' \
  "$repo/schema/feed/alive.xsd" && rm "$repo/schema/feed/alive.xsd.bak"
c2=$(commit 2 "reword the documentation")

cat >"$repo/schema/feed/new.xsd" <<'EOF'
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
    <xs:element name="brand_new"><xs:complexType>
        <xs:attribute name="id" type="xs:int" use="required"/>
    </xs:complexType></xs:element>
</xs:schema>
EOF
c3=$(commit 3 "a new message with a required attribute")

echo "readme" >"$repo/README.md"
c4=$(commit 4 "only the readme")

xsd alive.xsd "$a_subscribed"
c5=$(commit 10 "drop the product attribute")

xsd alive.xsd "$a_subscribed
$a_node"
c6=$(commit 11 "add another optional attribute")

xsd alive.xsd '            <xs:attribute name="subscribed" type="xs:long"/>
'"$a_node"
c7=$(commit 12 "widen subscribed")

# a large file whose only change is one removed attribute near the end: the check must read all
{
  echo '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:complexType name="big">'
  for i in $(seq 1 3000); do echo "  <xs:attribute name=\"a$i\" type=\"xs:int\"/>"; done
  echo '</xs:complexType></xs:schema>'
} >"$repo/schema/feed/big.xsd"
c8=$(commit 13 "a large type")
sed -i.bak '/name="a2999"/d' "$repo/schema/feed/big.xsd" && rm "$repo/schema/feed/big.xsd.bak"
c9=$(commit 14 "drop one attribute of the large type")

failures=0
# check <name> <expected exit> <expected output pattern> -- <PINS> <NOW in days> [head]
check() {
  local name=$1 want=$2 pattern=$3 pins=$4 days=$5 at=${6:-main}
  git -C "$repo" update-ref refs/heads/main "$at"
  local out got=0
  : >"$work/shapes"
  out=$(SCHEMA_REPO="file://$repo" PINS="$pins" NOW=$((base + days * day)) SHAPE_REPORT="$work/shapes" \
    "$here/schema-drift.sh" line 2>&1) || got=$?
  if [ "$got" != "$want" ] || ! grep -Eq -- "$pattern" <<<"$out"; then
    echo "FAIL $name: exit $got (expected $want), output:"
    sed 's/^/    /' <<<"$out"
    failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

check "at the head" 0 "ok   line: up to date" "line=$c1" 1 "$c1"
check "an additive change, inside the window" 0 "refresh due by" "line=$c0" 3 "$c1"
check "an additive change, past the window" 1 "Refresh on line: scripts/refresh-schema.sh $c1" "line=$c0" 9 "$c1"
check "reworded documentation is additive" 0 "refresh due by" "line=$c1" 3 "$c2"
check "a new type's required attribute is additive" 0 "refresh due by" "line=$c2" 4 "$c3"
check "a commit outside the schema does not count" 0 "ok   line: up to date" "line=$c3" 30 "$c4"
check "a removed attribute needs a decision, and never fails" 0 "WARN line: needs a decision - $c5" "line=$c4" 60 "$c5"
check "additive changes before a shape change still age" 1 "refresh-schema.sh $c4" "line=$c0" 30 "$c5"
check "additive changes after it wait for the decision" 0 "1 additive change\(s\) after $c5 wait" "line=$c4" 60 "$c6"
check "a changed type is a shape change too" 0 "WARN line: needs a decision - $c7" "line=$c4" 60 "$c7"
check "a change deep in a large diff is found" 0 "WARN line: needs a decision - $c9" "line=$c8" 60 "$c9"
check "a pin that is not on main" 1 "is not on the schema's main" "line=0123456789012345678901234567890123456789" 1 "$c1"

# every shape change is reported, not only the first
check "both shape changes are reported" 0 "needs a decision - $c7" "line=$c4" 60 "$c7"
if [ "$(cut -f2 "$work/shapes" | tr '\n' ' ')" != "$c5 $c7 " ]; then
  echo "FAIL the shape report names $(cut -f2 "$work/shapes" | tr '\n' ' '), expected $c5 $c7"
  failures=$((failures + 1))
else
  echo "ok   the shape report names every shape change"
fi

# a branch without a pin, read the way the workflow reads it
sdk=$work/sdk
git init -q -b line "$sdk"
git -C "$sdk" -c user.email=t@e.invalid -c user.name=t commit -q --allow-empty -m empty
out=$(cd "$sdk" && SCHEMA_REPO="file://$repo" SDK_REMOTE="$sdk" "$here/schema-drift.sh" line 2>&1) && got=0 || got=$?
if [ "$got" = 1 ] && grep -q "FAIL line: no pin" <<<"$out"; then echo "ok   a branch without a pin"; else
  echo "FAIL a branch without a pin: exit $got"; sed 's/^/    /' <<<"$out"; failures=$((failures + 1)); fi
out=$(cd "$sdk" && SCHEMA_REPO="file://$repo" SDK_REMOTE="$sdk" "$here/schema-drift.sh" missing 2>&1) && got=0 || got=$?
if [ "$got" = 1 ] && grep -q "FAIL missing: cannot fetch it" <<<"$out"; then echo "ok   a branch that cannot be fetched"; else
  echo "FAIL a branch that cannot be fetched: exit $got"; sed 's/^/    /' <<<"$out"; failures=$((failures + 1)); fi

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed" >&2
  exit 1
fi
echo "all checks passed"
