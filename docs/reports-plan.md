# Reports tab: delivery plan

Status: **Reports v1 implemented**, 2026-10-15; verification and limits below. Mock canvas (private to the owner):
https://claude.ai/artifact/Kynq8KaBEA8WRj1SN7UVkS. API facts come from
[`hive-domain-reference.md`](hive-domain-reference.md) §5, which takes precedence if the two disagree.
The original mock is exploratory; the implemented scope below takes precedence.

## 0. Goal and constraints

Use Hive temperature history to show, per home and per room:

- how warm it was;
- how that compares with earlier periods;
- where something looks wrong.

Every number on screen must come from data we really have. Copy says "about" or "estimate" where
the method is approximate.

Hard limits we can't design around:

| Limit | Consequence |
|---|---|
| History is **room temperature only**. No target, mode, `working`, boost or HoD history. | We can't report boiler run-time, energy, kWh or cost. "Time on target" for past periods uses today's schedule unless our own state log covers that period (§3.3). |
| Hive exposes the **current** schedule only. | Same as above. Schedule snapshots start the day we ship. |
| A TRV's sensor sits on the radiator valve. | Valve rooms read warm while heating and fall faster afterwards. Compare valves with valves; never rank a valve room against the thermostat room on cool-down. |
| Hive keeps about 15 months. | Year-on-year comparisons only work from about July 2025 onwards. Once stored locally (§3.1), history grows beyond Hive's window. |
| Android can defer background work and restrict network access while idle. | Durable background history sync uses WorkManager; visible room screens may use a one-off fallback. The earlier DNS failure does not prove all background sockets are always blocked. |

## 1. What we verified live (2026-10-15, owner's account, Pixel 9 Pro XL, API 37)

API probes were read-only GETs through `HistoryProbeReceiver`; later checks used the real history
worker and local database snapshots. No heating controls were changed.

1. **`operation=MIN` works.** Over the same 7 days, daily MIN, AVG and MAX were 19.0 / 19.49 / 20.0
   and 18.8 / 19.44 / 20.3 (thermostat). MIN is now supported by `HistoryAggregate`.
2. **Raw readings over 60 days work.** `MINUTES&rate=5` returned:
   - thermostat: 10,950 points, 487 KB;
   - one TRV: 4,969 points, 219 KB.

   That is about **182 readings a day** for the thermostat and **about 83 a day** for a valve.
   75 days at `DAYS` still works.
3. **Retention.** Data from 450 days ago (July 2025) came back for a TRV. Daily averages from one year
   ago came back for the thermostat.
4. **Transient 404s on valid windows.**
   - One TRV returned `404 NOT_FOUND` for four winter windows (Dec 2025 – Jan 2026), at both
     HOURS and DAYS. December alone and January alone worked.
   - Re-running one of the failed windows 3 minutes later returned **200** with full data.

   The earlier reader treated these as permanent errors. The implemented sync retries and splits
   valid windows before recording a gap (§3.2).
5. **The account has 8 sources:** 1 thermostat and 7 TRVs. Each has one `historySourceIds` entry today.
6. **A locked-phone probe failed to resolve Hive.** With the phone dozing on the
   lock screen, the probe failed with `UnknownHostException`, and the system log showed `Destroyed live tcp sockets for
   uids={app}`. The shell on the same phone resolved Hive fine. This is an observation rather than
   a general Android networking guarantee. WorkManager later downloaded history while the phone was locked.

The 30-day check has now run across all eight sources. Findings and go/no-go decisions are in §8.

## 2. Metric catalogue: what ships, when, and how honestly

All figures describe observed readings, with sampling and coverage limitations. Direct arithmetic
does not make them exact physical measurements or evidence of heating performance.

