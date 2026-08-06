#!/usr/bin/env python3
"""
Generate mcdrift's deprecation ruleset from the Spigot javadoc.

    python tools/generate_ruleset.py --out src/main/resources/ruleset.json

The javadoc "deprecated-list" page is the authoritative machine-readable source for
what Bukkit/Spigot has deprecated. It gives us, per member:

  - the owning class and member name, from the anchor href
  - the parameter types, from the anchor fragment
  - a replacement hint, from the description cell
  - whether it is *terminally* deprecated (i.e. scheduled for removal)

Severity is assigned from that last flag rather than guessed: terminally deprecated
members are the ones that will actually stop existing, so they are the only ones
worth an ERROR. Everything else is advice.

Run this when a new Minecraft version ships. The output is versioned separately from
the mcdrift binary so a ruleset refresh does not need a release.
"""

import argparse
import html
import json
import re
import sys
import urllib.parse
import urllib.request

DEFAULT_SOURCE = "https://hub.spigotmc.org/javadocs/spigot/deprecated-list.html"
DEFAULT_PAPER_SOURCE = "https://jd.papermc.io/paper/26.1.2/deprecated-list.html"

# Primitive and array types map straight onto JVM descriptors.
PRIMITIVES = {
    "byte": "B",
    "char": "C",
    "double": "D",
    "float": "F",
    "int": "I",
    "long": "J",
    "short": "S",
    "boolean": "Z",
    "void": "V",
}

# Server-API packages a plugin can legitimately call. Anything outside these — the
# javadoc also lists a few third-party and internal namespaces — is dropped, so the
# ruleset stays about API a plugin author is actually using.
API_PREFIXES = (
    "org/bukkit",        # Bukkit/Spigot
    "io/papermc",        # Paper
    "com/destroystokyo", # Paper's older namespace
    "org/spigotmc",      # Spigot extensions
)


def to_descriptor(java_type: str) -> str:
    """Convert a javadoc type such as `java.util.Map` or `int[]` to a JVM descriptor."""
    t = java_type.strip()
    if not t:
        raise ValueError("empty type")

    arrays = 0
    while t.endswith("[]"):
        arrays += 1
        t = t[:-2].strip()
    # Varargs in an anchor appear as `Type...`
    if t.endswith("..."):
        arrays += 1
        t = t[:-3].strip()

    if t in PRIMITIVES:
        desc = PRIMITIVES[t]
    else:
        desc = "L" + to_internal_name(t) + ";"
    return "[" * arrays + desc


def to_internal_name(qualified: str) -> str:
    """
    `org.bukkit.event.entity.EntityDamageEvent.DamageCause`
      -> `org/bukkit/event/entity/EntityDamageEvent$DamageCause`

    Nested classes are the one genuinely tricky case: the javadoc writes them with a
    dot, but the JVM uses `$`. They are distinguished by the segment starting with an
    uppercase letter, which is a convention Bukkit follows without exception.
    """
    parts = qualified.split(".")
    out = []
    seen_class = False
    for part in parts:
        if seen_class:
            out.append("$" + part)
        elif part[:1].isupper():
            seen_class = True
            out.append("/" + part if out else part)
        else:
            out.append("/" + part if out else part)
    joined = "".join(out)
    # The leading separator only appears if the first segment was already a class.
    return joined.lstrip("/")


def parse_entries(page: str):
    """Yield one dict per deprecated member found in the page."""
    # Section boundaries come from the real `id=` anchors, not from the caption text:
    # the phrase "Terminally Deprecated" also appears in the table of contents near the
    # top of the page, and matching that put every entry outside the terminal range.
    terminal_start, terminal_end = section_bounds(page, "for-removal")

    # The description cell is a nested <div class="block">, so the row regex has to
    # stop at the end of the enclosing col-last div rather than the first </div>.
    # Display text may contain <wbr> hints, so it cannot be captured with [^<]*.
    pattern = re.compile(
        r'<div class="col-summary-item-name[^"]*">\s*<a href="([^"]+)"[^>]*>(.*?)</a>\s*</div>\s*'
        r'<div class="col-last[^"]*">(.*?)(?=<div class="col-summary-item-name|<div class="caption"|</section>)',
        re.S,
    )

    for match in pattern.finditer(page):
        href, display, description = match.group(1), match.group(2), match.group(3)
        if "#" not in href:
            continue  # a class/interface deprecation, not a member

        is_terminal = terminal_start <= match.start() < terminal_end

        entry = build_entry(href, strip_tags(display), description, is_terminal)
        if entry:
            yield entry


