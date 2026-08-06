# mcdrift

[![CI](https://github.com/kitsune-de/mcdrift/actions/workflows/ci.yml/badge.svg)](https://github.com/kitsune-de/mcdrift/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/kitsune-de/mcdrift)](https://github.com/kitsune-de/mcdrift/releases)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Finds Minecraft plugin code that breaks on newer server versions — by reading the
compiled `.jar`. No source, no server, no plugin install.

```bash
mcdrift myplugin.jar --target 26.1
```

```
MyPlugin 1.4.2  (myplugin.jar)
target Minecraft 26.1   214 classes scanned, 96 shaded library classes ignored

[version-parsing]
  ERROR  com.example.Compat:57
         Version check on literal "1." via startsWith() assumes the legacy 1.x scheme
         -> Minecraft 26.1 replaced 1.21.11; there is no 1.22. This test does not crash,
            it silently takes the wrong branch. Compare parsed versions where every
            calendar version outranks every 1.x version, or feature-detect instead.

Summary: 1 error
```

Or check a whole server before upgrading it:

```bash
mcdrift plugins/ --target 26.1
```

```
Scanning 12 plugins against Minecraft 26.1

  PLUGIN                 ERRORS  WARNINGS   NOTES   VERDICT
  --------------------------------------------------------
  OldEconomy                  8        14       3   will break
  CustomEnchants              2         9       1   will break
  WorldGuard                  -         6       4   needs review
  LuckPerms                   -         -       2   looks fine

2 of 12 plugins have errors that will break on Minecraft 26.1.
```

## Why

Minecraft 26.1 (March 2026) broke three things at once, and two of them break quietly:

- **Version numbers went calendar.** `1.21.11` was the last of the old scheme; the next
  release was `26.1`. There is no 1.22. Any `version.startsWith("1.")` still compiles,
  still runs, and now silently takes the wrong branch — no stack trace to follow.
- **Obfuscation ended.** Mojang ships unobfuscated server jars and Paper dropped its
  remapper, so Spigot-mapped names like `EntityHuman` or `v1_20_R3` no longer resolve.
- **The world folder moved.** The Overworld, Nether and End now live under
  `world/dimensions/minecraft/*`. Code that builds those paths by hand reads empty
  directories.

Plus Java 25 became the minimum.

Existing compatibility checkers answer a different question: *has this plugin been
updated?*, by looking the plugin up on Hangar or Modrinth. mcdrift answers
*what in this code will break, and where?* — by reading the bytecode.

## Install

Requires Java 21 or newer to run. Analyses jars built with any Java version.

Grab `mcdrift.jar` from [Releases](https://github.com/kitsune-de/mcdrift/releases), or
build it:

```bash
git clone https://github.com/kitsune-de/mcdrift && cd mcdrift
./gradlew build
java -jar build/libs/mcdrift.jar myplugin.jar
```

## Usage

```bash
mcdrift <path>... [options]
```

`<path>` is a jar or a directory; directories are searched recursively.

| Option | |
|---|---|
| `-t, --target <version>` | Minecraft version to check against. Default `26.1`. |
| `--format <fmt>` | `text`, `summary`, `json`, `sarif`, `github`. Default `text`. |
| `--min-severity <sev>` | Hide findings below `error`/`warn`/`info`. Default `info`. |
| `--fail-on <sev>` | Exit non-zero at `error`/`warn`/`never`. Default `error`. |
| `--ignore-file <file>` | Suppression list. Defaults to `.mcdriftignore`. |
| `--no-ignore-file` | Ignore any `.mcdriftignore` that would be picked up. |
| `--disable <rule,rule>` | Skip rules by id. |
| `--ruleset <file>` | Use your own deprecation ruleset. |
| `--source-root <dir>` | Path prefix for `sarif`/`github` output. Default `src/main/java`. |
| `--stats` | Print aggregate `key=value` counts, for scripts. |
| `--list-rules` | Show available rules. |
| `--no-color` | Plain output. |

Exit code is `0` when clean, `1` when findings hit the `--fail-on` threshold, `2` on a
usage error.

With `--format json`, one jar produces the scan object directly; several jars produce
`{"scans": [...]}`, so the output is always a single parseable document:

```bash
mcdrift plugins/ --format json | jq '.scans[] | select(.summary.errors > 0) | .jar'
```

## GitHub Action

Findings appear inline on the pull request diff:

```yaml
- uses: kitsune-de/mcdrift@v1
  with:
    path: build/libs
    target: '26.1'
```

Every input is optional. To also publish to the Security tab:

```yaml
- uses: kitsune-de/mcdrift@v1
  id: mcdrift
  with:
    upload-sarif: 'true'
- uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: ${{ steps.mcdrift.outputs.sarif-file }}
```

The action exposes `errors` and `warnings` as step outputs.

## Rules

| id | what it catches | |
|---|---|---|
| `version-parsing` | Version checks assuming `1.x` | silent breakage |
| `legacy-mappings` | Spigot-mapped internals / NMS names | loud breakage |
| `world-paths` | Hardcoded dimension folders | silent breakage |
| `bytecode-level` | Class files newer than the server's JVM | fails at load |
| `deprecated-api` | Deprecated or removed Bukkit/Paper calls | varies |

Findings are scoped to `--target`: scanning against 1.21.11 will not report a
deprecation that only happened in 26.1.

## Suppressing findings

Adopting a linter on an existing codebase means being able to start without fixing
everything first. Put a `.mcdriftignore` next to your build file:

```
# ignore a rule everywhere
deprecated-api

# ignore a class or package
com.example.legacy.*

# ignore one rule in one place
deprecated-api:com.example.OldItemDb
```

Unknown rule ids are rejected with the line number, so a typo cannot silently disable
a check you thought was on.

## Design notes

A linter that cries wolf gets uninstalled, so the rules are deliberately conservative:

- **Shaded libraries are skipped.** A finding inside a relocated dependency is not
  something the plugin author can fix. Skipped classes are counted and labelled, not
  hidden.
- **Prose is not code.** A string like `"...e.g. v1_10_R1"` is an error message, not a
  class reference. Literals containing spaces are ignored by `legacy-mappings`.
- **Package-split NMS is fine.** `net/minecraft/world/entity/...` is Mojang-mapped and
  survives 26.1; only the flat legacy package and `v1_x_Rn` are flagged.
- **A bare `"1."` literal is not enough.** `version-parsing` only fires when the literal
  feeds a string comparison *and* the enclosing method reads a server version.
- **Severity comes from the javadoc, not from us.** Only members Spigot marks as
  *terminally* deprecated — scheduled for actual removal — are errors. Paper schedules
  removals far more aggressively (~1200 elements against Spigot's ~38), so members that
  only Paper has marked land at warning: real, but not worth failing a Spigot build over.
- **Source paths come from the class file.** javac records a `SourceFile` attribute, so
  `sarif` and `github` output points at the file that actually exists — including for
  inner classes, and for classes whose name differs from their file's. When the
  attribute is missing, the finding is reported without a location rather than with a
  guessed one, because GitHub silently discards results pointing at absent files.
- **Unparseable classes still get checked.** If a class file is too new for the bundled
  ASM, the class file version is read from the header rather than the class being
  silently dropped — otherwise the tool would ignore exactly the plugins built for the
  newest Minecraft.

Line numbers come from debug info. Jars compiled without it report `Class#method`
instead.

## The deprecation ruleset

`src/main/resources/ruleset.json` holds 1271 entries generated from the Spigot and
Paper javadocs, and is versioned separately from the tool so it can be refreshed
without a release:

```json
{
  "owner": "org/bukkit/block/Block",
  "name": "getData",
  "descriptor": "()",
  "severity": "WARN",
  "replacement": "Magic value",
  "terminalOn": ["paper"]
}
```

`terminalOn` records which platforms have scheduled the member for removal, which is
what keeps Paper's aggressive removal schedule from turning into errors on a Spigot
build.

Regenerate it when a new Minecraft version ships — both javadocs are read by default:

```bash
python tools/generate_ruleset.py --out src/main/resources/ruleset.json --ruleset-version 2026.09.1
```

`descriptor` is a parameter-list prefix — `(I)` matches any return type, which is
unambiguous because two overloads cannot share a parameter list. A full descriptor
matches exactly, and `"*"` matches any overload. Pass your own with `--ruleset`.

## Contributing

The most useful contributions right now are false positives. If mcdrift flags something
it shouldn't, that's a bug — open an issue with the jar or a minimal reproduction.

Run the tests with `./gradlew build`.

## Licence

MIT.

## Releasing

Tag and push; the release workflow builds and publishes `mcdrift.jar`:

```bash
git tag -a v1.2.3 -m "mcdrift 1.2.3" && git push origin v1.2.3
```

The tag must match `version` in `build.gradle.kts`, which the workflow checks before
publishing. If the tag push does not start a run — some accounts have push-triggered
workflows disabled — start it from the Actions tab, or publish manually:

```bash
./gradlew build && gh release create v1.2.3 build/libs/mcdrift.jar src/main/resources/ruleset.json
```
