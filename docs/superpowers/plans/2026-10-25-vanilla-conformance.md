# Vanilla Protocol Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A weekly, black-box suite that proves a proxy is transparent to vanilla traffic on every supported version, plus a static check of Velocity's packet id tables against Mojang's `packets.json`.

**Architecture:** The `protocol-conformance` project's `main` source set becomes a dependency-light harness (scripted client, tap relay, vanilla server runner, fake session service, differ). Its `test` source set holds offline unit tests (run in `check`). The existing `conformance` source set keeps the static checks that need Velocity on the classpath and launches the live harness against the proxy `shadowJar`.

**Tech Stack:** Java 25 toolchain, plain blocking sockets, `java.util.zip`, `javax.crypto`, `com.sun.net.httpserver`, Gson, night-config TOML, JUnit 6.

**Spec:** `docs/superpowers/specs/2026-10-25-vanilla-conformance-design.md`

## Global Constraints

- Harness (`main` source set) must not depend on `:velocity-proxy` or `:velocity-api` (anti-goal: circular oracles).
- Nothing downloaded or generated from Mojang is committed; it lives under `protocol-conformance/build/`.
- Infrastructure failures are `error`, never findings.
- Every tolerated difference in `ProxyRules` carries a justification string naming the proxy behaviour.
- Backends: `online-mode=false`, no forwarding. Proxy: `player-info-forwarding-mode = "none"`, `login-ratelimit = 0`, `force-key-authentication = false`.
- Checkstyle (`config/checkstyle/checkstyle.xml`) applies with zero warnings; license header as in existing files.
- Package root: `com.velocitypowered.proxy.conformance` (static) and `com.velocitypowered.proxy.conformance.harness` (live).

## Review Focus

1. A version in `SUPPORTED_VERSIONS` that Mojang has not published (snapshot constant ahead of the manifest) → skipped with a note, not an error that fails the run.
2. A proxy that fails to start, or a vanilla server that never prints `Done` → cell `error` with the process log tail, bounded by a deadline.
3. Packets in flight when a session ends → no spurious `dropped` (sync markers).
4. A packet whose uncompressed size is exactly the compression threshold → framed uncompressed (vanilla rule is `size >= threshold` compresses).
5. The backend kicks the client mid-session → the session ends promptly and the kick reason is attached to the finding.

---

### Task 1: Project layout and wire primitives

**Files:**
- Modify: `protocol-conformance/build.gradle.kts`
- Create: `.../harness/io/Buf.java` (VarInt/VarLong/String/UUID/bytes read+write over a growable byte array), `.../harness/io/FrameCodec.java` (frame split/join, zlib per vanilla threshold rule), `.../harness/io/CipherStreams.java` (AES/CFB8 wrappers)
- Test: `src/test/java/.../harness/io/FrameCodecTest.java`, `BufTest.java`

**Interfaces — Produces:**
- `final class Buf { Buf(); Buf(byte[]); int readVarInt(); void writeVarInt(int); String readString(); void writeString(String); UUID readUuid(); ... byte[] toByteArray(); int readableBytes(); }`
- `final class FrameCodec { void setCompressionThreshold(int); byte[] encode(byte[] packet); Optional<byte[]> decode(InputStream) ; }` — `decode` returns the decompressed `id+payload` of the next frame, empty on EOF.

- [ ] Tests: VarInt round-trip at 0, 127, 128, 2^31-1, -1 (5 bytes); frame at `size == threshold` is compressed, `threshold-1` is not; decode of a frame with `dataLength=0` yields the raw packet.
- [ ] Implement; run `./gradlew :velocity-protocol-conformance:test`; commit.

### Task 2: Versions and packet tables

**Files:** `.../harness/protocol/{Phase,Direction,PacketTable,PacketTables,MojangNames}.java`, `.../harness/vanilla/{VersionManifest,VanillaVersion}.java`
- `PacketTable { OptionalInt id(Phase, Direction, String name); Optional<String> name(Phase, Direction, int id); Set<String> names(Phase, Direction); }`
- `PacketTables.fromPacketsJson(JsonObject)` and `PacketTables.fromMinecraftData(JsonObject protocolJson)`; the latter maps Prismarine names through `MojangNames` (unmapped names kept as `prismarine:<name>`).
- `VanillaVersion(String id, int protocol, int javaMajor, URI serverUrl, String sha1)`.

