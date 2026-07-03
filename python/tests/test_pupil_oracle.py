"""Golden vectors decoded by bthome-ble — the exact parser Home Assistant uses.

If this passes, anything that understands BTHome (HA, Theengs) reads Pupil's
adverts identically to our own decoder. A fresh parser per case: bthome-ble
dedups repeated packet ids within one parser instance.
"""

import json
from pathlib import Path

from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData
from bthome_ble import BTHomeBluetoothDeviceData
from habluetooth import BluetoothServiceInfoBleak
from pytest import approx

from iris.pupil import BTHOME_SERVICE_UUID

GOLDEN = json.loads(
    (Path(__file__).parent / ".." / ".." / "contract" / "bthome-golden.json").read_text()
)


def _service_info(payload: bytes) -> BluetoothServiceInfoBleak:
    # bthome-ble's public API takes a `BluetoothServiceInfoBleak` (from `habluetooth`),
    # not the `home_assistant_bluetooth.BluetoothServiceInfo` the brief guessed — the
    # installed 3.23.4 needs the richer Bleak-backed type (it reads `.time` off it via
    # `BTHomeData.get_time`), which in turn wants a real `BLEDevice`/`AdvertisementData`.
    service_data = {BTHOME_SERVICE_UUID: payload}
    device = BLEDevice(address="AA:BB:CC:DD:EE:FF", name="Pupil", details={})
    advertisement = AdvertisementData(
        local_name="Pupil",
        manufacturer_data={},
        service_data=service_data,
        service_uuids=[],
        tx_power=None,
        rssi=-60,
        platform_data=(),
    )
    return BluetoothServiceInfoBleak(
        name="Pupil",
        address="AA:BB:CC:DD:EE:FF",
        rssi=-60,
        manufacturer_data={},
        service_data=service_data,
        service_uuids=[],
        source="local",
        device=device,
        advertisement=advertisement,
        connectable=True,
        time=0.0,
        tx_power=None,
        raw=None,
    )


def test_bthome_ble_oracle_decodes_golden_vectors() -> None:
    for case in GOLDEN["cases"]:
        parser = BTHomeBluetoothDeviceData()
        update = parser.update(_service_info(bytes.fromhex(case["service_data_hex"])))
        lux_values = [
            v.native_value for key, v in update.entity_values.items() if key.key == "illuminance"
        ]
        assert lux_values, f"oracle saw no illuminance: {case['description']}"
        assert lux_values[0] == approx(case["decoded_lux"]), case["description"]
