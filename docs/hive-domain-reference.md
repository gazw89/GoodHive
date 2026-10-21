# Hive domain reference

How British Gas Hive's smart-heating hardware and cloud API actually work, and what that means for
this app. Sections 1-4 are general Hive product/hardware knowledge (external research, sourced below).
Section 5 is this repo's own confirmed-against-a-live-account API knowledge — the part most likely to
matter when changing the Android `data/network` / `data/model` / `HeatingRepository` layer.

## 1. What Hive is

Hive (British Gas) is a smart-heating ecosystem of three device classes that communicate over a local
Zigbee mesh, bridged to Hive's cloud:

- **Thermostat** — the wall-mounted (or app-only, "hubless") control unit. Sets the target temperature
  and schedule for the whole system or, with TRVs, for its own zone.
- **Hub + receiver** — the receiver is wired to the boiler and does the actual on/off (or OpenTherm
  modulated) switching; the hub is the Zigbee coordinator that lets the thermostat, receiver, and TRVs
  talk to each other and to Hive's cloud.
- **TRVs (TRV003)** — battery-powered radiator valve heads that provide per-room scheduling and
  temperature control as part of the same Zigbee mesh.

This app (GoodHive) is a third-party client of Hive's cloud, not a local Zigbee integration — it reads
and writes device state entirely through Hive's (unofficial) REST API.

## 2. Hardware models & architecture

- Thermostats come in full-size (V4) and smaller (Mini) form factors, each in **hub** and **hubless**
  variants, and in heating-only or heating+hot-water editions.
- **Hubless** thermostats connect straight to Wi-Fi. Simpler to install, but cannot join the Zigbee
  mesh — no TRVs, no other Zigbee accessories, ever, without replacing the thermostat.
- **Hub** models ship with the current Nano 3 hub (Ethernet + USB powered), the Zigbee coordinator for
  the receiver, TRVs, and Hive plugs. A hub can support up to ~6 heating zones from one thermostat.
- The three components (thermostat, hub, receiver) form a dependency chain: if any one of them loses
  power, battery, or Zigbee connectivity, the whole chain stops responding to schedule/boost commands
  even though the others are still fine.

## 3. Constraints that shape app design

- **OpenTherm and multi-zone/TRV control are mutually exclusive.** The receiver runs in either OpenTherm
  modulation mode (boiler told exact flow temperature, more efficient) or relay/multi-zone mode (needed
  for TRVs and room-by-room control) — never both on the same household. A household with TRVs is, by
  construction, not OpenTherm-modulated.
- **A TRV cannot call for heat once the main thermostat is already satisfied, unless Heat on Demand is
  enabled on that TRV.** The boiler only runs for a TRV's own demand if that specific TRV has Heat on
  Demand `ENABLED` — otherwise the room can show "wants heat" while getting none until the primary
  thermostat's schedule brings the boiler on for some other reason. See section 5 for the derivation.
- **A TRV must not be installed in the same room as the main thermostat** — they read the same space
  independently and fight over the target, producing erratic-looking temperature/mode data.
- **Frost protection** defaults to activating heating below ~7°C indoors and is configurable per zone
  (`HeatingZone.frostProtectionC`).
- **Geolocation-based auto-off/reminders** (turn heating off when everyone's away, based on phone
  location) is a genuine Hive app feature. This app does not currently model or surface it — worth
  knowing so it isn't mistaken for a bug if a zone's mode changes without a matching schedule entry.

## 4. Known failure modes

Relevant to why the app caches connectivity/`lastSeen` and treats missing Hive fields defensively
rather than trusting every payload blindly:

- **Zigbee pairing loss** between hub and receiver/TRVs — shows as "No Signal"/offline in the app even
  though power is fine. Common triggers: router restarts, hub relocation, ISP changes.
- **Hub placement/interference** — metal shelving, TVs, microwaves, and proximity to the router are
  common causes of dropped Zigbee connectivity; poor hub placement accounts for a large share of
  real-world "Hive offline" complaints.
- **Battery drain** — TRVs and hubless thermostats are battery powered; cold rooms accelerate drain, and
  a low-but-not-flat battery can cause erratic connectivity before it fails outright. Rechargeable
  batteries are explicitly discouraged by installers for this reason.
- **Receiver/relay wiring faults** — most common after boiler replacement or renovation work; look
  identical to a connectivity issue (receiver powered, but not switching) from the API's point of view.
- **Boost vs. schedule interaction** — an active boost blocks the next scheduled event from firing until
  it ends.