| Metric | Method | Quality | Phase |
|---|---|---|---|
| Daily low / average / high per room | Extrema and sample mean of returned 5-minute AVG readings, London dates | Observed values; gaps may hide extrema | Shipped |
| Whole-home average | Mean of room daily averages, thermostat included; missing rooms mark a day partial | Descriptive sample average; definition in About | Shipped |
| This period vs the previous one / same period last year | Match reasonably sampled completed dates on both sides | Descriptive comparison over matched coverage | Shipped |
| Typical week (hour × weekday grid) | Mean per London-local hour over N weeks | Exact | 2 |
| Cold nights | Finished 22:00–07:00 nights with samples in at least seven hours and an observed minimum below threshold | Observed cold nights, not proof other nights stayed warm | Shipped |
| Missing readings | Runs with no reading for > 2 h (thermostat) / > 2 h (TRV), cross-referenced with current battery and online state | Exact | 2 |
| Readings near scheduled target | Share of samples within ±0.5° of comfort targets ≥17°; exclude observed overrides; unscored outside current Schedule mode | Always an estimate, not a performance score | Shipped |
| Morning warm-up | Experimental schedule-aligned rise calculation | Active heating unproven; TRV intervals often 15–30 minutes | Withheld |
| Overnight cool-down | Experimental overnight slope | Outdoor conditions and heating history unknown | Withheld |
| "Start earlier" suggestion | Needs validated active-heating evidence and calibrated samples | Current evidence insufficient | Withheld |
| Heat on Demand events per room | Possible observed transitions in the state log | Polling gaps can miss whole events; cannot claim exact counts | Future |
| Boiler run-time, energy, kWh, cost | — | **Not possible.** No data. | Out |
| Heat loss against outdoor weather | Needs an outdoor temperature source (such as Open-Meteo), which sends an approximate location to a third party | Needs a decision (§7) | Out for now |

## 3. Architecture

### 3.1 Store history locally (reverses the "never stored" rule)

That rule existed because nothing used the data. Reports do. Update `CLAUDE.md`, the
`architecture.md` "History reads" and "Invariants" sections, and `hive-domain-reference.md` in the
same PR as the migration.

Room **v10** (`Migration(9, 10)`, added to `DatabaseMigrations.ALL`, with `DatabaseMigrationTest`
extended):

- `history_reading(sourceId TEXT, epochMs INTEGER, tempC REAL, PRIMARY KEY(sourceId, epochMs))`.
  Ordinary Room table with a rowid and composite-key index; not `WITHOUT ROWID`.
  Raw 5-minute readings only. Calculate MIN/AVG/MAX locally, so one fetch mode serves every metric.
- `history_sync(sourceId PK, oldestMs, newestMs, backfillDone, lastAttemptMs, lastError)`.
- `zone_state_log(zoneId, epochMs, mode, targetC, scheduleOverride, boostActive, hodActive, working, online, battery)`.
  Append-only on changes, with an observation checkpoint after five minutes.
- `schedule_snapshot(zoneId, fromMs, scheduleJson)`, written when the canonical schedule hash changes.

Volume estimate at 15 months: about **340k rows** for the observed sampling rate. The original
10–15 MB estimate omitted text-key/index overhead. Budget roughly **40 MB at 120 bytes per reading**;
actual DB/WAL size varies. Settings clearly labels this as an estimate, and offers clear/resync.
Sign-out drains account work and wipes all account tables before another login may complete.

### 3.2 Sync (`HistorySyncRepository` + `HistorySyncWorker`)

**Backfill**
- Per source, newest window first: each 60-day chunk back to `now − 450 d`.
- Each chunk commits atomically and moves `oldestMs`, so a killed job resumes where it stopped.
- 8 sources × 8 chunks ≈ **64 requests**, about 15 MB downloaded once. At about 3 s a request
  plus a 1 s gap, the optimistic estimate was 4–5 minutes. Retries, Android scheduling and network
  conditions can make it much longer; no completion-time guarantee is shown.
- Show progress on the Reports tab. Week and Month are usable after the first chunk of each source
  (about 30 s).

