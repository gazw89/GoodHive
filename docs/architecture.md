# GoodHive architecture: how the app works under the hood

A map for agents and humans: where things live, how data and commands flow, and the invariants that
aren't obvious from reading one file. It covers **runtime mechanics only**.

- **Rules** (UI, deploy, load-bearing Hive facts) live in [`CLAUDE.md`](../CLAUDE.md). It wins on any
  conflict. Fix this doc, don't fork the rule.
- **Hive API fields and payload shapes** live in [`hive-domain-reference.md`](hive-domain-reference.md)
  §5. This doc links to them rather than restating them.
- **Status-ladder / Heat on Demand feature design**:
  [`heat-on-demand-and-status-ui.md`](heat-on-demand-and-status-ui.md).

Last verified against the code: 2026-10-21 (Hive write log; household outbound write gap and longer command confirmation). When you change a mechanism
described here, update this doc in the same commit. Symbols are named rather than line-referenced, so
search for them.

## Mental model

1. The phone talks **directly** to AWS Cognito (auth) and Hive Beekeeper (data). There is no
   GoodHive backend.
2. One read: `GET nodes/all` → `toHeatingZones()` → Room `heating_zone_cache`. **The UI reads only
   from Room**, so launch renders instantly from cache.
3. Every write is a durable row in `pending_command`, created before any network call. A `2xx` is not
   success. A command is `CONFIRMED` only when a *later* poll shows the expected state.
4. While a command is in flight, the UI shows the user's intent optimistically. Activity and status
   are derived from that adjusted state.
5. Temperature **history** has two paths. `TemperatureHistoryRepository` fetches windows on demand
   (in-memory cache only, used by the room screen). `HistorySyncRepository` stores raw 5-minute
   readings in Room for Reports, filled by `HistorySyncWorker`. Both go through one `HistoryFetcher`.
   GoodHive never *samples* temperatures itself: it only copies what Hive reports. The app also keeps
   its own `zone_state_log` (mode/target/HoD changes), because Hive has no such history.

## Codemap (`app/src/main/kotlin/com/gazw89/goodhive/`)

| Package | Responsibility | Key symbols |
|---|---|---|
| `auth/` | Cognito SRP login, SMS MFA, refresh, device remembering, encrypted storage | `AuthRepository` (only entry point), `CognitoSrpClient`, `SrpCrypto`, `TokenStore`, `CredentialStore`, `HiveAuthException.isDefiniteSessionRejection`, `TokenLifecycleLog` |
| `data/network/` | Retrofit API and raw JSON → domain mapping | `HiveApiService` (`getAllNodes`, `setState`, `getTemperatureHistory`), `HiveHistoryResponse`, `HiveNodesResponse`/`HiveNode` (props/state kept as `JsonObject`), `toHeatingZones()`, `activityProbeLines()`, `HiveWriteLoggingInterceptor` (Beekeeper POSTs only; `sanitizeHiveBody`) |
| `data/model/` | Pure domain types and derivations, no Android deps | `HeatingZone`, `WeekSchedule`/`ScheduleSlot`, `HeatingMode`/`ProductType` (raw Hive strings), `householdHeating()`, `activity()`, `ZoneActivity`, `canControl`, `deviceHealth`, `TemperatureHistory.kt` (`TemperatureReading`, `HistoryResolution`, `HistoryAggregate` (AVG/MAX/MIN), `summary()`, `mergeSourceReadings()`) |
| `data/db/` | Room DB `goodhive.db` | `AppDatabase` (v12), entities in `Entities.kt`, DAOs in `Daos.kt`, `DatabaseMigrations` |
| `data/repository/` | Refresh policy, write payloads, command reliability, history reads, Beekeeper write log | `HeatingRepository` (refresh, `setState`, top-level `build…Payload` / `expectedStateFor…` fns), `CommandExecutor`, `HiveRequestRunner` (token + one 401 refresh for every Beekeeper call), `HiveRequestLogRepository` (30-day `hive_request_log`), `HistoryFetcher` (the single fetch-one-window function: serialization, 404 retry, error mapping), `TemperatureHistoryRepository` (on-demand reads), `HistorySyncRepository` (Room store: backfill, top-up, `readings()`, `observeStoredStats()`, `clearStoredHistory()`), `ZoneStateLogger` (state log + schedule snapshots) |
| `data/prefs/` | Theme mode, dynamic colour, Reports options (DataStore `goodhive_settings`) | `SettingsStore` (the DataStore, `SettingsKeys`: every stored key must be listed there or `DropUnknownSettings` deletes it on start), `ThemePreferences`, `ReportPreferences` (`coldNightThresholdC` Flow, default 16, 12-18 in 0.5 steps) |
| `work/` | 15-min background poll and command resumption; history sync | `PollingWorker` (scheduled in `GoodHiveApplication`), `HistorySyncWorker` + `HistorySync` (enqueue/cancel; unique work `goodhive_history_sync`, `KEEP`) |
| `di/` | Hilt singletons: `Json`, `OkHttpClient`, Retrofit, Room, DAOs | `NetworkModule`, `DataModule` |
| `ui/` | Compose screens, each with a `@HiltViewModel` | `navigation/GoodHiveNavHost` (routes), `dashboard/`, `detail/` (design B: `ZoneHeadline`, `TargetStepper`, `TodayTemperatureCard`), `schedule/`, `login/`, `settings/` (incl. the Reports section: cold-night threshold dialog, stored history size, Sync now, Clear stored history; Hive writes list, filters, and detail), `components/` (incl. `chart/TemperatureChart` + pure `ChartGeometry`, reusable by reporting), `theme/` |
| `util/` | Time (Europe/London), schedule now/next and day steps, name joining | `GoodHiveTime` (`londonStartOfDay`, `minutesSinceLondonMidnight`), `scheduleNowNextLater()`, `stepsFor()`/`ScheduleStep`, `joinNames()`, `hodFiringReason()` |