- [ ] Tests from small JSON fixtures in `src/test/resources`: packets.json lookup both ways; minecraft-data mapper parsing (`"0x1f": "keep_alive"` → `minecraft:keep_alive`).
- [ ] Implement; test; commit.

### Task 3: Vanilla server runner and RCON

**Files:** `.../harness/vanilla/{VanillaDistribution,VanillaServer,Rcon,JavaRuntimes}.java`
- `VanillaDistribution.resolve(String id) -> VanillaVersion`, `.serverJar(VanillaVersion) -> Path` (sha1-keyed cache), `.reports(VanillaVersion) -> Optional<Path>` (data generator output, cached by sha1), `.packetTable(VanillaVersion) -> PacketTable`.
- `VanillaServer.start(VanillaVersion, Path dir, int port, int rconPort) -> VanillaServer`; `Rcon rcon()`; `close()`. `level-type` = `FLAT` below 1.19, `minecraft:flat` from 1.19. Deadline 180 s for `Done`.
- `JavaRuntimes.forMajor(int) -> Path` — smallest configured JDK ≥ major; configured by system property `conformance.jdks` (path list).

- [ ] Test `Rcon` against an in-test fake RCON server (auth + one command round-trip).
- [ ] Implement; smoke by starting 1.21.11 manually through a throwaway main; commit.

### Task 4: Trace and tap relay

**Files:** `.../harness/trace/{TraceEvent,Trace,Side}.java`, `.../harness/tap/{TapRelay,PhaseTracker}.java`
- `record TraceEvent(long nanos, Direction dir, Phase phase, int id, byte[] payload)`; `Trace.add`, `Trace.events()`, `Trace.write(Path, PacketTable)`.
- `PhaseTracker(PacketTable, int protocol)` — consumes `(Direction, id, payload)` in order; tracks handshake next-state, login compression, login→config/play, config↔play.
- `TapRelay(int listenPort, InetSocketAddress backend, VersionResolver)`; records two `Trace`s (serverbound P→B, clientbound B→P) per accepted connection.

- [ ] Test: loopback relay between two sockets forwards bytes unchanged and records a framed packet in each direction, including after a SetCompression.
- [ ] Implement; test; commit.

### Task 5: Fake session service and encryption

