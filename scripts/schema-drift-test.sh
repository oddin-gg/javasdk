#!/usr/bin/env bash
#
# Tests scripts/schema-drift.sh and scripts/schema-drift-issues.sh against a small schema
# repository built here, one dated commit per rule, so every outcome - up to date, due, stale,
# needs a decision, broken pin, cannot compare - is seen once.
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

xsd() { # xsd <attributes of the alive type>
  cat >"$repo/schema/feed/alive.xsd" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
    <!-- the message -->
    <xs:element name="alive">
        <xs:annotation><xs:documentation>Sent every few seconds.</xs:documentation></xs:annotation>
        <xs:complexType>
$1
        </xs:complexType>
    </xs:element>
</xs:schema>
EOF
}

product='            <xs:attribute name="product" type="xs:int" use="required"/>'
subscribed='            <xs:attribute name="subscribed" type="xs:int"/>'
node='            <xs:attribute name="node" type="xs:int"/>'

xsd "$product"
echo '<alive product="1"/>' >"$repo/test/fixtures/feed/alive.xml"
c0=$(commit 0 "base")

xsd "$product
$subscribed"
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

echo '<alive product="1" subscribed="1"/>' >"$repo/test/fixtures/feed/alive.xml"
c5=$(commit 5 "only a fixture")

xsd "$subscribed"
c6=$(commit 10 "drop the product attribute")

xsd "$subscribed
$node"
c7=$(commit 11 "add another optional attribute")

xsd '            <xs:attribute name="subscribed" type="xs:long"/>
'"$node"
c8=$(commit 12 "widen subscribed")

# a large file whose only change is one removed attribute near the end: the check must read all
{
  echo '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:complexType name="big">'
  for i in $(seq 1 3000); do echo "  <xs:attribute name=\"a$i\" type=\"xs:int\"/>"; done
  echo '</xs:complexType></xs:schema>'
} >"$repo/schema/feed/big.xsd"
c9=$(commit 13 "a large type")
sed -i.bak '/name="a2999"/d' "$repo/schema/feed/big.xsd" && rm "$repo/schema/feed/big.xsd.bak"
c10=$(commit 14 "drop one attribute of the large type")

xsd '            <xs:attribute name="subscribed" type="xs:long"/>
'"$node"'
            <xs:attribute name="region" type="xs:string" use="required"/>'
c11=$(commit 15 "a required attribute on an existing type")

xsd '            <xs:attribute name="subscribed" type="xs:long"/>
'"$node"'
            <xs:attribute name="region" type="xs:string" use="required"/>
            <xs:attributeGroup ref="shared"/>'
c12=$(commit 16 "an attribute group on an existing type")

git -C "$repo" rm -q schema/feed/new.xsd
c13=$(commit 17 "delete a message")

echo "this is not XML <" >"$repo/schema/feed/broken.xsd"
c14=$(commit 18 "a file that is not XML")

# a file name that would be code if it were spliced into a program
odd='schema/feed/odd|name&e.xsd'
printf '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:complexType name="t">\n<xs:attribute name="a" type="xs:int"/>\n</xs:complexType></xs:schema>\n' >"$repo/$odd"
c15=$(commit 19 "a file with an odd name")
printf '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:complexType name="t">\n</xs:complexType></xs:schema>\n' >"$repo/$odd"
c16=$(commit 19 "drop its attribute")

# the rules the classifier states, one commit each, on a file of their own
more() { # more <root attributes> <extra top-level content> <sequence>
  cat >"$repo/schema/feed/more.xsd" <<EOF
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"$1>
$2
    <xs:simpleType name="kind"><xs:restriction base="xs:int">
        <xs:enumeration value="1"/>
        <xs:enumeration value="2"/>$ENUM
    </xs:restriction></xs:simpleType>
    <xs:element name="m"><xs:complexType><xs:sequence>
$3
    </xs:sequence></xs:complexType></xs:element>
</xs:schema>
EOF
}
a='        <xs:element name="a" type="xs:int"/>'
b='        <xs:element name="b" type="xs:int"/>'
c='        <xs:element name="c" type="xs:int"/>'
ENUM=
more "" "" "$a
$b"
c17=$(commit 21 "another message")
more ' elementFormDefault="qualified"' "" "$a
$b"
c18=$(commit 21 "change a setting of the schema itself")
more ' elementFormDefault="qualified"' '    <xs:include schemaLocation="alive.xsd"/>' "$a
$b"
c19=$(commit 21 "include another file")
ENUM='
        <xs:enumeration value="3"/>'
