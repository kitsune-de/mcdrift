# Launch posts — mcdrift 1.0.0

Every number below is verified against a real scan. Sources at the bottom.

**Facts available to use:**

| claim | value |
|---|---|
| Ruleset entries | 1271 (35 ERROR, 785 WARN, 451 INFO) |
| Generated from | Spigot + Paper javadocs |
| Paper-only removals | 390 |
| Spigot-confirmed removals | 35 |
| EssentialsX 2.20.1 scan @ 26.1 | 12 errors, 166 warnings, 144 notes |
| Real finding | `net.ess3.nms.refl.ReflUtil:19-24` references `v1_12_R1`…`v1_19_R2` |
| Classes scanned in EssentialsX | 583 (505 shaded libs skipped) |
| Tests | 41 |
| Jar size | ~1.0 MB |
| Rules | 5 |
| Repo | https://github.com/kitsune-de/mcdrift |

---

## X — primary version (no hashtags)

Per `writing_no_ai_slop`: hashtags are dead on X and read as marketing. This is the
version I'd actually post from @kitsunedevs.

> Minecraft 26.1 broke version parsing for every plugin that does
> `version.startsWith("1.")`. It doesn't crash. It just takes the wrong branch forever,
> because 1.21.11 was the last of the old scheme and there's no 1.22.
>
> Wrote a thing that reads the compiled jar and finds it. No source needed, no server.
>
> Ran it on EssentialsX 2.20.1: 12 errors. Six of them are `net.ess3.nms.refl.ReflUtil`
> lines 19-24, a table of `v1_12_R1` through `v1_19_R2` mappings that don't resolve on
> unobfuscated jars anymore.
>
> github.com/kitsune-de/mcdrift

**Self-reply (post immediately after):**

> Severity comes from the javadocs, not from me. Ruleset is 1271 entries generated from
> Spigot + Paper. Paper schedules 390 removals Spigot hasn't, so those are warnings, not
> errors. Didn't want a Spigot plugin failing CI over Paper's roadmap.

---

## X — with hashtags (as requested)

Same post, tags appended. Keep it to three; X collapses relevance past that.

> Minecraft 26.1 broke version parsing for every plugin that does
> `version.startsWith("1.")`. It doesn't crash. It just takes the wrong branch forever,
> because 1.21.11 was the last of the old scheme and there's no 1.22.
>
> Wrote a thing that reads the compiled jar and finds it. No source needed, no server.
>
> Ran it on EssentialsX 2.20.1: 12 errors. Six of them are `net.ess3.nms.refl.ReflUtil`
> lines 19-24, a table of `v1_12_R1` through `v1_19_R2` mappings that don't resolve on
> unobfuscated jars anymore.
>
> github.com/kitsune-de/mcdrift
>
> #Minecraft #MinecraftPlugins #JavaDev

**Alternate tag sets, pick by audience:**

- Plugin devs: `#Minecraft #MinecraftPlugins #JavaDev`
- Server admins: `#Minecraft #MinecraftServer #Admincraft`
- Dev-tooling crowd: `#Java #StaticAnalysis #OpenSource`

---

## r/admincraft

Hashtags don't function on Reddit; the flair does. Post as **Tool/Resource**.

**Title:**
`Made a tool that tells you which of your plugins will break on 26.1 — reads the jars, no server needed`

**Body:**

> Upgrading to 26.1 means finding out which plugins survive, usually by starting the
> server and reading stack traces. I got tired of that and wrote something that reads
> the jars directly.
>
> ```
> mcdrift plugins/ --target 26.1
> ```
>
> ```
> Scanning 12 plugins against Minecraft 26.1
>
>   PLUGIN                 ERRORS  WARNINGS   NOTES   VERDICT
>   --------------------------------------------------------
>   OldEconomy                  8        14       3   will break
>   CustomEnchants              2         9       1   will break
>   WorldGuard                  -         6       4   needs review
>   LuckPerms                   -         -       2   looks fine
> ```
>
> It checks five things that 26.1 actually changed: version strings that assume `1.x`,
> Spigot-mapped internals that stopped resolving when Mojang dropped obfuscation, world
> folders that moved under `world/dimensions/`, class files compiled past Java 25, and
> deprecated API calls.
>
> This is different from PlugCheck or modcheck.sh, which look your plugin up on
> Hangar/Modrinth and tell you whether the author shipped an update. This reads the
> bytecode and tells you what specifically is going to break, including for plugins
> nobody maintains anymore.
>
> Tested it on EssentialsX 2.20.1 — 12 errors, and the interesting ones are a reflection
> table in `net.ess3.nms.refl.ReflUtil` holding `v1_12_R1` through `v1_19_R2`.
>
> Java 21+, MIT, single jar: github.com/kitsune-de/mcdrift
>
> If it flags something it shouldn't, that's a bug and I want the report — false
> positives are the fastest way to make a tool like this useless.

