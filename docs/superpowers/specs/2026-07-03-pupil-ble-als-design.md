# Pupil — the phone as a BLE ambient-light sensor (design)

_2026-07-03. Approved design for the phone-ALS BLE broadcaster ("Pupil") and its laptop receiver.
Companion to iris (STATUS.md "Phone ALS" open question). Research-grounded: four web-research
sweeps verified the wire format, advertising economics, screen-off sensor behaviour, and the
BlueZ receive path; load-bearing findings are cited inline._

## 1. Goal & scope

Turn the Android phone (Oppo Find N6, ColorOS 16 / Android 16) into a broadcast BLE
ambient-light sensor that the laptop can receive.

**Success criterion for v1:** Pupil broadcasts BTHome v2 adverts carrying live lux, and a
receiver script on the laptop prints/logs them, with correct freshness/presence semantics.
Integration into the iris daemon is *designed for* (a clean importable seam) but **deferred** —
the webcam remains iris's foundation; Pupil is an opportunistic boost.

**Out of scope for v1:** BTHome encryption (threat model: the neighbours learn how bright the
room is), additional sensor objects, auto-start on boot, Play-store distribution (sideloaded
personal app — Android-vitals wakelock policy is irrelevant), iris daemon fusion logic.

## 2. Architecture

```
Oppo Find N6                                molly (laptop)
┌───────────────────────────────┐           ┌────────────────────────────────┐
│ Pupil (Kotlin app, android/)  │           │ python/scripts/ble_als_probe.py│
│  ALS ──► deadband/rate limit  │ ~~BLE~~►  │  bleak active scan             │
│      ──► BthomeEncoder        │  adverts  │   ► bthome-ble decode          │
│      ──► AdvertisingSet       │  (BTHome  │   ► RSSI gate + state machine  │
│  (foreground service,         │   v2)     │   ► print / --csv log          │
│   connectedDevice type)       │           │  (pure logic in a module the   │
└───────────────────────────────┘           │   iris daemon imports later)   │
                                            └────────────────────────────────┘
```

- **Repo layout:** new `android/` directory alongside `python/` and `rust/` (by-language
  convention), containing the Pupil gradle project. Package id `io.github.genneth.pupil`.
- **Name/branding:** **Pupil** — the part of the eye that admits light; iris's anatomical
  companion and its star pupil. Icon: an eye whose pupil is the Bluetooth rune.
- **Transport: connectionless.** Non-connectable, non-scannable legacy BLE adverts. No pairing,
  no connection state machines, any number of listeners; absence (pocket, range, Doze) degrades
  to "no fresh adverts", which is exactly the opportunistic model iris wants.

## 3. Wire format — BTHome v2 (verified against bthome.io/format)

Unencrypted BTHome v2 service data under 16-bit UUID `0xFCD2`, plus a Flags AD element —
**mandatory**: BlueZ won't parse the advert under passive scanning without Flags.

> **Implementation reality (2026-07-03):** Android provides no way to include a Flags AD
> element on a non-connectable legacy advertising set, and the local name Android would
> advertise is the phone's Bluetooth adapter name, not "Pupil" — so the shipped advert carries
> service data only (the Flags AD element and Complete Local Name in the worked example below
> are not actually sent, and the byte total drops accordingly). Our receiver uses ACTIVE
> scanning and is unaffected. Consequence: Home Assistant / BlueZ PASSIVE-mode reception of this
> advert is unverified (passive scanning is Flags-gated, per the "mandatory" note above) —
> revisit if the passive-scanning upgrade (§7) is pursued.

Objects (ascending object-id order is **required** by the spec):

| Object | Id | Type | Meaning |
|---|---|---|---|
| Packet id | `0x00` | uint8 | Must *change* per new reading (wrap-around fine, need not increment by 1). |
| Illuminance | `0x05` | uint24 LE | 0.01 lx units; ceiling ~167 klx (direct sunlight fits). |

Worked example — packet id 42, illuminance 143.5 lx:

```
02 01 06                          Flags: LE General Discoverable | no BR/EDR
0A 16 D2 FC 40 00 2A 05 0E 38 00  Service Data (16-bit UUID):
   │  │───┘ │  │──┘ │──────────┘
   │  │     │  │    └ 05 illuminance: 0E 38 00 = 14350 ×0.01 = 143.50 lx
   │  │     │  └ 00 packet id = 42 (0x2A)
   │  │     └ 40 device info: v2, unencrypted, non-trigger
   │  └ UUID FCD2 (little-endian on the wire)
   └ AD type 0x16
06 09 50 75 70 69 6C              Complete Local Name "Pupil"
```

21 of 31 legacy-advert bytes. Free interop: Home Assistant / Theengs recognise the phone as a
generic BTHome illuminance sensor with zero config.

**The packet id is load-bearing, not optional:** BlueZ dedups *identical* service data into
silence (bleak sets `DuplicateData=False`), so without a changing packet id a constant room is
indistinguishable from a dead app. It also powers the receiver's freshness contract (§5).

## 4. Pupil, the phone app

The smallest respectable Kotlin app: one Activity, one foreground Service, one pure function.
Plain Views (no Compose — one screen doesn't justify the build weight). minSdk 31.

### Components

- **`BthomeEncoder`** — pure function `(packetId, lux) → ByteArray` producing the §3 service
  data. JUnit-tested against golden vectors (including the spec's own 13460.67 lx example).
- **`PupilService`** — foreground service, type `connectedDevice` (documented FGS type for BLE;
  **no runtime timeout** on Android 14–16, unlike `dataSync`). Manifest: `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`; runtime: `BLUETOOTH_ADVERTISE` (which also qualifies
  the FGS type), `POST_NOTIFICATIONS`. Owns the sensor listener and one `AdvertisingSet`.
  The persistent notification doubles as the live display: "👁 broadcasting 143 lx".
- **`MainActivity`** — start/stop toggle, current lux, the permission dance, a one-tap jump to
  the battery-exemption dialog, the settings screen (§6 knobs), and a **sensor report panel**:
  wakeup-ALS present? FIFO depth? which acquisition rung (§4a) is active? Genuinely per-device
  data nobody has published for the Find N6.

### 4a. Sensor acquisition ladder (from the screen-off research)

Screen-off delivery is gated by SoC suspend, not the display; the platform-prescribed pattern
exists but ColorOS is empirically unverified — so a ladder, probed at runtime:

1. **Wakeup ALS variant** — `getDefaultSensor(TYPE_LIGHT, /*wakeUp=*/true)`. Qualcomm
   sensor-hub devices (incl. past Oppos) often expose one. If present: **no wakelock at all**;
   the hub wakes the AP only on lux *change* — a dark pocket costs ~nothing.
2. **Non-wakeup ALS + `PARTIAL_WAKE_LOCK`** — the AOSP-documented pattern ("It is the
   responsibility of applications to keep a partial wake lock … while the screen is off").
   ~0.6–1.4 %/h AP-awake cost; lock held only while broadcasting.
3. If ColorOS defeats both (documented OEM failure mode: sensor value *frozen* at last reading,
   listener alive): documented finding, not a design failure — Pupil stays useful screen-on /
   as the calibration instrument, and the torch acceptance test (§8) is what detects this.

**Doze:** the battery-optimisation exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) is
load-bearing, not just ColorOS appeasement — Doze *ignores wakelocks*, and a phone stationary
on a desk (the primary use case) is exactly what Dozes. Advertising itself continues through
Doze regardless (controller-firmware offload), but the *update* path freezes; the receiver's
staleness contract (§5) is what makes that safe.

**Frozen-value corollary (known limitation):** a frozen sensor + live heartbeat advertises
stale lux with fresh packet ids — undetectable phone-side. Mitigation lives in iris (webcam
plausibility fusion, later) and in the torch test.

### 4b. ColorOS survival (dontkillmyapp, current-generation paths)

Documented in `android/README.md` as a setup checklist:
Settings → Battery → App battery management → Pupil: **Allow auto-launch**, **Allow foreground
activity**, **Allow background activity**, **Optimize battery use → off**; disable
**Sleep stand-by optimization**; **lock Pupil in recents** (also stops ColorOS silently
reverting the exemption); decline any "high background power consumption" prompt.
Acceptance test for "survivable": screen off 10 min → packet ids still advancing at the laptop.

### 4c. Advertising mechanics

- `BluetoothLeAdvertiser.startAdvertisingSet()`: legacy PDU, non-connectable, non-scannable.
- **Units trap:** `AdvertisingSetParameters.setInterval()` is in 0.625 ms units — 400 ms =
  `setInterval(640)`. (The constant named `INTERVAL_MEDIUM` is 250 ms.)
- Updates go through `advertisingSet.setAdvertisingData()` **in place** — never restart the
  set. No documented throttling; serialize updates on `onAdvertisingDataSet` (never fire blind).
- Recreate the set on `onAdvertisingSetStopped` (Bluetooth toggled off/on invalidates it).
- Battery: negligible — advertising is controller-offloaded (~0.01–0.02 %/h at 400 ms on this
  battery; a 52-h empirical beacon test measured ~0.11 %/h *total device* drain at 1 Hz).

## 5. Timing model — event-driven with a heartbeat

No polling timer. Downstream tempo (gsd-power EMA τ≈1.6 s; webcam daemon 2 s) makes sub-second
freshness pointless; the phone-side cost is CPU wakefulness, not updates; and light sensors are
on-change hardware anyway.

- **Sensor-driven:** `onSensorChanged` → deadband → rate limit → bump packet id →
  `setAdvertisingData`. Stable room ⇒ the app does nothing; firmware repeats the last advert.
- **Deadband:** ignore changes < max(1 lx, 5 %) — below the perceptual/EMA noise floor; this is
  the churn filter, not the update rate.
- **Rate limit:** ≥ 500 ms between payload updates, serialized on the completion callback.
- **Heartbeat:** every 10 s, bump the packet id and re-set the *same* lux (≈ one HCI command).
  Converts receiver-side silence from ambiguous into a crisp liveness contract — *fresh
  callback within 10 s or Pupil is stale* — and fails safe: Doze freezes the heartbeat too, so
  a Dozing phone is *detected* as stale rather than trusted.

**Proximity gating by physics (deliberate):** low TX power makes "audible to the laptop" ≈
"near the laptop" ≈ "lux relevant to the laptop", and body attenuation (~10–20 dB) makes a
pocketed phone — whose reading is garbage — naturally drop out. The precision knob is
receiver-side RSSI (continuous, tunable without touching the phone), TX power is the coarse
physical layer.

## 6. Config

One `PupilConfig` object (SharedPreferences) behind a plain settings screen; receiver gets
matching CLI flags. Defaults:

| Knob | Default | Why |
|---|---|---|
| Advertising interval | 400 ms (`setInterval(640)`) | ≪ gsd τ=1.6 s; slower saves ~nothing, faster buys nothing; kept for 2.4 GHz collision resilience. |
| TX power | `TX_POWER_LOW` (−15 dBm); `ULTRA_LOW`…`HIGH` selectable | Room-scale reach; proximity gating per §5. `ULTRA_LOW` risks flapping at desk distance (±10 dB indoor multipath swings). |
| Sensor delay | `SENSOR_DELAY_NORMAL` | ALS is on-change hardware; setting barely matters. |
| Deadband | max(1 lx, 5 %) | §5. |
| Min update gap | 500 ms | §5. |
| Heartbeat | 10 s | §5. |
| Receiver `--min-rssi` | −75 dBm, hysteresis (admit > −70, drop < −80) | Empirically recalibrated by the room walk (§8); hysteresis prevents boundary flap. |
| Receiver stale-after | 25 s (2.5× heartbeat) | Distinguishes "constant room" (heartbeats arriving) from "phone gone/Dozed". |
| Receiver scanner-dead | 30 s with no adverts *from anyone* → recreate scanner | HA watchdog pattern; covers rfkill/suspend/adapter races. |

## 7. Receiver — `python/scripts/ble_als_probe.py`

In the existing uv project; `uv add bleak bthome-ble`.

- **Active scanning** (verified workable today on this box: BlueZ 5.86, no config changes;
  passive needs `Experimental = true` in `/etc/bluetooth/main.conf` — a later power
  optimisation, not v1). `service_uuids=["0000fcd2-…"]` filter **plus client-side re-filter**:
  BlueZ merges all clients' discovery filters (GNOME's scan widens ours). Median update latency
  ≪ 400 ms; delivery fires on every service-data *change* (hence packet id).
- **Identify Pupil by payload** (BTHome service data + local name) — **never by MAC**: Android
  rotates the advertising address ~every 15 min.
- **Decode with `bthome-ble`** — the exact parser Home Assistant uses (actively maintained;
  packet-id dedup included). Guarantees byte-for-byte HA-compatible interpretation.
- **Three-state machine** (§6 timings): `FRESH` / `STALE` / `SCANNER_DEAD`, plus the RSSI
  hysteresis gate (below-gate adverts logged but treated as absent). Never conflate stale with
  dead — conflating them is how a frozen value gets trusted.
- **Structure:** decode + gate + state machine in a pure, pytest-able module
  (`python/src/iris/pupil.py`); the script is a thin bleak shell around it. That module is the
  seam the iris daemon imports later.
- **Output:** timestamped `lux / RSSI / packet-id / state` lines; `--csv` for calibration-walk
  logs; `--all` to dump every BTHome device seen (debug).
- **Coexistence:** BlueZ discovery sessions are per-client and refcounted — GNOME can't stop
  our scan, we can't stop its. `org.bluez.Error.InProgress` races → retry with backoff.

## 8. Build, install, acceptance

- **Toolchain (molly tiers):** JDK + Android `cmdline-tools` installed in the **dev toolbox**;
  SDK under `$HOME/Android/Sdk` (user-scoped, survives toolbox rebuilds); gradle wrapper checked
  in. `adb` over USB (developer mode); wireless-debugging pairing as fallback if USB-from-toolbox
  is fiddly.
- **Cross-language contract test:** shared golden hex fixtures — Kotlin JUnit asserts
  `BthomeEncoder` produces them; pytest decodes the same bytes with `bthome-ble` and asserts the
  values. Encoder and decoder cannot drift apart silently.
- **pytest:** state machine (fresh→stale→dead transitions, RSSI hysteresis) on synthetic events.
- **On-device acceptance sequence:**
  1. Sensor report: wakeup-ALS present? FIFO depth? → records which §4a rung the Find N6 gets.
  2. First-sighting smoke test (`bluetoothctl` / probe script).
  3. **Screen-off 10 min + torch test:** packet ids advancing *and* the advertised lux moves
     when a torch hits the phone — detects both ColorOS killing and the frozen-value mode.
  4. **RSSI room walk:** log RSSI at desk / sofa / next room at −15 dBm; set `--min-rssi`
     empirically.

## 9. Risks & knowns

| Risk | Standing |
|---|---|
| ColorOS kills the service or freezes the sensor screen-off | The open empirical question; §4a ladder + §4b checklist + torch test. Worst case: Pupil is a screen-on/calibration instrument. |
| Doze staleness (advert live, updates frozen) | Detected by design: heartbeat freezes too → receiver marks STALE. Exemption keeps rung 2 alive. |
| Frozen sensor value + live heartbeat = plausible stale lux | Undetectable phone-side; mitigated by torch test now, webcam fusion later. |
| BlueZ dedup silences constant readings | Solved: packet id (mandatory in our encoding). |
| MAC rotation breaks device tracking | Solved: identify by payload, never MAC. |
| GNOME/other scanners interfere | Refcounted sessions + client-side re-filter + InProgress retry. |
| Phone-side battery | Rung 1: ~nothing. Rung 2: ~0.6–1.4 %/h. Radio: ~0.01–0.02 %/h. Acceptable for a play project. |

## 10. Key sources

- BTHome v2 format: <https://bthome.io/format/>; HA parser: <https://github.com/Bluetooth-Devices/bthome-ble>
- AdvertisingSet semantics: <https://developer.android.com/reference/android/bluetooth/le/AdvertisingSet>;
  FGS types/timeouts: <https://developer.android.com/develop/background-work/services/fgs/timeout>
- Non-wakeup sensors & suspend: <https://source.android.com/docs/core/interaction/sensors/suspend-mode>;
  Doze: <https://developer.android.com/training/monitoring-device-state/doze-standby>
- ColorOS: <https://dontkillmyapp.com/oppo> (stale; current paths via /realme, /oneplus)
- bleak BlueZ backend & DuplicateData: <https://github.com/hbldh/bleak> (scanner.py, manager.py);
  BlueZ adapter API: <https://github.com/bluez/bluez/blob/master/doc/org.bluez.Adapter.rst>
- Beacon battery measurement: <https://developer.radiusnetworks.com/2015/12/09/battery-friendly-beacon-transmission>