## Read path: refresh and polling

`HeatingRepository.refresh(force, notBeforeEpochMillis)` is the only place that reads from Hive.
- **Single-flight.** A mutex plus `refreshGeneration` means concurrent callers share one `nodes/all`.
  A caller that waited on someone else's fetch reuses its result, or its error.
- **Reuse window.** Within `MIN_REFRESH_GAP_MS` (8 s) of a success, a non-`force` call returns
  `false` with no network round-trip. `notBeforeEpochMillis` lets `CommandExecutor` demand an
  observation that *started after* its write.
- **403/429 backoff.** Waits 15 s, doubling up to 120 s, or longer if `Retry-After` says so. While
  backing off, every call throws `HiveRateLimitedException`, including `force` calls. A 403 here is
  Hive's CDN rate-limiting, **not** an auth failure.
- **Snapshot is authoritative.** `applySnapshot` overwrites every listed product. Hive omits fields
  such as `state.autoBoost` to mean "off", so merging with the old cache would keep stale values.
  Products missing from a snapshot get `reportedByHive = false` and are kept until the user removes
  them (`removeUnreportedDevice`, local only).
- **Debug builds** log whitelisted activity fields on change under tag `HiveProbe`.

Who polls (keep it to exactly this, because stacked loops trigger Beekeeper 403s):

| Caller | Cadence |
|---|---|
| `DashboardViewModel` | 25 s loop, running only while `HeatingRepository.foregroundActive` is true (`MainActivity.onStart/onStop`). The dashboard stays on the back stack under detail screens, so this one loop covers them too. |
| `DeviceDetailViewModel` | One non-forced refresh on open. `todayHistory` is `TodayHistoryLoader`: it observes today's readings in the local store (live as the sync lands data), asks `HistorySync.enqueueNow()` for a top-up when `isSyncDue()` on open and on resume after more than 10 min, and uses the on-demand `TemperatureHistoryRepository` read only while the store has nothing for today. **No loop.** |
| `CommandExecutor` | Forced refreshes while confirming a command. |
| `PollingWorker` | WorkManager periodic job, 15 min, needs network. Unique work name `goodhive_background_poll`, policy `KEEP`. After each poll it enqueues `HistorySyncWorker` when `HistorySyncRepository.isSyncDue()` (a source never synced, a backfill still open, or the last attempt over 1 h old). |

