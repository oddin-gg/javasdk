#!/usr/bin/env python3
"""Whether one version of an XSD changes the shape of the other.

    scripts/schema_shape.py OLD NEW      (either may be /dev/null for a file added or deleted)

Exit 0: additive or no change at all. Exit 1: a shape change, with what changed on stdout.
Exit 2: the files could not be compared, for any reason.

Both files are read as trees, so comments, documentation and formatting never count. Every schema
component - element, attribute, type, group, enumeration value - is keyed by its path of names from
the root. A key the old version had and the new one lacks, or one whose settings changed (a type,
use, occurrence, default), is a shape change, and so are changed settings of the schema itself. So
is a required attribute added to a component that already existed - old payloads do not carry it,
and it changes how a generated class holds it - and a new attribute-group reference on one, which
may bring required attributes from another file. Any other new key is additive: a new optional
attribute, a new element (required or not, it adds a field and changes none that exists), a new
type, a new enum value, a new include.
"""
import sys
import xml.etree.ElementTree as ET

XS = "{http://www.w3.org/2001/XMLSchema}"
IGNORED = {"annotation", "documentation", "appinfo"}
# what names a component; includes and imports are named by what they bring in
NAMING = ("name", "ref", "value", "schemaLocation", "namespace")


def components(path):
    """Every component of the schema: key -> its settings."""
    if path == "/dev/null":
        return {}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"{path}: not XML: {e}")
        sys.exit(2)
    # the schema element's own settings, such as elementFormDefault
    found = {"/": dict(root.attrib)}

    def walk(node, prefix):
        counts = {}
        for child in node:
            if not isinstance(child.tag, str):
                continue  # a comment or processing instruction
            tag = child.tag.replace(XS, "")
            if tag in IGNORED:
                continue
            label = next((f"{tag}[{a}={child.get(a)}]" for a in NAMING if child.get(a) is not None), None)
            if label is None:
                # an unnamed container such as a sequence: by its position among its kind
                counts[tag] = counts.get(tag, 0) + 1
                label = f"{tag}#{counts[tag]}"
            key = f"{prefix}/{label}"
            found[key] = {k: v for k, v in child.attrib.items() if k not in NAMING}
            walk(child, key)

    walk(root, "")
    return found


def main():
    old, new = components(sys.argv[1]), components(sys.argv[2])
    changes = []
    for key in sorted(old):
        if key not in new:
            changes.append(f"removed {key}")
        elif old[key] != new[key]:
            changes.append(f"changed {key}: {old[key]} -> {new[key]}")
    for key in sorted(set(new) - set(old)):
        parent, label = key.rsplit("/", 1)
        if parent not in old or parent == "":
            continue  # part of a component that is new as a whole
        if label.startswith("attribute[") and new[key].get("use") == "required":
            changes.append(f"required attribute added to an existing component: {key}")
        elif label.startswith("attributeGroup[ref="):
            changes.append(f"attribute group added to an existing component, maybe with required attributes: {key}")
    for change in changes:
        print(change)
    sys.exit(1 if changes else 0)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:  # anything unexpected is "cannot compare", never "a shape change"
        print(f"cannot compare: {e!r}")
        sys.exit(2)
