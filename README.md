# GoodHive

Android app for British Gas Hive heating: live temperatures, weekly schedules, and Heat on Demand.
The phone talks to Hive Cognito and Beekeeper directly — there is no GoodHive cloud backend.

## Why this exists

I found the official Hive app unreliable, so I used AI to help me build my own. For what I use it
for, GoodHive works much better than the official app.

I called it GoodHive because it's like the Hive app, but good.

Feel free to use it, fork it, or borrow from it if it's useful to you.

This app is developed primarily for myself. I don't intend to support it for the public, but I
thought I'd share it because why not?

## What it supports

GoodHive deliberately supports only:

- **Hive thermostats** (the primary heating thermostat / receiver)
- **Hive radiator valves (TRVs)**, including Heat on Demand

That's all the Hive hardware I have, so that's all it does. Hot water, lights, plugs, cameras,
sensors and other Hive products are intentionally out of scope.

Features include live room temperatures, mode and target changes, boost, weekly schedules, Heat on
Demand, temperature history, and reports.

## Design

The UI tries to follow Google's Android design guidelines closely:
[Material 3 / Material You](https://m3.material.io), with dynamic (wallpaper) colour, light and dark
themes, reduced-motion support, 48 dp touch targets, and TalkBack labels.

## Not affiliated with Hive

GoodHive is an **unofficial** app. It is not made, endorsed, or supported by Hive, British Gas, or
Centrica. "Hive" is their trademark.

It uses Hive's undocumented "Beekeeper" cloud API, the same one the official app uses. That API has
no public documentation and can change or break at any time. You sign in with your own Hive
account, and the app only controls your own devices. Use it at your own risk.

## Privacy

- Your Hive email and password are sent only to Hive's sign-in service (AWS Cognito).
- Tokens and saved credentials are stored on the phone, encrypted with the Android Keystore.
- Temperature history and a 30-day log of heating changes are kept in a local database on the phone.
- There are no analytics, ads, or third-party servers.

## Building

Requires Android Studio and a device on Android API 37 or later.

```bash
./gradlew :app:installDebug
```

Unit tests: `./gradlew :app:testDebugUnitTest`. How the app works under the hood is in
[`docs/architecture.md`](docs/architecture.md); the Hive API notes are in
[`docs/hive-domain-reference.md`](docs/hive-domain-reference.md).

## Credits

GoodHive stands on the shoulders of the Hive community, who worked out how Hive's cloud works long
before this app existed.

- **[Pyhive](https://github.com/Pyhass/Pyhive)** (formerly Pyhiveapi), by the Pyhass team, is the
  Python library behind the Home Assistant Hive integration. It is the main reference for how the
  Beekeeper API behaves. GoodHive's sign-in code (`SrpCrypto.kt`, `CognitoSrpClient.kt`) is ported
  from Pyhive's `srp_crypto.py` and `hive_auth_async.py`, and its boost, schedule and request-header
  handling follows Pyhive. Pyhive is MIT-licensed; its notice is
  reproduced below.
- **The [Home Assistant Community](https://community.home-assistant.io/)**, whose Hive threads,
  especially on
  [Heat on Demand](https://community.home-assistant.io/t/hive-heating-heat-on-demand/495713) and
  [TRVs with a Hive thermostat](https://community.home-assistant.io/t/automating-radiator-valves-trv-with-a-boiler-and-hive-thermostat/347546),
  explained behaviour Hive never documents.
- **[amazon-cognito-identity-js](https://github.com/aws-amplify/amplify-js/tree/main/packages/amazon-cognito-identity-js)**
  (AWS Amplify, Apache-2.0), the original Secure Remote Password (SRP) sign-in implementation that
  Pyhive's port, and so GoodHive's, is based on.
- **[Reorderable](https://github.com/Calvin-LL/Reorderable)** by Calvin Liang (Apache-2.0), which
  powers drag-to-reorder for rooms.

The app also uses standard open-source Android and Kotlin libraries, each under its own license.
The full list is in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

### Pyhive license

```
MIT License

Copyright (c) 2020 Pyhive

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## License

[MIT](LICENSE)