History calls are not polls and never run in a loop. Callers fetch on a user action or screen open
(see "History reads").

### History reads

Two readers share `HistoryFetcher.fetchOnce`/`fetchWithRetry`. Live facts are in
hive-domain-reference §5.
- **Endpoint:** `GET history/heating/{id}`, returning `{data:[{date:"<epochMs string>", temperature}]}`.
  It works on accounts without Hive+.
- **Which id:** `HeatingZone.historySourceIds`. A thermostat uses its `heating` product id. A TRV
  uses its physical `trv` device ids from `props.trvs`; a `trvcontrol` product id returns 404.
  Multi-valve rooms list several ids. On-demand callers take the first. The store syncs all of them
  and `HistorySyncRepository.readings()` merges a room's sources to the mean per 5-minute bucket
  (`mergeSourceReadings`, pure).
- **`HistoryFetcher`:** every request holds one mutex (so the on-demand and sync paths never overlap),
  points with a null temperature or bad date are dropped, and results are de-duplicated and sorted.
  Callers leave about 1 s between windows. Errors are `HistoryException`:
  - 404 → `OutOfRange`, **only after** two retries (5 s, 30 s; the mutex is released while waiting),
    because Hive returns transient 404s for valid windows.
  - 403 with the SigV4 "Authorization header requires 'Credential'" body → `UnsupportedSource`.
    That's API Gateway's reply for an unknown route, **not** auth or a rate limit.
  - Any other 403, or a 429 → `RateLimited`. It's kept separate from the `nodes/all` backoff.
- **On-demand (`TemperatureHistoryRepository.readings`):** chunks ranges longer than 60 days (75 worked
  live, 90 returned 404), with an in-memory LRU. A window that ends before London midnight is
  immutable and cached for the process lifetime; one that includes now has a 5-min TTL.
- **Stored (`HistorySyncRepository.sync`)**, run only by `HistorySyncWorker` (Android blocks the app's
  network when the phone is locked and no job is running):
  - Raw `FIVE_MINUTES` + `AVG` readings go into `history_reading(sourceId, epochMs)`; upserts make
    overlapping windows harmless.
  - Pass 1 gives every source its latest 60 days (first run) or a top-up from `newestMs − 2 h`. Pass 2
    backfills newest-first in 60-day chunks to now − 450 days. Each chunk and its `history_sync` bounds
    (`oldestMs`, `newestMs`, `backfillDone`) commit in one transaction, so a killed job resumes at
    `oldestMs`.
  - A 404 that survives the retries splits the window in half, recursively, down to 7 days. A 7-day
    window that still 404s is skipped and noted in `history_sync.lastError`. A 404 for a window ending
    more than ~440 days ago is the retention edge: the source's backfill is finished. Unsupported
    sources are marked (`lastError = "unsupported"`) and skipped from then on. Other 403 and 429 stop
    the run with `RETRY_LATER`, which the worker turns into a WorkManager retry, capped at three
    retries after the initial attempt. Progress survives retries and process death. Maintenance
    cancels and joins the active sync before deleting readings instead of waiting for a full backfill.
- **Schedule overlay:** Hive only exposes the **current** schedule. A past day drawn with
  `stepsFor(date)` shows today's schedule, not the one that applied then. `schedule_snapshot` records
  schedules from the day it shipped.

- **Room detail "Today" chart (`TodayHistoryLoader`):** reads `HistorySyncRepository.readings(historySourceIds,
  London midnight, ∞)`, so a multi-valve room is the mean of its valves. If that is empty (before the first
  backfill) it does one on-demand fetch of the first source, then switches to the store as soon as the
  sync writes readings. A failed fallback shows "unavailable" but never blanks a chart that already has
  readings. The ViewModel re-observes after London midnight.