- **Cascading dependency failures** — because thermostat, hub, and receiver must all be online and
  paired, a single weak link (e.g. a flat TRV battery) can make an otherwise-healthy zone look
  unresponsive.

## 5. The unofficial Beekeeper API — confirmed field map

Hive publishes no API schema. Everything below is either directly observed from a live account (marked
**confirmed**) or best-effort/defensive extraction that degrades to `null`/`unknown` rather than
throwing when a field is missing. Treat this section as the canonical field reference for this repo.

- **Base URL & auth**: `https://beekeeper.hivehome.com/1.0`. Auth is AWS Cognito SRP (device
  SRP-auth + refresh-token flow — Android `CognitoSrpClient` / `SrpCrypto`), not a documented
  OAuth flow. **Confirmed**: requests need a Hive-like `User-Agent` (this repo uses
  `Hive/12.04.0 Android/GoodHive`) — a bare OkHttp User-Agent gets intermittent 403s from Hive's
  CDN.
- **Fetch**: `GET /nodes/all?products=true&devices=true&actions=false` returns `{products, devices}`.
  `product.type` is `"heating"` for the primary thermostat/relay or `"trvcontrol"` for a TRV; anything
  else is ignored by this app's zone extraction.
- **Confirmed field-path quirks** (each one caused a real bug before being fixed):
  - Display name is `product.state.name`, **not** `product.props.zoneName` — two different TRVs on one
    real account both reported `zoneName: "Thermostat 1"`.
  - A device's owning zone is `device.state.control` (matches a product's `id`), **not**
    `device.parent`/`device.props.parent` — those point at the *physical* parent hardware (typically the
    hub), not the room.
  - `device.lastSeen` is a **top-level** field on the device object (epoch milliseconds), not nested
    under `props`/`state`.
  - **Battery/connectivity (confirmed live 2026-10-15):** One TRV reported
    `device.props.battery=0` while both `product.props.online` and `device.props.online` were **true**.
    The official Hive app showed an empty battery/offline warning. Online flags alone do not prove
    a valve is usable: a valid 0% reading must take precedence over mode/activity and live controls.
    Read the physical device through `device.state.control` or `product.props.trvs` (physical device
    ids). An explicit offline flag on either the product or a linked device also makes it unavailable.
    Offline without 0% is not proof of an empty battery; label the cause conservatively. Invalid or
    absent battery values stay unknown. For several linked devices use the lowest valid percentage.
  - Current/target temperature: `product.props.temperature` (current) and `product.state.target`
    (target) — note current and target live under different top-level keys (`props` vs `state`).
  - Mode: `product.state.mode`, raw strings `SCHEDULE` / `MANUAL` / `OFF` / `BOOST` (and `HOLIDAY` for
    the primary thermostat) — kept as Hive's own strings, not remapped, to avoid drift as Hive adds
    values.