# The order of summary blocks on the page. Each is a plain <div id="..."> rather than
# a <section>, so a block ends where the next one begins.
SECTION_IDS = [
    "for-removal", "interface", "class", "enum-class", "annotation-interface",
    "field", "method", "constructor", "enum-constant",
]


def section_bounds(page: str, section_id: str):
    """
    Byte range of a javadoc summary block, by its id attribute.

    The block is delimited by the *next* block's id, not by a closing tag: javadoc
    emits these as nested <div>s with no unique terminator. Falling back to the end of
    the page when no terminator is found would silently mark every entry as belonging
    to this section, so an unknown id returns an empty range instead.
    """
    start = page.find(f'id="{section_id}"')
    if start < 0:
        return (-1, -1)

    end = len(page)
    for other in SECTION_IDS:
        if other == section_id:
            continue
        pos = page.find(f'id="{other}"', start + 1)
        if pos > start:
            end = min(end, pos)
    return (start, end)


def strip_tags(fragment: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", "", fragment)).strip()


def build_entry(href: str, display: str, description_html: str, is_terminal: bool):
    owner_path, fragment = href.split("#", 1)
    owner = owner_path[:-5] if owner_path.endswith(".html") else owner_path
    owner = owner.strip("/")
    if not owner.startswith(API_PREFIXES):
        return None

    fragment = urllib.parse.unquote(fragment)
    match = re.match(r"^([^(]+)\((.*)\)$", fragment)
    if not match:
        return None  # a field, not a method

    name, raw_params = match.group(1), match.group(2)
    if name == "<init>":
        # Constructors are a real share of the terminal deprecations (AttributeModifier
        # alone has four), and they appear in bytecode as INVOKESPECIAL <init>, so the
        # scanner matches them with the same owner/name/descriptor lookup.
        pass

    try:
        params = split_params(raw_params)
        descriptor = "(" + "".join(to_descriptor(p) for p in params) + ")"
    except ValueError:
        return None

    # A constructor's descriptor is fully determined — it always returns void — so it
    # can be written exactly rather than left as a prefix to match.
    if name == "<init>":
        descriptor += "V"

    replacement = clean_description(description_html)

    return {
        "owner": owner,
        "name": name,
        # The javadoc anchor gives parameter types but not the return type, so the
        # descriptor is a prefix. mcdrift matches on it with startsWith, which is
        # exact for overload selection: two overloads cannot share a parameter list.
        "descriptor": descriptor,
        "severity": "ERROR" if is_terminal else severity_for(replacement),
        "terminal": is_terminal,
        "replacement": replacement,
    }


def split_params(raw: str):
    """Split a parameter list, respecting generics such as `java.util.Map<K,V>`."""
    if not raw.strip():
        return []
    parts, depth, current = [], 0, ""
    for ch in raw:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    parts.append(current)
    # Erase generics: the JVM descriptor has no type arguments.
    return [re.sub(r"<.*>", "", p).strip() for p in parts if p.strip()]


def clean_description(fragment: str) -> str:
    """Turn the javadoc description cell into a one-line replacement hint."""
    text = re.sub(r"<[^>]+>", "", fragment)
    text = html.unescape(text)
    text = " ".join(text.split())
    text = text.strip().rstrip(".")
    return text


def severity_for(replacement: str) -> str:
    """
    Non-terminal deprecations: WARN when the javadoc names a concrete replacement,
    INFO when it is only advisory.

    A deprecation that tells you exactly what to call instead is actionable, so it is
    worth a warning. One that says "magic value" or offers no alternative is noise in
    a build log and belongs at INFO.
    """
    if not replacement:
        return "INFO"
    lowered = replacement.lower()
    if lowered.startswith("magic value") or lowered in ("deprecated", "internal use only"):
        return "INFO"
    actionable = ("use ", "replaced by", "instead", "see ", "prefer ", "superseded")
    if any(token in lowered for token in actionable):
        return "WARN"
    return "INFO"


def read_source(source: str) -> str:
    if source.startswith("http"):
        with urllib.request.urlopen(source) as response:
            return response.read().decode("utf-8", errors="replace")
    with open(source, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", action="append", dest="sources", default=None,
                        help="deprecated-list.html URL or local path; repeatable")
    parser.add_argument("--out", required=True, help="where to write ruleset.json")
    parser.add_argument("--ruleset-version", default=None,
                        help="version stamp, e.g. 2026.08.1")
    args = parser.parse_args()

    # Paper's javadoc is a superset of Spigot's: it re-lists the whole Bukkit API and
    # adds io.papermc / com.destroystokyo on top. Reading both means the ruleset covers
    # plugins built against either, and the merge below keeps the more severe rating
    # when the two disagree.
    sources = args.sources or [DEFAULT_SOURCE, DEFAULT_PAPER_SOURCE]

    entries = []
    for source in sources:
        try:
            page = read_source(source)
        except Exception as exc:  # noqa: BLE001 - report and continue
            print(f"warning: skipping {source}: {exc}", file=sys.stderr)
            continue
        platform = "paper" if "papermc" in source or "paper" in source.lower() else "spigot"
        found = list(parse_entries(page))
        for entry in found:
            entry["platform"] = platform
        print(f"  {len(found):5d} entries from {source} ({platform})", file=sys.stderr)
        entries.extend(found)

    if not entries:
        print("error: no entries parsed from any source", file=sys.stderr)
        return 1

    # Deduplicate: the same member is listed in both the terminal section and the
    # per-kind one. Neither row alone is complete — the terminal row carries the
    # for-removal flag but an empty description, while the general row carries the
    # replacement hint — so the two are merged rather than one discarded.
    merged = {}
    for entry in entries:
        key = (entry["owner"], entry["name"], entry["descriptor"])
        existing = merged.get(key)
        if existing is None:
            entry["terminalOn"] = [entry["platform"]] if entry["terminal"] else []
            merged[key] = entry
            continue
        if entry["terminal"] and entry["platform"] not in existing["terminalOn"]:
            existing["terminalOn"].append(entry["platform"])
        # Prefer whichever source gave a usable hint. Paper and Spigot word the same
        # deprecation differently, and one of the two is often blank.
        if len(entry["replacement"] or "") > len(existing["replacement"] or ""):
            existing["replacement"] = entry["replacement"]

    final = sorted(merged.values(), key=lambda e: (e["owner"], e["name"], e["descriptor"]))
    for entry in final:
        # Paper schedules removals far more aggressively than Spigot: it marks ~1200
        # elements for removal where Spigot marks ~38. Rating all of those ERROR for
        # everyone would punish a Spigot-targeting plugin for Paper's roadmap, so an
        # entry is only an error where the platform it targets says so.
        terminal_on = entry.pop("terminalOn")
        entry.pop("platform", None)
        entry.pop("terminal", None)
        if terminal_on:
            entry["terminalOn"] = sorted(terminal_on)
        entry["severity"] = ("ERROR" if "spigot" in terminal_on
                             else severity_for(entry["replacement"]))
        if terminal_on == ["paper"] and entry["severity"] == "INFO":
            # Scheduled for removal on Paper but not on Spigot: still worth a warning
            # for the majority of servers, which run Paper.
            entry["severity"] = "WARN"

    document = {
        "schema": 1,
        "rulesetVersion": args.ruleset_version or "generated",
        "sources": sources,
        "entries": final,
    }

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    counts = {"ERROR": 0, "WARN": 0, "INFO": 0}
    for entry in final:
        counts[entry["severity"]] += 1
    print(f"wrote {len(final)} entries to {args.out}", file=sys.stderr)
    print(f"  ERROR {counts['ERROR']}  WARN {counts['WARN']}  INFO {counts['INFO']}",
          file=sys.stderr)


if __name__ == "__main__":
    main()