- **Settings (Reports section):** `SettingsViewModel` shows `HistorySyncRepository.observeStoredStats()`
  (row count, estimated reading storage at 120 bytes a row including index overhead, newest
  `history_sync.lastAttemptMs`; not measured DB/WAL file size). "Sync now" calls
  `HistorySync.enqueueNow()`; "Clear stored history" (confirm dialog) calls `clearStoredHistory()`, so the
  next sync backfills again.

### State log

`HeatingRepository.fetchAndPersist` hands every snapshot to `ZoneStateLogger.record`. It appends a
`zone_state_log` row (mode, target, derived `scheduleOverride`, user `boostActive`, `hodActive`,
`working`, `online`, battery) when a value changes or at least five minutes have passed, and a
`schedule_snapshot` only when `canonicalizeWeekSchedule` changes. The latest values are held in
memory (seeded once from Room), and a logging failure never fails the refresh. `scheduleOverride` is
derived (SCHEDULE mode and a target more than 0.1 off the current slot) because the zone cache does not
store Hive's `props.scheduleOverride`. These are observed checkpoints, not proof of continuous
state between polls. Reports cap each observation's estimated coverage at five minutes; overnight
gaps remain unknown.

**Reads are retried too.** `HeatingRepository.fetchAndPersist` retries `nodes/all` twice (1 s, 3 s) on IO errors, 408 and
5xx, since replaying a read is free. 403/429 stay with the refresh backoff. History reads retry 404s in
`HistoryFetcher`; other history failures retry via `HistorySyncWorker`. Every Hive write goes through `CommandExecutor`.

## Write path: the command reliability layer

```
ViewModel ── build…Payload() + expectedStateFor…() ──▶ CommandExecutor.enqueue()
   │  (local optimistic override)                         │ insert pending_command (PENDING), supersede older
   ▼                                                       ▼
uiState ◀── CommandExecutor.observeZones() ◀── Room ◀── process(): SENDING ─▶ CONFIRMING ─▶ CONFIRMED | FAILED
```

- **Payloads.** Always use the top-level builders in `HeatingRepository.kt`. Each one has a matching
  `expectedStateFor…`. The live-confirmed body rules (OFF + frost target, SCHEDULE + the current slot target (never a stale one), HoD
  enable/disable shapes, cancel-boost restoring `props.previous`) are documented on those functions
  and in CLAUDE.md.
- **Superseding.** A new command supersedes active ones for the same zone **and** the same
  `commandGroup()`: `heating` (mode/target/boost), `heatOnDemand` (`autoBoost`/`zone` keys), or
  `schedule`. A target tap doesn't cancel a pending HoD toggle.
- **`process(commandId)`** runs in the executor's own `SupervisorJob` scope on IO:
  - Commands older than `MAX_RESUME_AGE_MS` (15 min) are not sent. One fresh poll runs first: a match
    is `CONFIRMED` with no banner, and a mismatch fails as expired.
  - **Observe-first** for resumed commands (status past PENDING) and for every HoD command. If a fresh
    poll already matches, finish without sending. This is the no-op skip that avoids HoD 400s on
    re-enable.
  - **Send**, with up to 5 attempts (`SEND_DELAYS_MS` 0/5/15/30/60 s). Every `setState` goes through
    one household gate (`outboundMutex`, `OUTBOUND_GAP_MS` = 5 s): the next write waits until 5 s
    after the previous one **started**, and never overlaps it. A rejected POST still occupies the gap.
    A command superseded while waiting does not start a new one. A Hive `Retry-After` of at least 5 s
    is not added on top of the gap. After any error the executor re-polls first, because the write may
    have applied. Only `isRetryable()` errors are retried (408/429/5xx/IO). A `400` fails immediately.
    `Retry-After` over 120 s fails the command.
  - **BOOST resends send the minutes left**, not the original duration (`boostPayloadForSend`), so retries and
    resumed boosts never push the end time later. Once the boost time has passed, the command fails.
  - **Confirm** by polling with `CONFIRM_DELAYS_MS` (about 280 s), checking
    `matchesExpectedState`. It compares only the keys `target` (±0.1), `mode`, `autoBoost` (missing ≡
    DISABLED) and `schedule` (`canonicalizeWeekSchedule`). An unknown key never matches. These polls
    are reads; they are not spaced by the outbound gate.
  - Timeout → `FAILED` with **no blind resend**. The user retries via `retryLatestFailed`.