**Top-up**
- Fetch `newestMs − 2 h → now` on Reports open, on room detail open (it then reads `TodayTemperatureCard`
  from Room too), and from `PollingWorker` when the last top-up is older than 1 h.

**Errors**
- **404 inside retention:**
  1. Retry twice with backoff (5 s, 30 s).
  2. Then split the window in half, down to 7 days.
  3. If a ≤ 7-day window still 404s, record a gap and move on.
- **404 older than about 440 days:** treat as the retention edge and stop backfill for that source.
- **403 "Credential":** `UnsupportedSource`.
- **Other 403 or 429:** `RateLimited`. Stop the job and let WorkManager back off, up to three retries
  after the initial attempt. Saved progress survives a failed run and a later explicit retry.

**Runs as**
- A unique `CoroutineWorker` (`goodhive_history_sync`, `KEEP`) with `NetworkType.CONNECTED`.
- The Reports screen enqueues it. Never a bare coroutine (§1 finding 6).

**Contention:** share `HiveRequestRunner` and `networkMutex` with the live reads, so a sync never
starves a user write.

**Multi-valve rooms:** sync every `historySourceIds` entry. A room's series is the mean per
5-minute bucket.

### 3.3 State log (gives us mode history from now on)

`HeatingRepository.refresh()` already sees mode, target, `scheduleOverride`, boost, HoD and `working`
on every refresh. Append a row when any of them changes.

Coverage:
- the existing 25-second foreground refresh loop, with checkpoints at five-minute intervals;
- every 15 min from `PollingWorker`, but Doze defers that to maintenance windows, which can be
  hours apart overnight.

These are observations, not continuous ground truth. Each checkpoint supports at most five minutes
of estimated mode coverage; longer gaps stay unknown. Near-target scoring excludes observed Off,
Manual, Holiday and Boost/override periods and labels schedule fallback as an estimate.

### 3.4 Pure calculations: `data/model/reports/`

No Android dependencies, JVM-tested, consistent with existing rules:

- `DailyStats`
- `compareWindows`
- `HourOfWeekGrid`
- `ColdNights`
- `ReadingGaps`
- `TimeOnTarget`
- `WarmUps`
- `CoolDown`
- `InsightRules`, which returns a ranked `List<Insight>`. Each `Insight` has a type, room, numbers,
  severity and an evidence window.

Test fixtures come from anonymised real readings, including a DST changeover day and a day with
gaps. All day and hour bucketing uses `GoodHiveTime` (Europe/London).

### 3.5 UI: `ui/reports/`

- `GoodHiveNavHost` gains an M3 `NavigationBar` (Rooms | Reports). Detail screens keep no bar.
- `ReportsScreen` (period segmented button, overview) and `RoomReportScreen`.
- New charts beside `TemperatureChart` in `ui/components/chart/`, all static, with semantics
  descriptions and theme roles:
  - `RangeBarChart` (daily low to high);
  - `BarChart` (warm-up minutes);
  - `HourWeekHeatmap`, with cold to neutral to warm built from the `calm`/`heat` roles and both
    light and dark variants.
- Every chart has a "Show as table" alternative.

## 4. Brief

The brief at the top of Reports is deterministic template text built by `ReportsBuilder`: the
sentences of up to three insight cards, or, when there are none, a short summary of the whole-home
average and its comparison. Warm-up, cool-down and advice remain suppressed.

**Privacy:** all calculation happens on the phone. Say so in the "About these reports" sheet.

## 5. Phases and exit criteria

Phase 1 progress (2026-10-15):
- [x] v10 migration and `DatabaseMigrationTest`.
- [x] `HistorySyncRepository` / `HistorySyncWorker`, retry and split policy, repository tests with `FakeHiveApi`.
- [x] `MIN` in `HistoryAggregate`.
- [x] Room detail "Today" chart reads from the store (`TodayHistoryLoader`), with the on-demand fetch as fallback.
- [x] Settings: estimated reading storage and last sync attempt, Sync now, Clear stored history,
  cold-night threshold.