- **Heat on Demand (TRV "Auto Boost")**:
  - Setting lives at `product.state.autoBoost`, raw string `"ENABLED"`/`"DISABLED"`. Hive **omits** this
    key entirely when disabled — so a missing/null value is an authoritative "disabled", not an unknown
    to be filled from cache.
  - Support/eligibility is separate from the current setting: `product.props.capabilities` includes
    `TRV_AUTO_BOOST_READY` (hardware/firmware supports it, whether or not currently enabled) or the
    fully-active `TRV_AUTO_BOOST`. **Confirmed**: every TRV reports `*_READY` even while `autoBoost` is
    absent — offer the toggle whenever a `*READY`/`TRV_AUTO_BOOST` capability is present, not only when
    a current value already exists.
  - Toggling (confirmed live, 2026-08 — string-only bodies are **not** enough):
    - **Enable**: `POST /nodes/trvcontrol/{id}` with
      `{"autoBoost":"ENABLED","zone":"<primary thermostat props.zone>"}`. Posting `autoBoost` alone
      returns HTTP 200 but never sticks. The TRV's `state.zone` then mirrors that thermostat zone
      and `props.zoneName` becomes the thermostat name (e.g. "Thermostat 1").
    - **Disable**: `POST /nodes/trvcontrol/{id}` with `{"zone":""}` (empty string). Posting
      `{"autoBoost":"DISABLED"}` while HoD is on returns `HTTP 400 MALFORMED_REQUEST` and does not
      change state. After a successful disable Hive omits `state.autoBoost` and clears `state.zone`.
    - Re-posting an already-applied enable can return `HTTP 400 MALFORMED_REQUEST` — always diff
      against a fresh fetch before writing, and skip no-ops.
  - Mechanism (per Hive's own support documentation, not just inferred): raising a Heat-on-Demand-enabled
    TRV's target sends a one-off trigger that temporarily raises the primary thermostat's own firing
    threshold just enough to start the boiler, until that TRV's room reaches target. A TRV with Heat on
    Demand disabled can only benefit from heat the boiler is *already* producing for some other reason —
    it can never start the boiler by itself. Boiler demand from Hive's product rules:
    1. Primary thermostat `OFF` or `HOLIDAY` → boiler is not running, full stop, regardless of any TRV.
    2. Else, primary thermostat's own demand `requesting` → boiler is running.
    3. Else, any TRV with `autoBoost: "ENABLED"` **and** its own demand `requesting` → boiler is running.
    4. Otherwise → not running (or `unknown` if the primary thermostat's own state couldn't be read).
- **Live heating-activity fields.** First found in the open-source Pyhiveapi library, then **confirmed
  live on 2026-10-15** with a debug probe (`data/network/HiveActivityProbe.kt`, `adb logcat -s HiveProbe`)
  during a real Heat on Demand (HoD) event. GoodHive's UI does not read these yet.
  - `product.props.working` (boolean) on both `heating` and `trvcontrol`:
    - **On a TRV it means "this valve is calling for heat", not "receiving heat".** Confirmed: a TRV
      with HoD off showed `working=true` (19.8° → 21°) while the thermostat was `OFF`, so no boiler.
      Combine it with the thermostat's state to tell "Heating" apart from "Waiting for boiler".
    - On the thermostat it went `true` when HoD fired the boiler, and is `false` when idle or OFF.
  - `product.props.autoBoost` is an **object** `{"active": bool, "duration": 30, "trvs": [ids]}`. It is
    present on the `heating` product and on every TRV with HoD **enabled**, and absent on TRVs with HoD
    off. It is distinct from the TRV's `state.autoBoost` string.
    - `active=true` means a HoD boost is running. **`trvs` holds the `trvcontrol` *product* ids** that
      triggered it, matching `HeatingZone.id` directly.
    - `trvs` can still list the trigger while `active=false` right after a boost ends (seen after OFF).
      It cleared once that TRV was satisfied, so key off `active`, not `trvs`.
  - **A HoD boost is a real thermostat boost** (confirmed): thermostat `state.mode` → `"BOOST"`,
    `state.target` → `25`, `state.boost` → `30` (minutes, matching `autoBoost.duration`), and
    `props.working` → `true`. Without checking `props.autoBoost.active`, it looks exactly like a
    user-started boost, which is how GoodHive shows it today: an unexplained 25° with "Cancel boost".
  - **Latency** (confirmed): about **3.5 min** from raising a HoD TRV's target to `active=true`. Don't
    expect it within one poll.
  - **The thermostat can be turned OFF mid-HoD-boost** (confirmed): `POST {"mode":"OFF",…}` was accepted.
    Hive ended the boost (`active=false`, `working=false`) and restored the thermostat's own OFF target
    (7°), ignoring the target sent. HoD did not re-fire while OFF, even though the TRV kept calling
    (`working=true`) for 3+ min. Hive's own app blocks this; Hive's API does not.
  - **Second live test, 2026-10-15 08:08–08:25:**
    - **`{"mode":"OFF"}` without a target returns 200 but does NOT apply during a HoD boost.** The
      thermostat stayed in BOOST for 4+ min. `{"mode":"OFF","target":7}` applied in about 5 s.
      Always send a target with OFF, but expect mode only. (Off without a target *does* work during
      a user boost.)
    - **HoD can re-fire after OFF.** About 80 s after OFF applied, Hive put the thermostat back to
      `BOOST`/25° on its own, because the source TRV was still calling and `autoBoost.active` had
      stayed `true`. In the first test it didn't re-fire for 3+ min, so the behaviour is
      inconsistent. **Thermostat OFF is not a reliable way to stop a HoD boost.**
    - **Lowering the source TRV's target below room temperature ends the HoD boost at once.**
      `active` → false, `trvs` → [], and the thermostat returns to its pre-boost mode/target.
    - A TRV's `working` lags its target change. One TRV only reported `working=true` about
      2–5 min after its target was raised, and HoD fired about 5.5 min after the change.
    - Sending SCHEDULE with a stale `target` (the old payload) left the thermostat at target 5°
      while the schedule slot said 10°. Mode-only SCHEDULE gave the correct 10°. This explains the
      "Now 10° vs 5°" mismatch.
  - The `heating` product also has `state.autoBoost: "ENABLED"`, plus `TRV_AUTO_BOOST_READY` in its
    capabilities. Pyhiveapi writes `{"autoBoost": "ENABLED" | "DISABLED"}` to the **heating** node as a
    household HoD switch. We have **not** tested that write; the per-TRV writes above are the proven path.
  - `trvcontrol.props.trvs` holds the physical TRV *device* ids (not product ids).
  - Other keys seen live but unused: heating `props` has `holidayMode`, `model`, `pmz`, `previous`,
    `readyBy` and `scheduleOverride`; heating `state` has `optimumStart`. TRV `props` has `consumers`,
    `parent`, `pmz`, `previous`, `scheduleOverride` and `zoneName`.
- **Writes are fire-and-forget from Hive's point of view**: a `2xx` response only means Hive's cloud
  *accepted* the command, not that the physical device applied it. This is why the Android
  `HeatingRepository` pairs every write with an `expectedStateFor…` check and a command-confirmation/retry
  layer rather than trusting the POST response alone.
- **Schedule shape** (`product.state.schedule`, **confirmed** exact shape against a real account):
  Monday-first day keys (`monday`…`sunday`), each a list of
  `{"start": <minutesSinceMidnight 0-1439>, "value": {"target": <°C>}}` slots — the slot holds from its
  `start` until the next slot (or midnight wrap for the last one). Mirrored in Kotlin as `WeekSchedule`/
  `ScheduleSlot` (`app/src/main/kotlin/com/gazw89/goodhive/data/model/HeatingZone.kt`).
- **Setpoint ranges**: this app defaults the primary thermostat's allowed range to 5-32°C
  (`HeatingZone.minTemperatureC`/`maxTemperatureC`); published TRV003 hardware specs allow a wider
  0-40°C range on the valve itself — don't assume both device classes share one clamp range in UI or
  validation.
- **Temperature history** (confirmed live 2026-10-15 on an account **without Hive+**, so it isn't
  paywalled at the API, whatever the official app shows):
  `GET history/heating/{id}?start=<epochMs>&end=<epochMs>&timeUnit=<MINUTES|HOURS|DAYS>&rate=<n>&operation=<AVG|MAX|MIN>`
  returns `{"data":[{"date":"<epochMs as string>","temperature":<°C double>}]}`. The `date` is a
  **string**.
  - `{id}` is the thermostat's `heating` **product** id, or a TRV's physical `trv` **device** id
    (the ids in `trvcontrol.props.trvs`). A `trvcontrol` product id returns 404. The thermostat's
    `boilermodule` device id works too, but its `thermostatui` device id returns 404.
  - Only room temperature is returned. There's no target, mode or `working` history.
  - `operation=MIN` works (confirmed 2026-10-15): over the same 7 days, daily MIN 19.0 < AVG 19.49 <
    MAX 20.0 (thermostat) and 18.8 / 19.44 / 20.3 in another run.
  - Resolution: `MINUTES&rate=5` gives the raw-ish readings. The thermostat reported every 5–10 min
    (173 points in 24 h) and a TRV every 10–15 min (90 points in 24 h). `HOURS&rate=1` gives hourly
    averages.
    A subsequent 30-day check across all eight sources found TRV median intervals of 15–25 minutes,
    a 95th percentile of 30 minutes and a maximum of 40 minutes. Do not promise 5-minute precision
    merely because the requested bucket width is five minutes. With `operation=AVG`, reported lows
    and highs are extrema of returned bucket averages, not exact underlying sensor extrema.
  - Raw volume over 60 days at `MINUTES&rate=5` (2026-10-15): the thermostat returned 10,950 points
    (487 KB), about **182 readings a day**; one TRV 4,969 points (219 KB), about **83 a day**.
    Fifteen months of one account (1 thermostat + 7 TRVs) is roughly 340k rows.
  - Span limit: 75 days per request worked, 90 and 120 days returned 404 `NOT_FOUND`. Retention:
    data from **450 days ago came back for a TRV** (July 2025, confirmed 2026-10-15); 800 days
    returned 404.
  - **Transient 404s on valid windows** (2026-10-15): one TRV returned `404 NOT_FOUND` for
    four winter windows (Dec 2025 – Jan 2026) at both HOURS and DAYS, though December alone and
    January alone worked. Re-running a failed window 3 minutes later returned 200 with full data. So a
    404 is not proof that a range is out of retention: retry (5 s, 30 s), then split the window down
    to 7 days, and only then give up on it.
  - During a locked/dozing-phone probe, requests failed with `UnknownHostException` while the shell
    could resolve Hive, and logs showed destroyed app sockets. This is an observation, not proof that
    all background networking is always blocked. Use WorkManager for durable background sync;
    visible screens may use the on-demand fallback. The real history worker subsequently downloaded
    while the phone was locked and resumed from saved cursors after a force-stop/relaunch.
  - Unknown routes (e.g. `history/trvcontrol/…`, `history/trv/…`) return **403** with an AWS
    SigV4 "Authorization header requires 'Credential'…" message. That looks like AWS API Gateway's
    usual reply for an undefined route (inferred), **not** an auth or rate-limit failure: the same
    token got 200s from real routes in the same run. Don't treat it as a session rejection.
  - `GET products/trvcontrol/{productId}/events?from=&to=` returns `[{id,start,end,type,timestamp}]`.
    It seemed to ignore `from`/`to`: a 1-day and a 7-day request returned identical bodies. For one
    TRV it held 97 `HEATING_FAILURE` events, in bursts at identical timestamps. The heating product's
    events were `[]`. Meaning unconfirmed.
  - Probe: debug-only `app/src/debug/.../HistoryProbeReceiver.kt`, triggered with
    `adb shell am broadcast -a com.gazw89.goodhive.PROBE_HISTORY -n com.gazw89.goodhive/.debug.HistoryProbeReceiver`
    (optional `--es paths "label=path;…"`).
- **SCHEDULE must carry the current slot's target** (confirmed live 2026-10-15, one TRV):
  - After OFF → `{"mode":"SCHEDULE"}`, Hive reported `mode=SCHEDULE, target=7` (the OFF frost
    target) for 5+ min, while the slot asked for 19°. It would have stayed at 7° until the next slot.
  - Posting `{"mode":"SCHEDULE","target":19}` (the current slot) gave `target=19` and
    `props.scheduleOverride=false` within 30 s.
  - The earlier "mode-only" rule only guards against **stale** targets.

## 6. Sources

External (product/hardware/failure-mode research):

- [Hive Smart Thermostats Explained: V4, Mini, Hub or Hubless](https://qualityheating.co.uk/blogs/learn-more/hive-smart-thermostats-v4-mini-hub-or-hubless-uk-guide)
- [Hive Thermostat Problems & Fixes 2026 UK Guide](https://boilers2go.co.uk/advice/hive-thermostat-problems-fixes/)
- [Hive Heating — doesn't allow true multi-zone heating (user discussion)](https://singletrackworld.com/forum/off-topic/hive-heating-doesnt-allow-true-multi-zone-heating/)
- [Automating Radiator Valves (TRV) with a boiler and Hive thermostat — Home Assistant Community](https://community.home-assistant.io/t/automating-radiator-valves-trv-with-a-boiler-and-hive-thermostat/347546)
- [Pyhiveapi (Pyhass/Pyhiveapi, `dev` branch)](https://github.com/Pyhass/Pyhiveapi) — the open-source
  Python client behind Home Assistant's Hive integration. Key files: `src/devices/heating.py`
  (`get_current_operation` → `props.working`, `get_heat_on_demand` → `props.autoBoost.active`,
  `set_heat_on_demand` → `{"autoBoost": …}`) and `src/data/data.json` (an anonymised sample
  `nodes/all` payload, the best public picture of the field shapes).
- [Hive Heating — Heat on Demand (Home Assistant Community)](https://community.home-assistant.io/t/hive-heating-heat-on-demand/495713) —
  users note HA shows *that* Heat on Demand fired but not *which* TRV fired it.
- [A Guide to Hive Heating Geolocation Features — Hive Support](https://support.hivehome.com/portal/app/portlets/results/viewsolution.jsp?solutionid=240311182433700)

Internal (confirmed API/domain knowledge already in this repo — treat as primary sources over the
external links above wherever they'd conflict):

- [`app/src/main/kotlin/com/gazw89/goodhive/data/network/HiveMapper.kt`](../app/src/main/kotlin/com/gazw89/goodhive/data/network/HiveMapper.kt) —
  Beekeeper field extraction.
- [`app/src/main/kotlin/com/gazw89/goodhive/data/repository/HeatingRepository.kt`](../app/src/main/kotlin/com/gazw89/goodhive/data/repository/HeatingRepository.kt) —
  Heat on Demand / target / schedule write payloads + expected-state helpers.
- [`app/src/main/kotlin/com/gazw89/goodhive/data/model/HeatingZone.kt`](../app/src/main/kotlin/com/gazw89/goodhive/data/model/HeatingZone.kt) —
  Android domain model, same confirmed quirks.