- **Outcomes** are written to `command_log`. Unresolved `FAILED` rows from the last 15 min drive the
  dashboard banner and the detail/schedule retry and dismiss affordance (`dismissFailuresForZone`).
- **Resumption.** `resumeInFlightCommands()` runs on `DashboardViewModel` init and in every
  `PollingWorker` run. It keeps only the newest command per (zone, group).
- **Two optimistic layers:**
  1. `CommandExecutor.observeZones()` folds every active command's payload into its zone
     (`applyPendingState`). Intent survives navigation and process death.
  2. Each ViewModel keeps short-lived local overrides (`optimisticTargets`/`optimisticMode`/…)
     guarded by `changeVersions`. They bridge the moment before the Room row exists and are cleared
     when `awaitSettled` returns, not on a timer.
  - `householdHeating()`/`activity()` are computed **after** both layers, so the status ladder reacts
    to a tap instantly.

**Adding a new write type:**
1. Add a payload builder and an `expectedStateFor…` that uses only keys `matchesExpectedState`
   understands, or extend it.
2. Teach `applyPendingState` the optimistic effect.
3. Check that `commandGroup` classifies it correctly.
4. Enqueue it from the ViewModel through its existing optimistic wrapper (`submitChange` in the
   detail and dashboard ViewModels, `enqueueScheduleWrite` in `ScheduleViewModel`), never through
   `HeatingRepository.setState`.
5. Add payload and expected-state tests (see `ModePayloadTest`, `HeatOnDemandPayloadTest`).
6. Verify live that Hive applies it, because a `200` with the wrong body is common.

## Auth and session

- **Pool discovery.** `CognitoSrpClient.discoverPoolConfig()` scrapes `https://sso.hivehome.com/` for
  the current Cognito pool and client IDs and caches them in memory. Cognito calls go to
  `cognito-idp.<region>.amazonaws.com`.
- **Login.** Password SRP → optional SMS MFA (`LoginOutcome.SmsCodeRequired`) → `ConfirmDevice` when
  Cognito returns `NewDeviceMetadata`. That stores a `RememberedDevice` so later refreshes send
  `DEVICE_KEY`. **Without ConfirmDevice, Hive rejects refresh after about 1 h.** The email and
  password are saved (`CredentialStore`) only after full success, to prefill the next login.
- **Tokens.** `AuthRepository.currentIdToken()` refreshes under a mutex once 90 % of the ID-token
  lifetime has passed (`TokenStore.shouldRefresh`). If `REFRESH_TOKEN_AUTH` is definitively rejected,
  it falls back to `DEVICE_SRP_AUTH`. Storage is `EncryptedSharedPreferences`.
- **401 handling.** `HeatingRepository.withTokenRecovery` retries once with
  `refreshAfterRejection(token)`. That refreshes only if nobody else already has, so a burst of 401s
  costs one refresh. Other codes, 403 included, are rethrown.
- **Session expiry UX.** Only the foreground decides. `DashboardViewModel` calls
  `markSessionNeedsReauth()` after 2 consecutive definite rejections. That clears the access/id
  tokens but keeps the refresh token, device and credentials, then navigates to login.
  `PollingWorker` never signs the user out; on a definite rejection it returns `failure()`.
  `signOut()` clears tokens, invalidates in-flight authentication and starts account cleanup in a
  repository-owned scope. `AccountDataCleaner` cancels history work and command processing, drains
  readers/refreshes, wipes all account tables and resets in-memory state. Login awaits cleanup and
  fails closed on a wipe error. With no persisted refresh token, login repeats cleanup after a
  process restart; changing the saved username also requires cleanup. Saved login credentials remain
  available for the owner's next login.
- **Start destination.** `NavStartViewModel` opens the dashboard if `hasRefreshToken()`, otherwise
  login.

## Derived state