more ' elementFormDefault="qualified"' '    <xs:include schemaLocation="alive.xsd"/>' "$a
$b"
c20=$(commit 21 "a new enum value")
more ' elementFormDefault="qualified"' '    <xs:include schemaLocation="alive.xsd"/>' "$a
$b
$c"
c21=$(commit 21 "a new required element")
more ' elementFormDefault="qualified"' '    <xs:include schemaLocation="alive.xsd"/>' "$b
$a
$c"
c22=$(commit 21 "reorder the elements")

# a type that is new, in a file that exists, with a required attribute: additive
sed -i.bak 's|</xs:schema>|    <xs:complexType name="fresh"><xs:attribute name="x" type="xs:int" use="required"/></xs:complexType>\
</xs:schema>|' "$repo/schema/feed/more.xsd" && rm "$repo/schema/feed/more.xsd.bak"
c25=$(commit 21 "a new type with a required attribute in an existing file")
git -C "$repo" mv schema/feed/more.xsd schema/feed/moved.xsd
c26=$(commit 21 "move a file")

# a file name that sed would have run: its w flag writes a file
trap_name='schema/feed/odd|w marker|.xsd'
printf '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:complexType name="t">\n<xs:attribute name="a" type="xs:int"/>\n</xs:complexType></xs:schema>\n' >"$repo/$trap_name"
c23=$(commit 22 "a file whose name is a sed command")
printf '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:complexType name="t">\n</xs:complexType></xs:schema>\n' >"$repo/$trap_name"
c24=$(commit 22 "drop its attribute")

# a commit that exists but not on main
git -C "$repo" checkout -q -b side "$c1"
echo side >"$repo/side.txt"
side=$(commit 20 "on a side branch")
git -C "$repo" checkout -q main

failures=0
pass() { echo "ok   $1"; }
fail() {
  echo "FAIL $1"
  [ -z "${2:-}" ] || sed 's/^/    /' <<<"$2"
  failures=$((failures + 1))
}

# check <name> <expected exit> <expected output pattern> <PINS> <NOW> <schema head>
check() {
  local name=$1 want=$2 pattern=$3 pins=$4 now=$5 at=$6 out got=0
  git -C "$repo" update-ref refs/heads/main "$at"
  : >"$work/shapes"
  out=$(SCHEMA_REPO="file://$repo" PINS="$pins" NOW="$now" SHAPE_REPORT="$work/shapes" \
    "$here/schema-drift.sh" line 2>&1) || got=$?
  if [ "$got" = "$want" ] && grep -Eq -- "$pattern" <<<"$out"; then pass "$name"; else
    fail "$name: exit $got (expected $want), output:" "$out"; fi
}
days() { echo $((base + $1 * day)); }

check "at the head" 0 "ok   line: up to date" "line=$c1" "$(days 1)" "$c1"
check "an additive change, inside the window" 0 "refresh due by" "line=$c0" "$(days 3)" "$c1"
check "an additive change, exactly at the deadline" 0 "refresh due by" "line=$c0" "$(days 8)" "$c1"
check "an additive change, a second past the deadline" 1 "Refresh on line: scripts/refresh-schema.sh $c1" \
  "line=$c0" "$(($(days 8) + 1))" "$c1"
check "reworded documentation is additive" 0 "refresh due by" "line=$c1" "$(days 3)" "$c2"
check "a new type's required attribute is additive" 0 "refresh due by" "line=$c2" "$(days 4)" "$c3"
check "a commit outside the schema does not count" 0 "ok   line: up to date" "line=$c3" "$(days 30)" "$c4"
check "a commit to the fixtures only counts" 1 "Refresh on line: scripts/refresh-schema.sh $c5" \
  "line=$c4" "$(days 30)" "$c5"
check "a removed attribute needs a decision, and never fails" 0 "WARN line: needs a decision - $c6" \
  "line=$c5" "$(days 60)" "$c6"
check "additive changes before a shape change still age" 1 "refresh-schema.sh $c5" "line=$c0" "$(days 30)" "$c6"
check "additive changes after it wait for the decision" 0 "1 additive change\(s\) after $c6 wait" \
  "line=$c5" "$(days 60)" "$c7"
