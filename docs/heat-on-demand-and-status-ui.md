# Heat on Demand indicator & "alive" status UI — design notes

Status: **built** on branch `feature/hod-status-ui` (2026-10-15); see §9 for what shipped vs the original spec. Mock canvas (private to the owner):
https://claude.ai/artifact/FYsNvRDGGNZdgdQ14hNMP3. API facts behind this live in
[`hive-domain-reference.md`](hive-domain-reference.md) §5, which takes precedence if the two disagree.

## 1. Problems being solved

1. **No Heat on Demand (HoD) indicator.** Hive's official app shows a full-screen "One or more of your
   Radiator valves is boosting your Thermostat" card when a HoD-enabled TRV is firing the boiler.
   GoodHive shows the thermostat as plain "Schedule", even though the boiler is running.
2. **States blur together.** On the dashboard (`ui/dashboard/DashboardScreen.kt`, `ZoneStatusChip`):
   - the Heating chip (`secondaryContainer` #FFE3BC) is **1.15:1** against the card (#FFF6E6);
   - the Schedule chip (`surfaceContainerHigh` #FCEDCE) is **1.08:1** against the card;
   - **Off and Schedule use the identical colour** (`surfaceContainerHigh`);
   - the −/+ buttons use the Heating colour on every card, idle or not.
   "Heating" is also just a guess (`isCallingForHeat()`: `current < target - 0.1`), not Hive's state.

## 2. Status ladder (one signal per card, emphasis rises with real activity)

| Tier | State | Card | Chip | Extra |
|---|---|---|---|---|
| Dormant | Off | `OutlinedCard`, no fill, name + temp muted (`onSurfaceVariant`) | outlined (`outlineVariant`), power icon | −/+ replaced by a filled-tonal "Turn on" button |
| Dormant | Offline | outlined, no fill | `errorContainer`, cloud-off icon | "Last seen …"; low battery in the chip text |
| Calm | On schedule | filled `surfaceContainer` | fixed `calmContainer` (cool blue), clock | context shows next change ("17° at 22:30") |
| Calm | Manual | filled `surfaceContainer` | fixed `overrideContainer` (violet), hand | — |
| Wants heat | Waiting for boiler | `surfaceContainer` | outlined in `heat`, hourglass | TRV wants heat, boiler idle, HoD off → nudge to enable HoD |
| Active | Heating | `heatContainer` | solid `heat`, flame | flat progress current → target |
| Active | Boost | `heatContainer` | solid `heat`, bolt, "Boost · 42m" | progress = time left |
| Active | HoD-driven thermostat | `heatContainer` | solid `heat`, radiator, "Heat on Demand" | inline "Boosted by Bedroom ›" row opens detail |

TRVs that are the HoD source also get a small "Heat on Demand · firing the boiler" line.
A dashboard-top summary card says "Heating is on / off" plus why (e.g. "Bedroom's Heat on Demand is
firing the boiler · 2 rooms warming up").

Status is a flat, non-interactive `StatusBadge` (not a chip) so it never looks like a button. The
card's headline number is the **current room temperature**; the stepper shows a labelled "Target".
There is no online dot (offline/battery are carried by the badge) and device type is a plain icon.
The summary card gets a `heat` outline + hourglass when rooms are waiting and the boiler is off.

Warm colour means **real heat only**. Every state also differs by fill, outline and icon, never hue
alone (CLAUDE.md accessibility rule).

## 3. New theme tokens: extended `heat` role

To add in `ui/theme/Color.kt`, exposed like `statusOnline` (a `ColorScheme` extension that picks the
light/dark variant). Do **not** reuse `error*`, because Offline must stay distinct from Heating.

| Role | Light | Dark | Contrast |
|---|---|---|---|
| heat | #A94400 | #FFB689 | light 5.98:1 on white; dark 7.72:1 vs onHeat |
| onHeat | #FFFFFF | #552000 | |
| heatContainer | #FFDCC6 | #5F2600 | heat text on it: 4.64:1 light, 6.98:1 dark |
| onHeatContainer | #351000 | #FFDCC6 | 13.3:1 light, 9.24:1 dark |

With dynamic colour on, harmonise `heat` toward the scheme's primary so it still reads as warm.

## 4. Motion (all through `GoodHiveMotion` / `LocalReducedMotion`; static when reduced motion is on)

- Flame icon: slow alpha pulse while Heating, Boost or HoD.
- Progress: flat `LinearProgressIndicator` (`HeatProgress`). The expressive wavy variant was dropped
  (2026-10-19): it flattens itself outside ~10–95% progress, so cards in the same state looked
  different for a reason users couldn't read, and the wave carried no information of its own.
- Card container colour: `animateColorAsState` on state change.

## 5. Thermostat detail while HoD is active

- **Must fit one phone screen, no scrolling** (user feedback: Hive's app needs no vertical scroll).
  A compact HoD card sits at the top: header line, one sentence, and one tappable row per source TRV
  with a progress bar. The dial, mode buttons and Boost stay below it.
- **Controls stay usable.** Hive's app locks the thermostat during a HoD boost. The user explicitly
  wants to be able to turn the thermostat Off during one, because Off stops the boiler regardless of
  TRVs (domain rule 1). Do not block writes. If Hive rejects or reverts the change, the existing
  `CommandExecutor` confirm and failure banner surfaces it. Hive **accepts** it (confirmed live, §7).

## 6. Detection logic (confirmed live 2026-10-15; see hive-domain-reference §5)

Read Hive's own flags. Never infer HoD from temperatures.
- **HoD boost running:** thermostat `props.autoBoost.active == true`. The thermostat will also show
  `mode=BOOST`, target 25, `state.boost=30`, so **a BOOST with `autoBoost.active` is HoD, not a user
  boost**. Label it "Heat on Demand", not "Boost", and don't offer "Cancel boost" as if the user started it.
- **Which rooms caused it:** `props.autoBoost.trvs` holds `trvcontrol` product ids, equal to
  `HeatingZone.id`, so no mapping is needed.
- **Boiler on:** thermostat `props.working`.
- **TRV states:**
  - **Heating** = TRV `working` and boiler on.
  - **Waiting for boiler** = TRV `working` and boiler off. This was seen live: a TRV was
    `working=true` while the thermostat was OFF, and GoodHive wrongly showed "Heating".
  - **On schedule** = TRV not `working`.
- Keep the `current < target` heuristic only as a fallback when `working` is absent.

Expect about 3.5 min between a HoD TRV's target rising and `active` flipping. The UI shouldn't imply
it's instant; for example, after raising a HoD TRV while the boiler is off, it can show "Waiting for
boiler".

## 7. Answered questions (live test, 2026-10-15)

1. `props.autoBoost.active` → `true` during a boost; `trvs` = trigger TRV product ids. ✅
2. TRV `props.working` = valve calling for heat (not receiving). ✅
3. Thermostat → OFF mid-boost is **accepted by Hive**. The boost ends, Hive restores its own OFF target
   (7°), and HoD does not re-fire while OFF. ✅ So GoodHive should allow it, as the user wants.
4. `duration` 30 = boost minutes (matches `state.boost=30`). ✅

Still untested: the household-wide `{"autoBoost": …}` write to the `heating` node (Pyhiveapi uses it).

**Bug found during the test (fix as part of this work).** Changing mode during *any* boost raises a
false "Couldn't confirm the change":
- `DeviceDetailViewModel.setMode` sends `buildModePayload(mode, currentTarget)` and expects that
  target.
- Mid-boost, the current target is the boost's 25°, so GoodHive posted `{"mode":"OFF","target":25}`
  and waited for 25.
- Hive applied OFF but set its own 7° target, so the confirm never matched.
- Fix: when leaving BOOST, or switching to OFF, don't send or expect `target` (send the mode alone,
  as `expectedStateForMode` already does for SCHEDULE). Then re-verify live.

Probe: `HiveActivityProbe.kt`, debug builds only, logs whitelisted fields when they change.
`adb logcat -s HiveProbe`. OkHttp body logging stays off on purpose (`di/NetworkModule.kt`).

## 8. Implementation plan

Each phase is shippable, ends with `./gradlew :app:testDebugUnitTest` and an on-device check, and updates
this doc. Device: Pixel 9 Pro XL over wireless adb. Builds need
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. The user has OK'd driving
the phone and changing heating, provided it's **reverted afterwards**.

### Phase 1: command-correctness bugs (do first, small, high value)
- **B1: false "Couldn't confirm" when changing mode during a boost.**
  - Cause: `DeviceDetailViewModel.setMode` sends `buildModePayload(mode, currentTarget)`; mid-boost
    that target is 25°.
  - Fix: send and expect `target` **only for MANUAL**. OFF and SCHEDULE send `{"mode":…}` alone, and
    `expectedStateForMode` matches mode only. Files: `data/repository/HeatingRepository.kt`
    (`buildModePayload`/`expectedStateForMode`), `ui/detail/DeviceDetailViewModel.kt`.
- **B2: "Cancel boost" posts `{"mode":"SCHEDULE","target":<boost target>}`**, which risks leaving a 25°
  schedule override.
  - Fix: map `props.previous` (`{mode, target}`, seen live) and cancel the way Pyhiveapi's
    `set_boost_off` does. If the previous mode was MANUAL or OFF, restore that mode with
    `previous.target`; otherwise send SCHEDULE alone.
- Tests: extend `app/src/test/.../data/repository/` with payload and expected-state cases (MANUAL keeps
  target; OFF and SCHEDULE don't; cancel-boost restore).
- Verify live: (a) start a 30-min Boost, then Off; (b) Boost, then Cancel. No false banner, and the
  thermostat ends in the expected state. Revert to the original state.

### Phase 2: data model and activity derivation
- `HiveMapper` → `HeatingZone` + `HeatingZoneCacheEntity` (Room version 6→7, destructive is fine).
  Mapping is in `HeatingRepository.toCacheEntity`/`toDomain`. New fields:
  - `working: Boolean?` ← `props.working`
  - `autoBoostActive: Boolean?` ← `props.autoBoost.active`
  - `autoBoostTrvIds: List<String>` ← `props.autoBoost.trvs`
  - `previousMode: String?` / `previousTargetC: Double?` ← `props.previous`
  - `boostMinutesLeft: Int?` ← `state.boost`
- New pure `data/model/HeatingActivity.kt`:
  - `ZoneActivity` = OFF, OFFLINE, IDLE, WAITING_FOR_BOILER, HEATING, BOOST, HOD_BOOST (thermostat)
    and HOD_SOURCE (TRV).
  - `HouseholdHeating(boilerOn, hodActive, hodSourceIds)` and `deriveActivities(zones)`, using the
    §6 rules. Fall back to the `current < target` heuristic only when `working` is null.
- Delete the private `isCallingForHeat()` in `DashboardScreen.kt`. Both ViewModels expose the
  derived activity, and the dashboard also exposes `HouseholdHeating`.
- Tests: `HiveMapperTest` with an anonymised fixture built from the live shapes (§5), and
  `HeatingActivityTest` covering every ladder row, including the live cases: "TRV working, thermostat
  OFF → Waiting" and "thermostat BOOST + autoBoost.active → HoD, not Boost".

### Phase 3: theme and dashboard (status ladder, §2–§4)
- Theme: add `heat`, `onHeat`, `heatContainer` and `onHeatContainer` light/dark in `ui/theme/Color.kt`,
  plus `ColorScheme.heat…` extensions next to `statusOnline` in `TemperatureVisuals.kt`. With dynamic
  colour, harmonise toward the primary.
- Dashboard (`ui/dashboard/DashboardScreen.kt`):
  - Rework `ZoneStatusChip` and `ZoneCard` to show one state per card, following the ladder.
  - Off zones use an `OutlinedCard` with "Turn on" (→ SCHEDULE) in place of −/+.
  - Heating cards get a flat progress bar (originally `LinearWavyProgressIndicator`; replaced
    2026-10-19, see §4).
  - The flame icon pulses via `GoodHiveMotion`.
  - The −/+ buttons stop using the heating colour everywhere.
  - Add a household summary card above the list ("Heating is on · Bedroom's Heat on Demand …").
  - The HoD-driven thermostat card gets an inline "Boosted by …" row, and source TRVs get the
    "firing the boiler" line.
  - Keep cards about as tall as today. The new rows appear only on active cards.
- Verify: light, dark and dynamic colour; reduced motion (animator scale 0); TalkBack reads the
  status; touch targets ≥48dp.

### Phase 4: thermostat detail during HoD (§5)
- `ui/detail/DeviceDetailScreen.kt`:
  - When `HOD_BOOST`, show the compact HoD card at the top: header, one sentence, and a tappable row
    per source TRV that navigates to its detail.
  - Hide "Cancel boost" (it would only re-fire about 3.5 min later). Keep the mode buttons; Off is
    the way to stop it (confirmed allowed).
  - The dial shows the boost target, with "25° Heat on Demand boost · 30 min".
- TRV detail when it is a HoD source: one-line note "Firing the boiler via Heat on Demand".
- After raising a HoD TRV's target while the boiler is idle, show "Waiting for boiler · Heat on Demand
  usually starts within ~4 min", so the delay doesn't look like a failure.
- Must fit one screen without scrolling on the Pixel 9 Pro XL; check a small display size too.
- Verify: re-run the live HoD test (§7), screenshot each state, then revert.

### Phase 5: investigations and clean-up
- **B3: schedule header mismatch.** In Schedule mode, Hive reported thermostat target **5°** while
  the header (`util/ScheduleNowNextLater.kt`) said "Now 10.0°C". Extend the probe to log the parsed
  current slot, then find out which is wrong (cached schedule, day/time zone, or the old SCHEDULE+target
  payload from B1 creating an override).
- **B4: system Back didn't leave the detail screen** once during the test (`input keyevent BACK`;
  the on-screen arrow worked). Reproduce. It may just be screenshot timing; if real, check predictive
  back with `enableOnBackInvokedCallback="true"`.
- Keep `HiveActivityProbe` debug-only. Record every outcome in hive-domain-reference §5 and in this doc.
- Update `CLAUDE.md` load-bearing facts once the UI reads the new fields.

## 9. What shipped (2026-10-15) and deviations from the spec above

- **Steps A and B are built and verified on the Pixel.** Live checks:
  - Off mid-user-boost: no false banner.
  - "Turn on": works.
  - HoD end-to-end: summary card, "Boosted by Study" row, source-TRV line, wavy progress, and
    the one-screen detail card with a tappable source row.
- **Material3 is pinned to `1.5.0-beta01`** (`gradle/libs.versions.toml`) for the public
  `LinearWavyProgressIndicator`, which no screen uses any more (progress bars are flat now). The pin
  can be revisited if nothing else needs 1.5.0 APIs. Move to stable 1.5.x when it's released.
- **Calm badges use fixed extended roles, not `tertiary`** (status legibility pass, 2026-10-15).
  With dynamic colour on, the wallpaper made `tertiaryContainer` peach, which read as heat; the
  interim fix (neutral grey chips) made Off / On schedule / Manual indistinguishable. `calm*` (blue)
  and `override*` (violet) in `TemperatureVisuals.kt` are harmonised ≤15° like `heat*`, so they stay
  cool under a warm wallpaper (`HarmonizeHueTest`); contrast is asserted in `StatusContrastTest`.
- **During a HoD boost the detail screen hides both Boost and Cancel boost**: a HoD thermostat is
  `mode=BOOST`, and cancelling would re-fire in about 4 min.
- **"Stop boost"** (the user's decision after the second live test): puts the source TRV(s) back to
  their current schedule temperature, which ends the boost reliably and leaves HoD enabled. Off stays
  available too, but Hive may re-fire the boost while the source is still calling.
- **Payload rules learned live:**
  - OFF sends `{"mode":"OFF","target":<frost>}` and expects mode only.
  - SCHEDULE sends mode only. **Superseded 2026-10-15:** mode-only left a TRV stuck at its 7° OFF
    target after OFF → SCHEDULE. SCHEDULE now also sends the current slot's target (CLAUDE.md).
  - MANUAL sends and expects the target.
  - Cancel boost restores `props.previous`.
- **B3 (schedule header vs Hive target):** explained by the old SCHEDULE+target payload. Fixed by
  mode-only SCHEDULE; verified that Hive reports 10° = the schedule slot.
- **B4 (system Back):** not reproducible, 3/3 passes.
- **Possible follow-up:** a TRV's `working` lags 2–5 min after its target is raised, so the card
  honestly says "On schedule" until Hive reports it calling. A "starting…" hint keyed off a recent
  target raise could bridge that gap.
- **Multiple sources**: several HoD TRVs can drive one boost at the same time (`autoBoost.trvs` is
  a list). Every UI string uses a shared `joinNames()` and plural copy. "Stop boost" resets
  **every** source, because the boost only ends once none is calling. The detail card lists up to
  3 source rows, then "+n more", to keep the one-screen fit.