`data/model/HeatingActivity.kt` and `DeviceHealth.kt` are pure functions over `List<HeatingZone>`.
The domain rules behind them are in hive-domain-reference §5. The non-obvious mechanics:
- `householdHeating()` reads the **first** reported `heating` product. If that product can't be
  controlled, the household is unknown (`boilerOn = null`).
- `activity()` is first-match-wins: offline → off → HOD_BOOST (checked **before** BOOST, because HoD
  sets `mode=BOOST`) → HOD_SOURCE → BOOST → calling → WAITING_FOR_BOILER → HEATING → IDLE.
- Hive's `props.working` is authoritative. The `current < target − 0.1` heuristic applies only when
  `working` is null.
- `canControl = reportedByHive && online && batteryLevel != 0`. A 0 % battery beats online flags.

## Persistence and migrations

- **Tables:**
  - `heating_zone_cache`: one row per zone, the UI's source.
  - `pending_command`: durable writes.
  - `command_log`: outcomes.
  - `room_order`: dashboard drag order. TRVs only; the thermostat is always pinned first.
- `exportSchema = true`, and schemas are written to `app/schemas/` (keep them committed).
  `DatabaseMigrations.ALL` migrates **every** version 1–7 straight to 8 by rebuilding the cache table.
  Its column list and DDL are deliberately **frozen at v8**.
- **v9** adds `historySourceIdsJson` (`MIGRATION_8_9`, an `ALTER TABLE … ADD COLUMN`). Room chains 1–7 → 8 → 9.
- **v10** (`MIGRATION_9_10`, creates tables only) adds the Reports tables:
  - `history_reading(sourceId, epochMs, tempC)`, primary key `(sourceId, epochMs)`. Room 2.8 can't
    declare `WITHOUT ROWID`, so this is an ordinary table.
  - `history_sync(sourceId, oldestMs, newestMs, backfillDone, lastAttemptMs, lastError)`.
  - `zone_state_log(id, zoneId, epochMs, mode, targetC, scheduleOverride, boostActive, hodActive, working, online, battery)`,
    indexed on `(zoneId, epochMs)`.
  - `schedule_snapshot(id, zoneId, fromMs, scheduleJson)`, indexed on `(zoneId, fromMs)`.
  - DAOs: `HistoryDao` (readings + sync, with the atomic `commitChunk`), `ZoneStateDao`.
- **v11** (`MIGRATION_10_11`) drops every table not in the frozen `TABLES_V11` set, removing tables
  left by retired features. `DatabaseMigrationTest` asserts the migrated database holds exactly
  `CURRENT_TABLES`.
- **v12** (`MIGRATION_11_12`, creates the table and its `startedAtEpochMillis` index) adds
  `hive_request_log`: sanitised Beekeeper POST method, path, request body, response code, duration,
  response body, and transport error. `HiveWriteLoggingInterceptor` records only
  `POST beekeeper.hivehome.com` (temperature, mode, boost, schedule, Heat on Demand, including a 401
  retry). Polls, history reads, Cognito, and SSO are not stored. Bodies pass through `sanitizeHiveBody`
  (no Authorization, cookies, tokens, or address-like fields; 64 KB cap). Rows older than 30 days are
  deleted on insert and when the repository is created. Sign-out clears the table with the rest of
  the account database. Settings → Hive writes lists them, with sort and filters, and opens the
  sanitised request and response.
- **Adding v13:** bump the version, then add a new `Migration(12, 13)`, appended to `DatabaseMigrations.ALL`.
  Don't edit the frozen v8 constants, `MIGRATION_9_10`, `MIGRATION_10_11`, `MIGRATION_11_12`, or `TABLES_V11`.
  Extend `CURRENT_TABLES` and `DatabaseMigrationTest` (androidTest, uses the exported schemas). There's no
  destructive fallback: operational data and pending commands must survive upgrades.
- Temperature history **is** stored (user decision, for Reports; see "History reads"). It holds only
  Hive's own readings plus our state log. The old `heating_sample` table (our own sampling) stays
  dropped.
- **Wiping:** account cleanup belongs to `AuthRepository` / `AccountDataCleaner`, not a Settings
  ViewModel coroutine. Sign-out cancels and joins account work and clears all account tables before
  another account may log in. `clearStoredHistory()` clears readings and sync state but
  retains the state log and schedules, which Hive cannot re-supply. Saved credentials survive sign-out.