check "a changed type is a shape change" 0 "WARN line: needs a decision - $c8" "line=$c7" "$(days 60)" "$c8"
check "a change deep in a large diff is found" 0 "WARN line: needs a decision - $c10" "line=$c9" "$(days 60)" "$c10"
check "a required attribute on an existing type is a shape change" 0 \
  "required attribute added to an existing component" "line=$c10" "$(days 60)" "$c11"
check "an attribute group on an existing type is a shape change" 0 \
  "attribute group added to an existing component" "line=$c11" "$(days 60)" "$c12"
check "a deleted schema file is a shape change" 0 "WARN line: needs a decision - $c13" "line=$c12" "$(days 60)" "$c13"
check "a file that is not XML fails loudly" 1 "cannot compare schema/feed/broken.xsd" "line=$c13" "$(days 60)" "$c14"
check "a setting of the schema itself is a shape change" 0 "changed /:" "line=$c17" "$(days 60)" "$c18"
check "a new include is additive" 0 "refresh due by" "line=$c18" "$(days 21)" "$c19"
check "a new enum value is additive" 0 "refresh due by" "line=$c19" "$(days 21)" "$c20"
check "a new required element is additive" 0 "refresh due by" "line=$c20" "$(days 21)" "$c21"
check "reordered elements are additive" 0 "refresh due by" "line=$c21" "$(days 21)" "$c22"
check "a new type's required attribute in an existing file is additive" 0 "refresh due by" "line=$c22" "$(days 22)" "$c25"
check "a moved file is compared with itself" 0 "refresh due by" "line=$c25" "$(days 22)" "$c26"
mkdir "$work/cwd"
git -C "$repo" update-ref refs/heads/main "$c24"
out=$(cd "$work/cwd" && SCHEMA_REPO="file://$repo" PINS="line=$c23" NOW="$(days 60)" "$here/schema-drift.sh" line 2>&1) \
  && got=0 || got=$?
# the run has to have reached the file - reported it by its name - for "nothing written" to mean anything
if [ "$got" = 0 ] && grep -Fq "odd|w marker|.xsd: removed" <<<"$out" && [ -z "$(ls -A "$work/cwd")" ]; then
  pass "a file name that is a sed command runs nothing"
else
  fail "a file name that is a sed command: exit $got, left: $(ls -A "$work/cwd")" "$out"
fi
check "a file name is reported as data, never run" 0 "odd\|name&e\.xsd: removed" "line=$c15" "$(days 60)" "$c16"
# a line that cannot be judged fails alone: the other line is still checked
git -C "$repo" update-ref refs/heads/main "$c14"
out=$(SCHEMA_REPO="file://$repo" PINS="broken=$c13 fine=$c14" NOW="$(days 60)" "$here/schema-drift.sh" broken fine 2>&1) \
  && got=0 || got=$?
if [ "$got" = 1 ] && grep -q "FAIL broken: cannot compare" <<<"$out" && grep -q "ok   fine: up to date" <<<"$out"; then
  pass "a line that cannot be judged does not stop the others"
else
  fail "a line that cannot be judged: exit $got" "$out"
fi
check "a pin that does not exist" 1 "is not on the schema's main" \
  "line=0123456789012345678901234567890123456789" "$(days 1)" "$c1"
check "a pin that exists but is not on main" 1 "its pin $side is not on the schema's main" "line=$side" "$(days 21)" "$c4"

check "every shape change is reported" 0 "needs a decision - $c8" "line=$c5" "$(days 60)" "$c8"
if [ "$(cut -f2 "$work/shapes" | tr '\n' ' ')" = "$c6 $c8 " ]; then pass "the shape report names every shape change"; else
  fail "the shape report names $(cut -f2 "$work/shapes" | tr '\n' ' '), expected $c6 $c8"; fi

