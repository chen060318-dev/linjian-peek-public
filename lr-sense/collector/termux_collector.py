#!/data/data/com.termux/files/usr/bin/python
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

BASE_URL = os.environ.get("LR_SENSE_URL", "").rstrip("/")
TOKEN = os.environ.get("SENSOR_API_TOKEN", "")
SCENE = os.environ.get("LR_SCENE", "")
UPLOAD_INTERVAL = int(os.environ.get("LR_UPLOAD_INTERVAL", "60"))
LOCATION_INTERVAL = int(os.environ.get("LR_LOCATION_INTERVAL", "300"))
ENABLE_LIGHT = os.environ.get("LR_ENABLE_LIGHT", "0") == "1"
ENABLE_LOCATION = os.environ.get("LR_ENABLE_LOCATION", "0") == "1"

if not BASE_URL or not TOKEN:
    raise SystemExit(
        "Missing LR_SENSE_URL or SENSOR_API_TOKEN. "
        "Export both before starting the collector."
    )

def run_json(args, timeout=20):
    try:
        out = subprocess.check_output(
            args,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=timeout,
        )
        return json.loads(out)
    except Exception:
        return None

def read_battery():
    data = run_json(["termux-battery-status"], timeout=10)
    if not isinstance(data, dict):
        return {}
    status = str(data.get("status", "")).upper()
    plugged = str(data.get("plugged", "")).upper()
    charging = status in {"CHARGING", "FULL"} or (
        plugged and plugged != "UNPLUGGED"
    )
    result = {}
    if isinstance(data.get("percentage"), (int, float)):
        result["batteryPercent"] = float(data["percentage"])
    result["charging"] = bool(charging)
    return result

def read_light():
    data = run_json(["termux-sensor", "-s", "Light", "-n", "1"], timeout=10)
    if not isinstance(data, dict):
        return {}
    for sensor in data.values():
        if isinstance(sensor, dict):
            values = sensor.get("values")
            if isinstance(values, list) and values and isinstance(values[0], (int, float)):
                return {"lightLux": float(values[0])}
    return {}

def read_location():
    data = run_json(
        ["termux-location", "-p", "network", "-r", "once"],
        timeout=25,
    )
    if not isinstance(data, dict):
        return {}
    lat = data.get("latitude")
    lon = data.get("longitude")
    if isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
        return {"latitude": float(lat), "longitude": float(lon)}
    return {}

def post(payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + "/sensor/update",
        data=body,
        method="POST",
        headers={
            "Authorization": "Bearer " + TOKEN,
            "Content-Type": "application/json",
            "User-Agent": "LR-Sense-Termux/0.1",
        },
    )
    with urllib.request.urlopen(req, timeout=20) as response:
        return response.status, response.read().decode("utf-8", errors="replace")

last_location = {}
last_location_at = 0.0

print("LR Sense collector started. Ctrl+C to stop.")

while True:
    started = time.time()
    payload = {}
    payload.update(read_battery())
    if ENABLE_LIGHT:
        payload.update(read_light())

    if ENABLE_LOCATION and (not last_location or started - last_location_at >= LOCATION_INTERVAL):
        fresh_location = read_location()
        if fresh_location:
            last_location = fresh_location
            last_location_at = started

    payload.update(last_location)

    if SCENE:
        payload["scene"] = SCENE

    payload["capturedAt"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    if payload:
        try:
            status, text = post(payload)
            print(
                datetime.now().strftime("%H:%M:%S"),
                "uploaded",
                status,
                payload.get("batteryPercent"),
                payload.get("lightLux"),
            )
        except urllib.error.HTTPError as exc:
            print("HTTP error:", exc.code, exc.read().decode("utf-8", errors="replace"))
        except Exception as exc:
            print("Upload failed:", exc)

    elapsed = time.time() - started
    time.sleep(max(5, UPLOAD_INTERVAL - elapsed))