## Invariants and boundaries (things that must stay absent)

- No GoodHive backend, MCP, or cloud link. Don't add network calls to anything but Cognito, Hive SSO
  and Beekeeper.
- No UI or ViewModel code calls `HiveApiService` or `HeatingRepository.setState` directly. Every
  device write goes through `CommandExecutor.enqueue`.
- Logcat stays at OkHttp `BASIC` (`NetworkModule`). Do not switch that interceptor to `BODY` or
  `HEADERS`: the Authorization header carries a live token. Beekeeper write bodies are stored only in
  `hive_request_log`, after sanitising. Requests must keep the Hive-like `User-Agent`.
- No second poll loop, no automatic resend after a confirmation timeout, and no BOOST replay.
- No history call inside a loop, and no history network traffic outside `HistorySyncWorker` or a
  visible screen's on-demand read. We store only what Hive reports; never infer or synthesize readings.
- No inferring Heat on Demand from temperatures. Read `props.autoBoost.active`.
- No merging a snapshot with the stale cache. A missing field is a real value.
- `data/model/` stays pure Kotlin, which keeps the derivations JVM-unit-testable.

## Build, test, debug

- **Toolchain:** builds need the Android Studio JBR:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- **Commands:**
  - Unit tests (JVM, no device): `./gradlew :app:testDebugUnitTest`.
  - Install: `./gradlew :app:installDebug`.
  - Migration tests (device):
    `ANDROID_SERIAL=<one serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.gazw89.goodhive.data.db.DatabaseMigrationTest`.
    - **It uninstalls the app afterwards, which wipes the Hive sign-in.** Warn the user first.
    - Pin `ANDROID_SERIAL`, because wireless debugging can list one phone twice and parallel runs
      crash.
- **SDK and signing:** `minSdk = targetSdk = compileSdk = 37`. Release is signed with the debug key
  (sideload only).
- **Material 3 pin:** `1.5.0-beta01` is pinned above the Compose BOM in `gradle/libs.versions.toml`
  for the public `LinearWavyProgressIndicator`. Move to stable 1.5.x when it ships.
- **Test doubles:** repository tests use the fakes in `RepositoryTestFixtures.kt` (`FakeHiveApi`,
  `FakeZoneDao`, `FakePendingDao`). No real account or network. Mapper tests use anonymised JSON
  shaped like the live payloads.
- **Logcat tags:** `adb logcat -s GoodHiveSync GoodHiveAuth GoodHiveTokens HiveProbe`. `HiveProbe`
  appears in debug builds only.
- **Device:** follow CLAUDE.md "Device deploy & verification". Live heating changes must be reverted
  afterwards.

## Where do I change…