# pins read the way the workflow reads them: from each branch's SOURCE, two branches in one run
git -C "$repo" update-ref refs/heads/main "$c1"
sdk=$work/sdk
git init -q -b fresh "$sdk"
pin_branch() { # pin_branch <branch> <commit or empty>
  git -C "$sdk" checkout -q --orphan "$1"
  git -C "$sdk" rm -rq --cached . 2>/dev/null || true
  rm -rf "$sdk/vendor"
  if [ -n "$2" ]; then
    mkdir -p "$sdk/vendor/oddsfeedschema"
    printf '# vendored\nrepository x\ncommit %s\nrelease v0\n' "$2" >"$sdk/vendor/oddsfeedschema/SOURCE"
    git -C "$sdk" add vendor
  fi
  git -C "$sdk" -c user.email=t@e.invalid -c user.name=t commit -q --allow-empty -m "$1"
}
pin_branch fresh "$c1"
pin_branch stale "$c0"
pin_branch unpinned ""
out=$(cd "$sdk" && SCHEMA_REPO="file://$repo" SDK_REMOTE="$sdk" NOW="$(days 30)" \
  "$here/schema-drift.sh" fresh stale unpinned missing 2>&1) && got=0 || got=$?
for expected in "ok   fresh: up to date" "FAIL stale: additive" "FAIL unpinned: no pin" "FAIL missing: cannot fetch it"; do
  if grep -q -- "$expected" <<<"$out"; then pass "branches read from SOURCE: $expected"; else
    fail "branches read from SOURCE: no \"$expected\"" "$out"; fi
done
if [ "$got" = 1 ]; then pass "a run with any failing branch fails"; else fail "the run exited $got, expected 1"; fi

# the issue script, against a gh that records what it is asked
bin=$work/bin
mkdir -p "$bin"
cat >"$bin/gh" <<'EOF'
#!/usr/bin/env bash
echo "gh $*" >>"$GH_LOG"
if [ "$1 $2" = "issue list" ]; then
  [ -z "${GH_FAIL:-}" ] || exit 1
  printf '%s\n' "$GH_TITLES"
fi
if [ "$1 $2" = "issue create" ]; then
  while [ "$#" -gt 0 ]; do
    if [ "$1" = "--title" ]; then echo "$2" >>"$GH_CREATED"; fi
    shift
  done
fi
EOF
chmod +x "$bin/gh"
printf 'release/0.x\t%s\tone\nnext\t%s\ttwo\n' "$c6" "$c8" >"$work/report"
asked="Schema drift: release/0.x needs a decision on oddsfeedschema ${c6:0:7}"
issues() { # issues <titles gh finds> [fail]; sets out, got, creates
  : >"$work/gh.log"
  : >"$work/created"
  out=$(PATH="$bin:$PATH" GH_LOG="$work/gh.log" GH_CREATED="$work/created" GH_TITLES="$1" GH_FAIL="${2:-}" REPO=owner/repo \
    "$here/schema-drift-issues.sh" "$work/report" 2>&1) && got=0 || got=$?
  creates=$(grep -c "issue create" "$work/gh.log" || true)
}
issues "something else"
if [ "$got" = 0 ] && [ "$creates" = 2 ]; then pass "issues: one per shape change not asked yet"; else
  fail "issues: exit $got, $creates created, expected 0 and 2" "$out"; fi
if [ "$(grep -c 'author:app/github-actions' "$work/gh.log")" = 2 ]; then pass "issues: only the workflow's own issues count"; else
  fail "issues: the lookup does not filter by the workflow's author" "$(cat "$work/gh.log")"; fi
if [ "$(grep -c -- '--state all' "$work/gh.log")" = 2 ]; then pass "issues: a closed issue counts as asked"; else
  fail "issues: the lookup does not include closed issues" "$(cat "$work/gh.log")"; fi
issues "$(cat "$work/created")"
if [ "$got" = 0 ] && [ "$creates" = 0 ]; then pass "issues: the next run finds the issues this one opened"; else
  fail "issues: the next run asked again: $creates created" "$out"; fi
issues "$asked"
if [ "$got" = 0 ] && [ "$creates" = 1 ] && grep -q "already asked: $asked" <<<"$out"; then pass "issues: a change asked about before is not asked again"; else
  fail "issues: exit $got, $creates created, expected 0 and 1" "$out"; fi
issues "" fail
if [ "$got" != 0 ] && [ "$creates" = 0 ]; then pass "issues: a failed lookup stops before asking"; else
  fail "issues: exit $got, $creates created after a failed lookup" "$out"; fi
: >"$work/report"
issues ""
if [ "$got" = 0 ] && [ ! -s "$work/gh.log" ]; then pass "issues: nothing to ask, nothing called"; else
  fail "issues: an empty report still called gh" "$(cat "$work/gh.log")"; fi

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed" >&2
  exit 1
fi
echo "all checks passed"