- [x] Docs updated (`architecture.md`, `hive-domain-reference.md`).
- [x] On-device resume after force-stop, preserving readings and cursors.
- [x] Full initial backfill completed for all eight sources: 392,603 readings, zero recorded errors.

Every phase follows the repo's UI checklist and on-device verification.

| Phase | Current scope/status | Remaining validation or gate |
|---|---|---|
| **0. Spike** | Completed: 30-day check for eight sources | Go/no-go decisions in §8; advice remains withheld |
| **1. Store + sync** | Implemented; migration, resume and all eight initial backfills verified | Normal ongoing top-ups; retry/split covered by tests |
| **2. Reports v1** | Implemented: root navigation, Week/Month, room trends/heatmap, observed cold nights/gaps, estimated near-target share, templates | Device visual/table checks below; human TalkBack listening remains follow-up |
| **3. State log + derived insights** | Checkpoints and schedules implemented; sparse intervals remain estimated | Active-heating evidence and long-term calibration required before any advice/event-count claim |
| **4. Year view** | Implemented with matched-date comparisons, absent values and partial coverage labels | Entirely historical comparisons improve as backfill completes |
## 6. Risks

| Risk | Mitigation |
|---|---|
| Hive throttles the backfill (unofficial API) | Serialised, 1 s gap, stop on 403/429 and let WorkManager back off. Backfill only once. |
| Transient 404s | Retry, split, record a gap (§3.2). Never mark retention from one 404. |
| A valve's warm bias misleads comparisons | Cooling/performance recommendations withheld; sensor differences explained in About. |
| Schedule or mode changed in the past | Near-target share always labelled an estimate; observation gaps stay unknown. |
| Storage growth | Budget about 40 MB for 340k readings plus variable WAL/state-log overhead. Estimated storage and clear/resync controls in Settings. |

## 7. Defaults used for v1

1. Cold-night threshold: 16°C, editable from 12–18°C in half-degree steps.
2. Hive-only: no outdoor-weather provider or location sharing added.
3. Year is available with partial coverage labels and matched available dates; unavailable values
   remain absent instead of being presented as zero.

## 8. Spike results

Checked on 2026-10-15 using a local copy of the app's Room store; no raw household readings or
credentials are committed to the repository.

- **Sampling:** 5,343 thermostat readings and 2,092–2,741 per valve over 30 days. TRV median
  intervals were 15–25 minutes, the 95th percentile 30 minutes and the largest interval 40 minutes.
  No gap over two hours appeared in this particular window; this does not prove devices were online.
- **Daily/trend/cold-night/gap calculations: go**, with observed-value and coverage labels. An
  independent Python handcheck for 5–11 October found seven reasonably sampled days per room and
  a whole-home average of **19.8291°C**, displayed as **19.8°C**. The same handcheck found room means
  from 16.8958°C to 21.1646°C. A local-only JVM check then passed the same readings through the
  production `buildReportsState` and all eight `buildRoomReportState` outputs: the household mean,
  seven counted days and every room's displayed average matched. The temporary test was removed;
  household readings were not added as repository fixtures.
- **Warm-up advice: no-go.** Aligning today's schedule with old readings counted passive daytime
  warming as heating: one room's median was about 296 minutes and a currently Off room gave a
  spurious 596-minute sample. Mode and actual active-heating evidence must be validated first.
- **Low near-target score as performance alert: no-go.** Sample shares of 4–38% often reflected
  rooms being above target. The descriptive share may be shown as an estimate; it must not trigger
  a heating-failure or start-earlier recommendation.
- **Cool-down advice: withheld.** Overnight slopes can be calculated but cannot establish insulation,
  draughts, savings or boiler behavior without additional validated evidence.
- **Durability:** force-stop preserved **85,225 readings**, eight cursors and one completed source.
  After relaunch and Android rescheduling, the store reached **95,819 readings** and a cursor moved
  further back. This proves resume progress, not completion of every backfill.
