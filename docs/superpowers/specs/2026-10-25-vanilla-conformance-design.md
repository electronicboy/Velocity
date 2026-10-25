# Vanilla protocol conformance — design

Date: 2026-10-25
Status: self-designed at the user's request (no question round); v1 implemented (section 11).

## 1. Intent

Velocity claims to sit transparently between a vanilla client and a vanilla server
on every version from 1.7.2 to the current release. Nothing checks that claim
end-to-end. Packet tables are maintained by hand, codecs are re-implemented by
hand, and the only feedback loop is a user noticing breakage in production.

This framework checks the claim mechanically: **a vanilla server's traffic,
observed on both sides of the proxy, must be identical except for transformations
the proxy is known and documented to make.** Everything else — a lossy re-encode,
a dropped packet, a reordering, a mis-mapped id — is a finding.

It runs weekly, unattended, against every supported version, and produces a
coverage report that states plainly how much of the vanilla protocol was actually
exercised.

### 1.1 Relationship to existing work

- **`protocol-conformance` project** (commit "Add a protocol conformance
  project"): downloads every supported vanilla server jar and runs Mojang's data
  generator over it, currently to check argument-type ids. This design extends
  that project rather than starting another one. Its download/cache machinery is
  reused.
- **2026-09-17 client-flow design** (`compliance/velocity-client-flows`): race
  and state-machine flows against a *synthetic* backend, for the config-phase
  defect cluster. This design is the *real-server* tier that one deferred: it
  shares the trace format and the ledger semantics (expected vs observed,
  unexpected pass fails), but uses a vanilla server and makes no attempt at timing
  control. The two are complementary — synthetic for races, vanilla for wire
  fidelity.

## 2. Goals, non-goals, anti-goals

### Goals

1. Every packet the vanilla server emits during a scripted session is checked
   byte-for-byte against what reaches the client, and vice versa for the client's
   packets reaching the server.
2. Velocity's packet id tables are checked against Mojang's own `packets.json`
   for every supported version that ships one, with no session required.
3. Packets the proxy *originates* (status, login, encryption) are validated
   against the vanilla format independently of the proxy's own codecs.
4. The online-mode path (encryption, session-server verification, profile
   properties) is exercised with a local Yggdrasil-compatible session service.
5. A coverage report per version lists which vanilla packet types were exercised
   and which were not — the honest answer to "all packets".
6. A weekly scheduled run over all supported versions, with a committed ledger of
   expectations so known breakage is explicit and fixes cannot land silently.
7. The live harness is black-box: it takes a proxy jar and a proxy kind, so the
   same harness can be pointed at Waterfall.

### Non-goals

- Driving a real, rendering Minecraft client.
- Timing/race reproduction (that is the client-flow design's job).
- Forwarding modes. Backends run offline with **no** forwarding, which is what
  permits a stock vanilla server to sit behind the proxy at all.
- Profile-key (1.19–1.19.2) verification: keys are signed by Mojang and cannot be
  forged by a local session service. `force-key-authentication` is disabled.
- Performance measurement.

### Anti-goals (failure even if it technically works)

- **A noise generator.** Infrastructure failures (port bound, server failed to
  start, RCON refused) must be reported as `error`, never as protocol findings.
- **Circular oracles.** The harness must not decode traffic with Velocity's own
  codecs or packet tables; a Velocity bug would then validate itself.
- **Committing Mojang data.** Server jars and generator output stay in the build
  directory, re-pulled each run, as the existing project already does.
- **Unexplained allowlist entries.** Every tolerated difference names the proxy
  behaviour that causes it.

## 3. Architecture

```
            +--------------------- harness (black-box, no Velocity deps) ---------------------+
            |                                                                                  |
  ScriptedClient  <--TCP-->  PROXY UNDER TEST  <--TCP-->  Tap relay  <--TCP-->  vanilla server |
   (C side capture)           (any jar)                  (B side capture)      (RCON-driven)   |
            |                     ^                                                            |
            |                     | hasJoined                                                  |
            +-- join --> FakeSessionService <-----------+                                      |
            |                                                                                  |
            +--> Trace(C)  Trace(B) --> Differ (+ proxy rules) --> Findings --> Ledger --> Report
                                \--> Coverage (vs packet universe)
            +----------------------------------------------------------------------------------+

  conformance source set (Velocity on classpath, no session):
      VanillaDataGenerator --> packets.json / registries.json --> PacketIdConformanceTest
                                                              --> ArgumentTypeRegistryConformanceTest
```

### 3.1 Units

| Unit | Responsibility |
|---|---|
| `VanillaServer` | Download (sha1-verified, cached), lay out, configure and run a vanilla server for one version; RCON client; Java runtime selection. |
| `PacketTable` | Per-version id ↔ name per (phase, direction). Built from the jar's own `packets.json` (1.21+) or PrismarineJS `minecraft-data` (older). Names normalised to Mojang's. |
| `Framing` | VarInt framing, zlib compression, AES/CFB8. Shared by client and tap. |
| `TapRelay` | Byte-transparent TCP relay between proxy and backend; parses a copy of each direction into a `Trace`. Never alters timing or bytes. |
| `ScriptedClient` | Independent client: handshake, status, login (offline / online with encryption), configuration, play. Answers keepalive, ping, teleport, chunk batches, tick end, player loaded, known packs. Records its own `Trace`. |
| `FakeSessionService` | `/session/minecraft/join` + `/hasJoined`, Yggdrasil-compatible JSON. |
| `Scenario` | A session script: what the client sends, which RCON commands run, how it ends. |
| `Differ` | Aligns B and C traces per direction and phase, applies `ProxyRules`, classifies findings. |
| `ProxyRules` | Per proxy kind: tolerated transformations (brand rewrite, injected proxy commands, consumed login packets…), each with a justification. |
| `Coverage` | Exercised packet types vs the `PacketTable` universe. |
| `Ledger` | Committed TOML expectations per (scenario, version range). |
| `Reporter` | Markdown summary, JUnit XML, per-cell trace files. |

### 3.2 The oracle

Three independent checks, none of which use the proxy's code:

1. **Differential transparency.** For each direction and phase, the packet
   sequence on the far side must equal the sequence on the near side. Packets are
   compared as decompressed `id + payload` bytes, so framing and compression
   differences are irrelevant.
2. **Proxy-originated packets.** Status response (valid JSON, protocol echo,
   pong echo) and the login sequence (encryption request, set compression, login
   success carrying the session service's UUID, name and properties) are decoded
   by the harness and checked against the vanilla layout.
3. **Vanilla acceptance.** The vanilla server is the oracle for serverbound
   traffic: a corrupted packet produces a kick, which is a finding in its own
   right with the disconnect reason attached.

### 3.3 Alignment and classification

Per direction and phase epoch, the source sequence `S` (what the sender emitted)
and sink sequence `K` (what the receiver got) are aligned with a Myers diff over
packet content hashes. Unmatched regions are classified:

| Shape | Finding (unless a rule tolerates it) |
|---|---|
| `S[i]` and `K[j]` same id, different bytes | **mismatch** — first differing offset, hex context |
| `S[i]` unmatched | **dropped** |
| `K[j]` unmatched | **injected** |
| identical bytes, different position | **reordered** |

Rules see the packet name, phase, direction, version and both payloads and may
tolerate a mismatch (with a semantic comparator), a drop (consumed by the proxy),
an injection (originated by the proxy) or a reorder (deferred by the proxy).

The run is bracketed by **sync markers** so in-flight packets at the end of a
session do not appear as drops: an RCON `tellraw` with a unique token
(clientbound) and a client custom payload with a unique token (serverbound). Both
traces are cut at the marker.

### 3.4 Semantic comparators (v1)

- **Brand**: the proxy may append its own identifier to the backend's brand.
  Tolerated if the backend brand is preserved within the rewritten string.
- **Command tree**: decoded by the harness's own Brigadier decoder (argument
  properties per version from the generator's `command_argument_type` registry,
  or string identifiers before 1.19). Tolerated if the backend tree is preserved
  exactly and the only additions are root literals in the proxy's command set.

Everything else must be byte-identical. That includes every packet Velocity
decodes and re-encodes (JoinGame, Respawn, PlayerInfo, chat, boss bars…), which
is precisely where lossy codecs hide.

## 4. Packet universe and coverage

`packets.json` (data generator, 1.21+) is authoritative and uses Mojang names.
For older versions, PrismarineJS `minecraft-data` `protocol.json` supplies ids and
names; a small normalisation table maps the Prismarine names the harness needs to
Mojang's. The coverage report lists, per version and (phase, direction), the
exercised and unexercised packet types. Packets that cannot be provoked from a
vanilla server and a scripted client are recorded in the ledger as
`unreachable` with a reason, so the uncovered list shrinks to genuine gaps.

Clientbound coverage is driven mainly through RCON on the vanilla server — a
version-gated command script (`title`, `bossbar`, `scoreboard`, `team`,
`worldborder`, `particle`, `playsound`, `summon`, `effect`, `give`, `tp`,
`setblock`, `fill`, `time`, `weather`, `xp`, `advancement`, `recipe`, `kill`,
`damage`, `tick`, `dialog`, `waypoint`, …). Serverbound coverage comes from the
client's script (chat, commands, suggestions, settings, movement, swing, use
item, carried item, client command, custom payload, …).

## 5. Static packet id conformance

A new test in the `conformance` source set: for every supported version whose jar
ships `packets.json`, and every packet class Velocity registers in
`StateRegistry` for that version, Velocity's id must equal vanilla's id for the
mapped name. The class → name mapping is a committed table in the test. An
unmapped Velocity class fails loudly, so new packets cannot be added without
stating what they are. This runs in seconds once the generator output is cached,
and catches id mistakes for packets no scenario happens to provoke.

## 6. Authentication

`FakeSessionService` binds to loopback and implements:

- `POST /session/minecraft/join` — records `(username → serverId)`.
- `GET /session/minecraft/hasJoined?username=&serverId=[&ip=]` — `200` with a
  profile (`id`, `name`, `properties` incl. a `textures` property) when the pair
  matches, `204` otherwise.

Velocity is pointed at it with `-Dmojang.sessionserver=<base>/session/minecraft/hasJoined`;
Waterfall with its `auth_url` setting. The client performs the real encryption
handshake (RSA-encrypted shared secret and verify token, AES/CFB8 stream, Mojang
server-id hash). Scenarios: online success, online without `join` (must be
disconnected during login), offline.

## 7. Expectations

`ledger.toml`, committed:

```toml
[[cell]]
scenario = "play/exercise"
versions = ">=1.20.2"
status   = "known-fail"
reason   = "Velocity re-encodes X and drops field Y"
issue    = 0

[[unreachable]]
packet   = "minecraft:debug_sample"
versions = ">=1.20.5"
reason   = "only sent to subscribed operators in a debug UI"
```

Statuses: `pass` (default for anything not listed), `known-fail`,
`not-applicable`. Observed `error` is never an expectation. Outcomes follow the
2026-09-17 design: regression and unexpected pass both fail the run.

## 8. Execution

- **Gradle**: `:velocity-protocol-conformance:vanillaConformance` builds the
  proxy `shadowJar` and runs the harness for a version list derived from
  `ProtocolVersion.SUPPORTED_VERSIONS` (one representative — the newest release
  name — per protocol number). `-Pconformance.versions=1.21.11,26.3` narrows it.
- **Standalone**: the harness source set has no Velocity dependency and builds
  to its own jar, runnable as
  `java -jar conformance-harness.jar --proxy-jar X --proxy-kind waterfall ...`.
  This is the Waterfall integration path.
- **Java**: each vanilla server runs on the smallest available JDK ≥ the major
  version Mojang's version JSON asks for. Pre-1.17 servers run on Java 17
  (verified for 1.7.10, 1.8.9, 1.12.2). JDKs come from Gradle toolchains.
- **Isolation**: every run gets fresh dynamic ports and a fresh work directory;
  server worlds are flat, view distance minimal, natural spawning off.
- **Weekly CI**: the existing workflow gains a sharded `vanilla` job (version
  groups in a matrix), uploading traces and the report as artifacts and writing
  the Markdown summary to the job summary.

## 9. Product changes

None required. Everything needed is reachable through existing seams: offline
mode, `player-info-forwarding-mode = "none"`, `login-ratelimit = 0`,
`force-key-authentication = false`, `-Dmojang.sessionserver`. If findings show a
Velocity tweak would materially improve testability, it is proposed as its own
change, not folded into the harness.

## 10. Builder's latitude (my calls)

- Plain blocking sockets instead of Netty in the harness: deterministic, easy to
  reason about, no shared code with the proxy.
- Ledger in TOML via night-config (already a Velocity dependency).
- Trace line format as in the 2026-09-17 design, plus a content hash column.
- Server switching with two vanilla backends is **deferred** to a follow-up; the
  differ already handles phase epochs, so it is additive.
- A full field-level decoder (ProtoDef interpreter over `minecraft-data`) is
  deferred; the byte-level differential oracle does not need it.

## 11. Implementation notes (2026-10-25)

What calibrating against real servers changed, so the next person does not relearn it.

### Harness lessons

- **The JDK's `CipherInputStream` holds decrypted bytes back** until more ciphertext arrives. In a
  request/response exchange (1.20.2+ login acknowledgement) the last packet before the server
  waits is never delivered, and the client stalls until the proxy's login timeout. The client
  uses a byte-wise CFB8 over AES/ECB instead (`wire/Cfb8`).
- **Greedy alignment is fooled by repeated packets.** Velocity's injected title reset is
  byte-identical to a reset the backend sends a hundred packets later. The differ is a patience
  diff anchored on packets unique to both sides, with a greedy walk only inside gaps.
- **Old servers boot on new JDKs but do not work.** 1.8.9 starts on Java 17 and resets every
  connection at login. Java 8 is part of the JDK set; each server runs on the smallest JDK at
  least as new as Mojang's `javaVersion`.
- **Vanilla defaults move.** 26.3 enables the whitelist by default; the harness sets every
  property it depends on explicitly.
- **26.3 changed client obligations**: `accept_teleportation` echoes position and counts as the
  tick's movement (a following move packet is a kick), relative teleports must be applied to a
  tracked absolute position, and `swing` became an empty `punch`.
- **Only operators see the full command tree**, so command-tree expectations are scoped to the
  scenario that ops the player.
- **Vanilla 1.13's RCON runs commands off the server thread**; `/kill` crashes it. The script skips
  it there, a crashed backend is reported as `error`, and the backend is restarted for the next
  scenario.
- **Several packets of one type** (three command trees in a session) must be paired tolerated-first,
  then in order, or the differ cross-matches them.

### Findings in Velocity

| Finding | Versions | Status |
|---|---|---|
| `minecraft:resource_or_tag` arguments re-encoded as `minecraft:resource` (both registered against `RegistryKeyArgument`). On 1.18.2 the substituted name does not exist client-side. | 1.18.2+ | ledger; fix on `fix/resource-or-tag-argument-id` |
| Chat and commands forwarded after async events, so later serverbound packets overtake them. | all | ledger (flaky) |
| Unsigned 1.19–1.19.2 chat re-stamped with `Instant.now()` on encode (`KeyedPlayerChatPacket`); with the async delay, a following command carries an earlier timestamp and vanilla kicks the player for out-of-order chat. | 1.19–1.19.2 | ledger |

Documented behaviours tolerated by `VelocityRules`, each citing its source: brand rewrite, proxy
commands merged into the tree, tab list header/footer reset and title reset on join,
`server_data` answered from the proxy's own ping, client brand held and settings replayed during
configuration, plugin channels re-announced to the backend, NBT compound order in 1.16–1.20.1 join
game, the 26.x join game online-mode flag, wrapped kick messages, sorted legacy tab completions.

### Static check

`PacketIdConformanceTest` found Velocity's packet ids identical to Mojang's `packets.json` for
1.21 through 26.3. It also noted that Velocity registers `show_dialog`/`clear_dialog` only in
configuration, not play; that is not an id error (they pass through undecoded).

### Deferred

- Server switching between two vanilla backends (the differ already splits by phase epoch).
- A kick scenario (the wrapped-kick rule exists but no scenario provokes a kick deliberately).
- Waterfall: the adapter and `BungeeRules` exist but have not been calibrated against a Waterfall
  build.
- A field-level decoder (ProtoDef over minecraft-data) for richer mismatch reports.
- Serverbound coverage beyond the scripted set; the coverage report lists what is missing.

## 12. Server transitions (added 2026-10-25)

Server switches are the other main source of breakage in the tracker: plugin messages dropped
while no server is connected (#1766), client brand and settings not reaching the next server
(#1212, #1015), packets lost right after JoinGame (#1873), players stuck in reconfiguration
(#1741), configuration-ending packets inside an open bundle (#1384), fast repeated switches
(#1334), mod payloads lost around configuration (#1284, #1191). A vanilla backend cannot reproduce
every timing, but it can pin down the invariants.

### Setup

A second vanilla server (B) with its own tap runs beside A; the proxy knows both and tries A
first. The client switches with the proxy's `/server` command, which Velocity and Waterfall both
provide. A vanilla 1.20.2+ client re-enters configuration on a switch *without* re-sending its
brand or settings (verified against the client source: `handleConfigurationStart` sends only the
acknowledgement), so the scripted client does the same; replaying them is the proxy's job.

### Checks

1. **Per-backend transparency.** The client's stream is split at the transition (the
   configuration start on 1.20.2+, the second JoinGame before). The part before is diffed against
   A's session, the part after against B's, with the usual proxy rules. Packets A sends after the
   switch was requested may be dropped — the client can no longer accept them — and are reported
   as tolerated, never silently.
2. **Probe conservation.** The client sends a custom payload carrying a unique token at each step
   of the transition: before acknowledging configuration, right after entering it, on B's first
   configuration packet, before finishing, after finishing, and on B's JoinGame. Every token must
   reach exactly one backend; tokens sent during configuration must reach B during B's
   configuration phase.
3. **Replay.** B must receive the client's brand and its most recent client information,
   byte-identical.
4. **Liveness and hygiene.** The switch completes within the deadline, nobody is disconnected,
   neither backend logs a decode error, and the client never receives a configuration-starting
   packet inside an open bundle.

### Scenarios

- `switch/basic` — A → B.
- `switch/round-trip` — A → B → A (a second session on A's tap).
- `switch/rapid` — A → B, and back to A the moment B's JoinGame arrives.

Kicks during configuration (#1832, #1603) cannot be provoked from a vanilla server at the right
moment and stay with the synthetic-backend design.

### Results (2026-10-25)

All 52 supported versions × 3 switch scenarios: everything passes or fails only as the ledger
expects. Defects recorded in the ledger:

| Finding | Versions |
|---|---|
| Plugin messages sent between finishing configuration and the backend's join game are dropped (#1766). | 1.20.2+ |
| Plugin messages sent while a switch starts are dropped: `doSwitch` disconnects the old server before the client is told to reconfigure. | 1.20.2+ |
| A plugin message sent right after login, before the backend connection is up, is dropped or throws `Not connected to server!` and kicks the player ("An internal error occurred"). Roughly half the sessions on 1.21.11 and 26.3. | 1.20.2+ |
| Chat and commands sent right after a switch's join game are dropped while `getCurrentServer()` is empty. | <1.20.2 |

Brand and settings replay to the new server held on every version. Harness lessons: separate
player names per scenario (the proxy may not have finished disconnecting the previous one), the
proxy's own keep-alives during a switch must be recognised in both directions, and an RCON listener
on old servers occasionally refuses connections during long runs (one restart on fresh ports).