**Files:** `.../harness/auth/{FakeSessionService,MojangHash}.java`
- `FakeSessionService.start() -> FakeSessionService`; `URI hasJoinedUri()`; `URI joinUri()`; `void join(String name, String serverId)` (in-process, as the client), `GameProfile profileFor(String name)`.
- `MojangHash.serverId(String base, byte[] secret, byte[] publicKey) -> String` (signed two's-complement hex).

- [ ] Tests: `MojangHash` vectors — `sha1("Notch")` → `4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48`, `"jeb_"` → `-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1`, `"simon"` → `88e16a1019277b15d58faf0541e11910eb756f6`; `hasJoined` returns 204 before join, 200 with matching profile after.
- [ ] Implement; test; commit.

### Task 6: Scripted client

**Files:** `.../harness/client/{ScriptedClient,ClientSession,Serverbound}.java`
- `ScriptedClient.connect(InetSocketAddress, VanillaVersion, PacketTable, ClientOptions) -> ClientSession`; `ClientOptions(String name, boolean online, FakeSessionService auth)`.
- `ClientSession`: `Trace received()`, `Trace sent()`, `void send(String name, Consumer<Buf> body)`, `awaitPlay(Duration)`, `awaitMarker(String, Duration)`, `Optional<String> disconnectReason()`, `close()`.
- Auto-responses: keep_alive, ping→pong, accept_teleportation, chunk_batch_received (rate 20), client_tick_end, player_loaded, select_known_packs (echo), finish_configuration ack, configuration_acknowledged, login_acknowledged.
- `Serverbound` holds version-gated encoders for the scripted packets (chat, chat_command, client_information, move_player_pos_rot, swing, set_carried_item, client_command, custom_payload, command_suggestion, use_item).
- Status probe: `ScriptedClient.status(...) -> StatusResult(json, pingEchoOk)`.

- [ ] Verified live in Task 9; commit.

### Task 7: Differ, rules, command tree

**Files:** `.../harness/diff/{Differ,Finding,FindingKind,ProxyRules,VelocityRules,BungeeRules,CommandTree,Myers}.java`
- `Differ.compare(Trace source, Trace sink, Direction, PacketTable, ProxyRules, int protocol) -> List<Finding>`; comparison per phase epoch.
- `record Finding(FindingKind kind, Phase phase, Direction dir, String packet, int sourceIndex, int sinkIndex, String detail)`; `FindingKind { MISMATCH, DROPPED, INJECTED, REORDERED, TOLERATED }`.
- `ProxyRules` methods: `Optional<String> tolerateMismatch(ctx, src, sink)`, `tolerateDrop`, `tolerateInjection`, `tolerateReorder` — each returns the justification when tolerated.
- `CommandTree.decode(byte[] payload, int protocol, ArgumentRegistry) -> CommandTree`; `boolean isSupersetWithRootLiterals(CommandTree base, Set<String> allowed)`.

- [ ] Tests on synthetic traces: identical → no findings; one byte flipped → MISMATCH with offset; removed packet → DROPPED; swapped → REORDERED; brand rewrite tolerated by `VelocityRules` only.
- [ ] Implement; test; commit.

### Task 8: Ledger, coverage, reporter

**Files:** `.../harness/report/{Ledger,Outcome,Coverage,Reporter,VersionRange}.java`, `protocol-conformance/ledger.toml`
- `VersionRange.parse(">=1.20.2" | "*" | "1.8-1.12.2")`, ordered by protocol number.
- `Ledger.load(Path)`; `Expectation expectation(String scenario, VanillaVersion)`; `Set<String> unreachable(VanillaVersion)`.
- `Outcome.of(expected, observed)` → `OK | REGRESSION | UNEXPECTED_PASS | STILL_BROKEN | ERROR`.
- `Reporter` writes `summary.md`, `junit.xml`, per-cell `*.trace`.

- [ ] Tests: version range parsing/containment; outcome matrix (4 rows of the design table + error).
- [ ] Implement; test; commit.

### Task 9: Scenarios and orchestration

**Files:** `.../harness/{Main,Suite,SuiteConfig}.java`, `.../harness/scenario/{Scenario,StatusScenario,ExerciseScenario,OnlineLoginScenario,AuthRejectScenario,RconScript}.java`, `.../harness/proxy/{ProxyUnderTest,VelocityProxy,WaterfallProxy}.java`
- `Main --proxy-jar P --proxy-kind velocity|waterfall --versions a,b --work DIR --report DIR --ledger FILE [--gate]`.
- Per version: start vanilla, relay, session service, proxy; run scenarios; diff; coverage; stop all.
- `RconScript` entries carry `VersionRange` and command text; RCON failures are logged, not fatal.

- [ ] Live: run against Velocity `shadowJar` for 26.3, 1.21.11, 1.20.4, 1.16.5, 1.12.2, 1.8.9; every finding is either a real proxy defect (ledger row) or a justified rule.
- [ ] Commit.

### Task 10: Static packet id conformance

**Files:** modify `VanillaDataGenerator.java` (also extract `packets.json`), `VanillaData.java` (`packets(ProtocolVersion)`); create `PacketIdConformanceTest.java`, `VelocityPacketNames.java`.
- Test: for each version with `packets.json`, each `(StateRegistry, Direction)` and each class in `packetClassToId`: `vanillaId(name) == velocityId`; unmapped class fails.

- [ ] Run `./gradlew :velocity-protocol-conformance:conformanceTest`; fix the name table; record real divergences; commit.

### Task 11: Gradle tasks and weekly CI

- `vanillaConformance` (JavaExec, depends on `:velocity-proxy:shadowJar`, JDKs from `javaToolchains` 17/21/25, versions from `SUPPORTED_VERSIONS` via a small `ListVersions` main in `conformance`), `harnessJar` (self-contained).
- Workflow: `vanilla` job matrix over version shards; upload `build/conformance-report`; append `summary.md` to `$GITHUB_STEP_SUMMARY`.

- [ ] Run task locally for two versions; commit; push branch to `electronicboy/Velocity`.