- **Database migration:** all nine emulator migration tests passed, including v9→v10. UI/unit and
  final deployment evidence is recorded with the implementation checkpoint below.

## Reports v1 implementation checkpoint (2026-10-15)

The production Week/Month/Year overview and room drill-down are implemented with root-only
Rooms/Reports navigation, stored Room data, sync/backfill progress and retry, period navigation,
loading/empty/error/partial coverage states, cold-night threshold preferences, chart/table toggles,
and a scrollable About sheet. Week follows Monday–Sunday in Europe/London. The current week shows
elapsed dates and compares only matching completed weekdays; the previous-week view on 15 Oct is
5–11 Oct, ready for the independent real-data handcheck. Stateless screen functions support a
separately committed debug-only fixture activity; no synthetic readings enter production.

The current delivery narrows earlier phase proposals based on calibration and correctness review:

- **No low-share performance alert.** The metric is “Readings near scheduled target”, explicitly a
  sample-based ±0.5° estimate using current schedules where historical snapshots are missing. A room
  above target lowers this share too. OFF/MANUAL and other non-SCHEDULE rooms are unscored.
- **No automated warm-up/cool-down advice.** Current schedules cannot establish active heating in past
  windows. Morning passive warming must not become “start earlier” advice. The corresponding tiles,
  charts and automatic actionable insights remain suppressed until mode/active-heat evidence is
  validated. No control writes occur from reports.
- **Honest comparisons.** Differences match reasonably sampled dates available on both sides and
  exclude today. Household days with a missing room are partial. Reasonably sampled means at least
  75% hourly coverage, not fully observed. Year lines compare matching observed dates within each
  elapsed month; unavailable months stay absent. Winter coverage is explicitly partial when needed.
- **Observed extrema only.** Low/high values are the lowest/highest returned 5-minute AVG readings,
  not exact extrema of underlying measurements. Averages are sample-based, not elapsed-time weights.
  No additional MIN/MAX API traffic is introduced.

Production regression coverage includes Monday boundaries, DST night/window handling, leap-year
matching, missing-room coverage, unmatched dates, current-day exclusion, explicit table gaps,
non-SCHEDULE scoring, threshold preferences and suppressed performance/advice insights. Verification
is reported below; human TalkBack listening and long-term calibration remain follow-up validation.

## Final verification (2026-10-15)

- Integrated `:app:testDebugUnitTest :app:assembleDebug --max-workers=2` passed: **339 tests**
  across 39 suites, no failures or errors. A separate temporary local real-data check passed as above.
- **Nine database migration tests passed** on the API 37 emulator. Connected Gradle tests were
  deliberately confined to the emulator so they could not uninstall the phone's signed-in app.
- The final debug build was installed over the owner's existing Pixel app with `adb install -r` and
  launched, preserving sign-in and downloaded readings. After the owner unlocked the phone, the
  aggregate probe reported **392,603 readings**, **8/8 completed sources**, **zero recorded sync
  errors**. Resume after force-stop was separately verified.
- Real-phone navigation from Rooms to Reports passed. The 5–11 October report displayed **19.8°C**
  over seven matched complete days, agreeing with the independent calculation and production-builder
  check. No heating controls were changed.
- Emulator visual checks covered the light overview, dark room charts, a partial Year with dynamic
  colour and 1.5× text, an empty period without fabricated zero averages, and the scrollable About
  sheet. The chart/table switch exposed dated low/mean/high values in the accessibility tree.
  Semantics and labelled controls were inspected; a human TalkBack listening pass is still pending.
- The optional broad lint run **crashed in `androidx.annotation.experimental.lint.ExperimentalDetector`**
  while analysing `CommandExecutor.kt` (`Unexpected owner function: null`). No lint checks were
  suppressed. This is a recorded analyser failure, not a passing lint/release-readiness claim.
- No heating settings, schedules or Heat on Demand toggles were changed during verification.