---

## SpigotMC — Resources section

Tags there are a real field. Use: `api`, `developer-tool`, `1.21`, `26.1`, `static-analysis`

**Title:** `mcdrift — find what breaks on 26.1 by scanning the jar`

**Description:**

> 26.1 changed three things that break plugins quietly:
>
> - Version numbers went calendar. `1.21.11` → `26.1`, no 1.22. Any
>   `version.startsWith("1.")` still compiles and silently takes the wrong branch.
> - Mojang stopped shipping obfuscated server jars and Paper dropped its remapper, so
>   Spigot-mapped names like `EntityHuman` or `v1_20_R3` no longer resolve.
> - World folders moved under `world/dimensions/minecraft/*`.
>
> mcdrift reads a compiled jar and reports which of those you hit, with line numbers.
> No source, no running server.
>
> The deprecation ruleset is 1271 entries generated from the Spigot and Paper javadocs,
> so severity is whatever the javadoc says rather than my opinion. Paper schedules 390
> removals Spigot hasn't — those are warnings, not errors, so a Spigot-targeting plugin
> doesn't fail CI over Paper's roadmap.
>
> There's a GitHub Action if you want findings on your PR diff.

---

## Dev.to / Hangar — article

Tags: `java`, `minecraft`, `staticanalysis`, `opensource`

**Title:** `Reading Minecraft plugin bytecode to find what 26.1 broke`

**Opening:**

> In March 2026 Mojang changed the version scheme from `1.21.11` to `26.1`. There is no
> 1.22. Every plugin doing `version.startsWith("1.")` still compiles, still runs, and
> now silently takes the wrong branch — no exception, no log line, nothing to grep for.
>
> Two other things broke at the same time. Mojang stopped shipping obfuscated server
> jars, so Spigot-mapped internals stopped resolving. And the world directory moved.
>
> I wanted to know which of my jars were affected without booting a server for each one,
> so I wrote a bytecode scanner. Some notes on what turned out to be harder than
> expected.

**Sections worth writing up:**

1. Why `@Deprecated` in the constant pool makes this tractable without source
2. The false-positive problem — EssentialsX has `" is not in valid version format. e.g.
   v1_10_R1"` in an error message, which is prose, not a class reference
3. Paper marks ~1200 elements for removal where Spigot marks 38 — why blindly trusting
   "terminal = error" would have produced 425 errors on a clean plugin
4. `SourceFile` in the class file beats guessing paths from class names
5. ASM 9.7 throws on Java 25 class files — the scanner would have silently skipped
   exactly the plugins it exists to check

---

## Sources for every claim

```bash
# 12 errors / 166 warnings / 144 notes on EssentialsX 2.20.1
java -jar mcdrift.jar EssentialsX-2.20.1.jar --stats --fail-on never

# the ReflUtil finding
java -jar mcdrift.jar EssentialsX-2.20.1.jar --no-color | grep ReflUtil

# 1271 entries, 35/785/451 split, 390 paper-only
python -c "import json,collections; d=json.load(open('src/main/resources/ruleset.json')); \
print(len(d['entries']), collections.Counter(e['severity'] for e in d['entries']), \
sum(1 for e in d['entries'] if e.get('terminalOn')==['paper']))"

# 41 tests
./gradlew test --rerun-tasks
```

## Posting notes

- Don't post the same copy twice. X's dedup filter cuts reach on repeated text.
- Reddit first, then X. A live thread with real replies gives the X post something to
  point at.
- First hour matters most on both. Answer every reply.
- The honest weak spot if someone asks: the ruleset covers Bukkit/Spigot/Paper API but
  the five structural rules are hand-written, so coverage is deep on 26.1 specifically
  and thin on older transitions.
