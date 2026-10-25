# Protocol conformance

Checks Velocity against vanilla Minecraft. Two suites, neither part of the normal build:

| Suite | Task | What it proves | Proxy started? |
|---|---|---|---|
| Registries | `conformanceTest` | Velocity's packet id and argument type tables match Mojang's own data generator output for every supported version. | no |
| Vanilla transparency | `vanillaConformance` | Traffic between a vanilla server and a scripted client crosses the proxy intact, in both directions, on every supported version. | yes |

Both run weekly in `.github/workflows/protocol-conformance.yml`, on demand, and on pull requests
labelled `minecraft update`. Design: `docs/superpowers/specs/2026-10-25-vanilla-conformance-design.md`.

Nothing downloaded or generated from Mojang is committed; it all lives under `build/`.

## Running it

```sh
# Static id tables (minutes the first time; cached afterwards)
./gradlew :velocity-protocol-conformance:conformanceTest

# The live suite against the proxy built from this tree, every supported version
./gradlew :velocity-protocol-conformance:vanillaConformance

# Narrow it down
./gradlew :velocity-protocol-conformance:vanillaConformance \
    -Pconformance.versions=1.8.9,1.21.11,26.3 \
    -Pconformance.scenarios=play/exercise,auth/online
```

Other properties: `-Pconformance.shard=0/4` (every fourth version, as CI does) and
`-Pconformance.gate` (exit non-zero on a regression, an unexpected pass or an infrastructure error).

Results land in `build/conformance-report/` (`summary.md`, `coverage.md`, `junit.xml`) and the raw
material in `build/conformance-work/<version>/`: server and proxy console logs, and per scenario
`traces/<scenario>/` with both sides' packet traces, `findings.txt`, and for every mismatch the two
payloads as `.bin` files.

## How the live suite works

```
scripted client ──> proxy under test ──> tap relay ──> vanilla server (RCON-driven)
      │ records what it sent/received          │ records both directions
      └──────────────── differ ────────────────┘
```

- The vanilla server runs offline with no forwarding, a flat world, and RCON enabled.
- The **tap relay** sits between proxy and backend and forwards every frame unchanged after
  recording it.
- The **scripted client** is an independent implementation (it shares no code with any proxy): it
  logs in (offline, or online through a local Yggdrasil-compatible session service with real
  encryption), answers keep-alives, pings, teleports, chunk batches, known packs and phase
  acknowledgements, and sends a spread of serverbound packets.
- RCON commands make the server emit as many clientbound packet types as it can (`RconScript`).
- Both captures are cut at sync markers, then the **differ** aligns them. Any packet that changed,
  went missing, appeared from nowhere or arrived out of order is a finding unless a proxy rule
  explains it.
- Packet names come from the server jar's own `reports/packets.json` (1.21+) or from PrismarineJS
  minecraft-data (older versions), normalised to Mojang's names.

Scenarios: `status/ping`, `play/exercise`, `config/full-registries` (1.20.5+), `auth/online`,
`auth/not-joined`, and the server-transition scenarios below.

### Server transitions

`switch/basic` (A → B), `switch/round-trip` (A → B → A) and `switch/rapid` (A → B, straight back
when B's join game arrives) run a second vanilla backend and switch with the proxy's `/server`
command. They check:

- each backend session is transparent on its own (the client's stream is split at the
  configuration start, or the second join game before 1.20.2); packets the old server sends after
  the switch was requested are reported as tolerated, never silently;
- **probe payloads**: the client sends a custom payload with a unique token at each step of the
  transition (`BEFORE_SWITCH`, `START_CONFIGURATION`, `CONFIGURATION_ENTERED`,
  `FIRST_CONFIGURATION_PACKET`, `FINISH_CONFIGURATION`, `PLAY_ENTERED`, `JOIN_GAME`). Each must
  reach exactly one backend, and those sent during configuration must reach the backend being
  configured, in its configuration phase. Failures read `CHECK probe.dropped <point>`,
  `probe.duplicated` or `probe.misrouted`;
- **replay**: the new backend receives the client's brand and latest client information
  byte-for-byte (a vanilla client does not resend them when it re-enters configuration);
- no configuration start or join game inside an open bundle, no stuck switch, no disconnect.

Per-backend traces land in `traces/switch_*/backend-<n>-<server>.trace`.

## When it fails

Read the cell in `summary.md`, then the cell's `findings.txt`. Each finding is one of:

| Kind | Meaning |
|---|---|
| `MISMATCH` | Same packet type on both sides, different bytes. The detail gives the first differing offset with hex context; the payloads are saved as `.bin`. |
| `DROPPED` | Sent, never delivered. |
| `INJECTED` | Delivered, never sent. |
| `REORDERED` | Delivered intact, out of order. |
| `CHECK …` | A non-differential check: login result, status response, proxy or backend log errors, etc. |

Then decide which of three things it is:

1. **A proxy defect.** Fix it, or record it in `ledger.toml` as an `[[expect]]` with the reason and
   issue number. A known failure is reported but does not fail the run. When it is fixed, the run
   fails with *unexpected pass* until the entry is removed, so fixes cannot land silently.
2. **A deliberate proxy behaviour.** Add a tolerance to `VelocityRules` (or `BungeeRules`) that is as
   narrow as possible and cites the code responsible. Tolerated differences are still listed in
   the report.
3. **A harness limitation.** Fix the harness.

Infrastructure problems (a server that will not start, a download that fails) are reported as
`ERROR`, never as findings.

## Ledger

```toml
[[expect]]
proxy = "velocity"                    # default "*"
scenario = "play/exercise"            # default "*"
versions = ">=1.19"                   # "*", "1.20.4", ">=1.20.2", "<1.13", "1.8..1.12.2", lists
signature = "MISMATCH S->C PLAY minecraft:commands"   # prefix of the finding signature
detail = "sent minecraft:resource_or_tag delivered minecraft:resource"   # optional substring
flaky = false                         # true: its absence is not treated as a fix
reason = "why"
issue = 1234

[[unreachable]]                       # coverage only: cannot be provoked by the harness
packet = "minecraft:debug_sample"
versions = ">=1.20.5"
reason = "only sent to operators subscribed through a debug screen"
```

## Other proxies

The harness has no dependency on Velocity. To run it against BungeeCord or Waterfall:

```sh
./gradlew :velocity-protocol-conformance:harnessJar
java -jar protocol-conformance/build/libs/*-harness.jar \
    --proxy-jar Waterfall.jar --proxy-kind waterfall --versions 1.8.9,1.21.11 \
    --jdks /path/to/jdk17:/path/to/jdk21:/path/to/jdk25 --report report --work work
```

## Debugging

- `-Dconformance.debug=true` on the harness JVM prints every packet the client sends and receives,
  and the shared secret of encrypted sessions (so a `tcpdump` capture can be decrypted).
- `-Dconformance.proxyJvmArgs="..."` passes extra flags to the proxy, e.g. a log4j configuration.
