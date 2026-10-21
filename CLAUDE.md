# CLAUDE.md

## Start here

This file holds the rules and is the single source of truth. `AGENTS.md` and `.cursor/rules/` defer to
it. The deeper docs are read on demand:

- [`docs/architecture.md`](docs/architecture.md) covers how the app works under the hood: codemap,
  refresh/polling, the command reliability layer, auth, migrations, invariants, and build/test/debug.
  **Read it before changing `auth/`, `data/`, `work/`, or any ViewModel's refresh/write logic.**
- [`docs/hive-domain-reference.md`](docs/hive-domain-reference.md) is the Hive API field map and the
  confirmed payload shapes.
- [`docs/heat-on-demand-and-status-ui.md`](docs/heat-on-demand-and-status-ui.md) is the status-ladder
  and Heat on Demand design history.

## Hive domain knowledge

GoodHive is an Android client of British Gas Hive's **unofficial** "Beekeeper" cloud API
(`https://beekeeper.hivehome.com/1.0`) — Hive publishes no schema. Full reference, sources, and
hardware/product background: [`docs/hive-domain-reference.md`](docs/hive-domain-reference.md). Load-bearing
facts that must not be broken when touching Hive-related code:

- `productType` `"heating"` = primary thermostat/relay, `"trvcontrol"` = TRV. Only the primary
  thermostat's own demand, or a Heat-on-Demand-`ENABLED` TRV's own demand, can drive the boiler — and
  never when the primary thermostat is `OFF` or `HOLIDAY`.
- Field-name quirks confirmed against a live account: display name is `state.name`, **not**
  `props.zoneName`; a device's zone link is `state.control`, **not** `device.parent` (that's the
  physical hub); `device.lastSeen` is a top-level epoch-ms field, not nested.
- Heat on Demand is the **live** Hive TRV toggle (`state.autoBoost`). There is no GoodHive cloud
  "Schedule on demand" / reconcile planner anymore — do not reintroduce one without an explicit product
  decision.
- Heat on Demand write shape (confirmed live 2026-08 against a real account — string-only bodies
  are wrong). Endpoint: `POST /nodes/trvcontrol/{trvProductId}`:
  - **Enable:** `{"autoBoost":"ENABLED","zone":"<primary thermostat props.zone>"}`. The `zone`
    value is the heating product's `props.zone` (cache/pass as `hiveZoneId` /
    `thermostatHiveZoneId`), **not** the TRV's product `id`. Posting `autoBoost` alone returns
    HTTP **200** but **never sticks** — confirm polls time out and the UI snaps back off. After a
    real enable, the TRV's `state.zone` mirrors that thermostat zone and `state.autoBoost` is
    `"ENABLED"`.
  - **Disable:** `{"zone":""}` (empty string). Posting `{"autoBoost":"DISABLED"}` while HoD is on
    returns HTTP **400** `MALFORMED_REQUEST` and leaves HoD on — optimistic UI then snaps back on.
  - Re-posting an already-applied enable can also 400 — always diff against a fresh fetch and skip
    no-ops (`CommandExecutor`).
  - Hive **omits** `state.autoBoost` (and clears `state.zone`) when disabled — missing ≡
    authoritative "disabled", not unknown. Payloads live in `HeatingRepository`.
- Live activity fields (confirmed 2026-10-15; details in `docs/hive-domain-reference.md` §5):
  - `props.working` on a TRV means "valve calling for heat", **not** "receiving heat".
  - `props.autoBoost = {active, duration, trvs}` sits on the thermostat and on HoD-enabled TRVs; `trvs`
    holds trvcontrol **product** ids.
  - A HoD event makes the thermostat `mode=BOOST`, target 25, `state.boost=30`. Check
    `autoBoost.active` before treating a BOOST as user-started.
  - OFF writes must include a target (`{"mode":"OFF","target":<frost>}`) and expect mode only:
    `{"mode":"OFF"}` alone returns 200 but doesn't apply mid-HoD. Even when OFF applies, Hive can
    re-fire the HoD boost while the source TRV is still calling. Lowering the source TRV's target
    is what reliably ends it.
  - SCHEDULE writes send the **current schedule slot's** target:
    `{"mode":"SCHEDULE","target":<scheduleNowNextLater().currentTargetC>}`. Mode-only leaves Hive on
    the previous target; after OFF → SCHEDULE a TRV stayed at the 7° frost target until the next slot
    (confirmed live 2026-10-15). A **stale** target, such as a boost's 25°, creates a wrong override.
    The current slot's target doesn't (`scheduleOverride` stays false). With no schedule, send mode
    only.
  - Design work in progress: [`docs/heat-on-demand-and-status-ui.md`](docs/heat-on-demand-and-status-ui.md).