| Task | Touch, in order |
|---|---|
| Read a new Hive field | `toHeatingZoneOrNull` in `HiveMapper.kt` (defensive `as?`/`…OrNull`, never throw) → `HeatingZone` → `HeatingZoneCacheEntity` + a new Room migration → `toCacheEntity`/`toDomain` → `HiveMapperTest`/`ZoneCacheMappingTest`. Record live confirmation in hive-domain-reference §5. |
| A history-based view or report | `HistorySyncRepository.readings(sourceIds, from, to)` (stored, merged across a room's valves) → pure aggregation in `data/model/` with tests → UI. For a one-off window not in the store use `TemperatureHistoryRepository.readings`. Use `HeatingZone.historySourceIds`, never product ids for TRVs. |
| Room detail layout | `DeviceDetailScreen` (controllable branch) → `ZoneHeadline` (pure copy, `ZoneHeadlineTest`) / `TargetStepper` / `TodayTemperatureCard`. The chart's x-axis is London **wall-clock** minutes, so readings line up with `stepsFor()` on DST days. |
| A new device write | See "Adding a new write type" above. |
| A new zone status | `ZoneActivity` + `activity()` order → `HeatingActivityTest` → `ui/components/HeatStatus.kt` and the dashboard and detail screens. |
| Polling cadence or rate limiting | `DashboardViewModel` loop, `HeatingRepository` constants, `CommandExecutor` outbound gap (`OUTBOUND_GAP_MS`), `GoodHiveApplication` worker schedule. Keep the single loop. |
| Auth or session behaviour | `AuthRepository` only. Callers never touch `TokenStore`. |
| Schedule editing | `ScheduleViewModel` (always writes the **full week** via `buildSchedulePayload`), `ScheduleScreen`, `ScheduleCopySheets`, `util/ScheduleNowNextLater.kt`. |
| A new screen or route | `GoodHiveNavHost` (`Routes`), with a `@HiltViewModel` that reads `zoneId` from `SavedStateHandle`. |
| Colours, type, motion | `ui/theme/` only (`Color.kt`, `TemperatureVisuals.kt` for `ColorScheme.heat…` and other extensions, `Motion.kt` for `GoodHiveMotion`/`LocalReducedMotion`). Follow the CLAUDE.md UI rules. |
| The HoD "Stop boost" target | `ui/detail/StopBoostTarget.kt` (`stopBoostTargetFor`), `DeviceDetailViewModel.stopHeatOnDemandBoost`. |

## Reports UI

`ui/reports/ReportsScreen` and `RoomReportScreen` render the stored history through
`ReportsDataSource`. Their public `ReportsContent` and `RoomReportContent` functions take only state
and callbacks, allowing the debug fixture activity to render without an authenticated account.
`GoodHiveNavHost` shows Material 3 Rooms/Reports navigation only on those roots. The dashboard stays
on the back stack under Reports, preserving the existing single foreground refresh loop. Detail,
schedule, settings, about and login retain full-screen navigation. Navigation respects reduced motion.

`ReportsViewModel` enqueues the unique `HistorySyncWorker` on resume only when sync is due. Retry
uses the same `KEEP` job. No additional polling loop or direct history network call exists in Reports.
Room/source progress and recorded sync errors remain visible alongside usable partial readings.
Both ViewModels observe Room, recalculate off the main thread, refresh their window on resume, and
save period selection in `SavedStateHandle`. `ReportsDataSource` observes state logs and schedule
snapshots as Flows; logging changes update target estimates without waiting for temperature history.
Each state observation supports at most five minutes, matching the logger heartbeat; longer gaps stay
unknown rather than extending an unchanged mode across an unobserved day. Logged overrides/boosts
are excluded from scheduled-target scoring.
Live temperature-only changes do not restart all history queries.

Periods use London calendar dates: Week starts Monday, Month/Year start on their calendar boundary;
current periods end after today. Current-day readings appear in the chart/table as partial, but
comparisons exclude today. `ReportEvidence.matchedComparison` matches reasonably sampled dates on
both sides (same weekday offset for Week/Month; same calendar date for Year), excluding leap-day
aliases and unmatched availability. A reasonably sampled day has readings in at least 75% of its
hours; this does not mean fully observed. Missing rooms mark a household day partial. Year comparison
lines use matching observed dates in each month; months lacking comparison data retain descriptive
current values. Missing values remain absent, never zero. Lines split at missing dates; isolated
points remain visible. Each chart has an accessible table alternative.

The brief is deterministic template text. Only descriptive cold-night and
missing-reading findings appear. Warm-up/cooling advice and low-target-share performance alerts are
suppressed pending validated active-heating evidence. “Readings near scheduled target” is always a
sample-based estimate within ±0.5° of comfort targets of 17° or above, using current schedules when
history is absent. Rooms currently outside SCHEDULE are unscored. A low share can mean above target;
it is not a heating-performance score. Cold-night evidence requires a finished London 22:00–07:00
window with readings spread over at least seven hours. The configurable cold-night threshold is read
from `ReportPreferences` in both screens.

The scrollable About sheet explains sample averaging, observed lows/highs of returned 5-minute AVG
readings (not exact underlying extrema), equal weighting of available rooms, missing data, retention,
mode/schedule uncertainty and local-only processing. Verification results are in `reports-plan.md`.