- Temperature history (`GET history/heating/{id}`, confirmed live 2026-10-15) works **without
  Hive+**. A thermostat uses its `heating` **product** id. A TRV uses its physical `trv` **device**
  id (`props.trvs`, `HeatingZone.historySourceIds`), never the `trvcontrol` product id, which 404s.
  A 403 whose body says "Authorization header requires 'Credential'" means "no such route", not
  auth or a rate limit. History **is stored locally** (user decision, reversing the earlier "never
  store" rule, because Reports use it): raw 5-minute readings sync into Room (`history_reading`) from
  `HistorySyncWorker`; background history network traffic runs inside that job, while a visible
  room screen may make an on-demand read before its local store is populated. Hive returns
  transient `404 NOT_FOUND` for valid windows, so a 404 is retried and the window split before it is
  treated as out of range. Details: `docs/architecture.md` "History reads" and `docs/reports-plan.md`.
- A `2xx` from Hive only confirms the cloud accepted the write, not that the physical device applied
  it — pair writes with an expected-state check (see `HeatingRepository`'s `expectedStateFor…`
  helpers), never trust the POST response alone. A 200 with the wrong HoD body is especially
  dangerous: it looks successful until confirm fails.
- Physical constraint: OpenTherm modulation and TRV/multi-zone relay control are mutually exclusive on
  a household's receiver, and a TRV cannot override a satisfied main thermostat unless Heat on Demand
  is enabled on it.

There is no GoodHive backend, MCP, or household cloud link. The phone talks to Hive Cognito and
Beekeeper directly.

## UI Guidelines

### Authority & sources of truth

- UI must follow **Material 3 / Material You** per https://m3.material.io and [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3).
- Compose theme tokens live only under `app/src/main/kotlin/com/gazw89/goodhive/ui/theme/` (`Theme`, `Color`, `Type`, `Shape`, `Spacing`, `Motion`). Do not invent parallel palettes in screens.
- Do **not** add Compose Material 2 (`androidx.compose.material` components). Icons from `androidx.compose.material.icons` are allowed.
- Before shipping UI, verify against current M3 specs — not Material 2 patterns or ad-hoc styling.

### Color system (Material You)

- Support **dynamic color** (wallpaper-derived schemes on API 31+) via Settings; branded honey/espresso scheme remains the fallback when dynamic color is off or unavailable.
- `lightColorScheme` / `darkColorScheme` (and any dynamic scheme) must set **all roles the app uses**, including containers and variants (`secondaryContainer`, `onSecondaryContainer`, `outlineVariant`, `scrim`, surface containers, etc.). Do not rely on library-default fillers for roles referenced in UI.
- Screens must use `MaterialTheme.colorScheme.*` for chrome and copy. Hardcoded `Color(0x…)` in composables is forbidden except:
  - theme token definitions in `ui/theme/`
  - data-viz / temperature accents defined as named theme helpers (e.g. `TemperatureVisuals`) with **light and dark** variants and documented contrast
- Never disable or bypass tonal roles by painting raw mid-gradient accents onto `Button`/`SegmentedButton` containers without a paired `contentColor` that meets contrast.

### Typography, shape, spacing

- Use `MaterialTheme.typography.*` roles. Prefer adjusting weights in `Type.kt`, not sprinkling `fontWeight` overrides at call sites.
- Use `MaterialTheme.shapes.*` for corners. No one-off `RoundedCornerShape(…)` / `RoundedCornerShape(50)` in screens unless adding a new named token in `Shape.kt`.
- Spacing must come from `GoodHiveSpacing` (extend the scale in `Spacing.kt` if a new step is needed). Avoid magic `N.dp` padding/spacers in screens.

### Components (prefer M3)

- Prefer Material 3 components over custom layouts for standard patterns:
  - Tabs → `PrimaryTabRow` / `ScrollableTabRow` + `Tab`
  - Chips / badges → `AssistChip` / `FilterChip` / `SuggestionChip` / `Badge`
  - Settings / nav rows → `ListItem`
  - Sheets → `ModalBottomSheet`; dialogs → M3 `AlertDialog` / sheets
  - Text fields → `OutlinedTextField` / `TextField` with scheme colors
- Custom controls (e.g. temperature dial, wheel picker) are allowed only when no M3 component fits the product UX. They must still:
  - use theme colors/shapes/typography
  - expose correct semantics
  - show M3 state layers (ripple) on pressable areas
  - meet 48dp minimum touch targets

### Interaction, elevation, motion

- Do **not** set `indication = null` on clickable/pressable surfaces unless there is an explicit, documented accessibility-safe substitute for state layers.
- Interactive targets ≥ **48×48 dp** (`IconButton` defaults / `minimumInteractiveComponentSize()`). Do not shrink IconButtons to 44dp.
- Prefer M3 tonal elevation (surface containers, `CardDefaults` tonal styling). Avoid ad-hoc `Modifier.shadow` unless required for a transient gesture (e.g. drag lift) and remove when idle.
- Animations must respect reduced motion: provide `LocalReducedMotion` from the theme root; use `GoodHiveMotion` helpers (or snap) — never fixed-duration entrance animations that ignore the system animator scale.

### Light / dark / window theme

- App must support light and dark via `ThemeMode` (System / Light / Dark).
- XML `themes.xml` is **window/splash only**. Keep comments accurate (do not claim Expressive/dynamic color lives in XML). Prefer DayNight-compatible window backgrounds so forced theme mode does not flash the wrong splash.

### Accessibility

- Color is never the sole status signal (e.g. online/offline): add semantics and/or icon/text.
- Decorative icons: `contentDescription = null` only when a parent already labels the control.
- Interactive custom widgets must announce name, value, and actions (TalkBack-smoke when changing dials/pickers/tabs).

### UI change checklist (required before done)

When a change touches Compose UI or theme:

1. Confirm colors/type/shape/spacing come from theme tokens.
2. Confirm M3 component chosen where a standard pattern exists.
3. Confirm ripples + ≥48dp targets on new interactive elements.
4. Confirm light **and** dark (and dynamic-on if the change touches color).
5. Confirm reduced-motion path if adding animation.
6. Follow **Device deploy & verification** (install on device when available) — compile-only is not done.

## Device deploy & verification

After completing an implementation or bug fix that produces a runnable app change, do **not** stop at compile-only.

1. Check for a device via `adb devices` (USB or wireless debugging). Use the SDK `platform-tools` from `local.properties` / `~/Library/Android/sdk` if `adb` is not on `PATH`.
2. If at least one device/`device` entry is connected, install and launch with `./gradlew :app:installDebug` or `:app:installRelease` as appropriate (and start the app’s main activity if needed).
3. Ask the user to smoke-test the change on-device, **or** drive the verification yourself with `adb` (launch app, screenshot, logcat) when that is practical.
4. If no device is connected, say so clearly and tell the user how to retry once debugging is available — still do not treat “compiled successfully” as the end of the task when a device might be available.

## Public repo & pushing

This repository is **public** (`github.com/gazw89/GoodHive`). Nothing may be pushed without gitleaks
passing on the commits being pushed.

1. Before every `git push`, run gitleaks on the outgoing commits, e.g.
 `gitleaks git --redact --log-opts="origin/main..HEAD" .`. If it reports any finding, or gitleaks is
 not installed, do **not** push. Fix the commit, or ask the user.
2. The tracked `.githooks/pre-push` hook enforces this. Enable it once per clone with
 `git config core.hooksPath .githooks`. Never bypass it with `--no-verify`.
3. Never commit personal data: home postcode or coordinates, account emails, Hive device/zone ids from
 a real account, tokens, or `tmp/` probe scripts. Tests and docs use made-up fixtures.
4. Old pre-public history lives in the private `archive` remote (`gazw89/GoodHive-archive`). Never push
 `backup/pre-public-history` or any branch based on it to `origin`.
