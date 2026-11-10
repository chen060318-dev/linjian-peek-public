#!/data/data/com.termux/files/usr/bin/python
import json
import os
import shutil
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

def run_text(args, timeout=20):
    try:
        return subprocess.check_output(
            args,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=timeout,
        ).strip()
    except Exception:
        return ""

def run_json(args, timeout=20):
    out = run_text(args, timeout=timeout)
    if not out:
        return None
    try:
        return json.loads(out)
    except Exception:
        return None

def number(value):
    return value if isinstance(value, (int, float)) else None

def read_battery():
    data = run_json(["termux-battery-status"], timeout=10)
    if not isinstance(data, dict):
        return {}

    status = str(data.get("status", "")).upper()
    plugged = str(data.get("plugged", "")).upper()
    charging = status in {"CHARGING", "FULL"} or (
        bool(plugged) and plugged != "UNPLUGGED"
    )

    result = {"charging": bool(charging)}

    mapping = {
        "health": "batteryHealth",
        "plugged": "batteryPlugged",
        "status": "batteryStatus",
        "temperature": "batteryTemperatureC",
        "current": "batteryCurrentUa",
        "percentage": "batteryPercent",
    }
    for source, target in mapping.items():
        value = data.get(source)
        if value is None:
            continue
        if source in {"health", "plugged", "status"}:
            result[target] = str(value)
        elif isinstance(value, (int, float)):
            result[target] = float(value)

    return result

def getprop(key):
    value = run_text(["getprop", key], timeout=5)
    return value or None

def read_device_static():
    result = {}

    fields = {
        "deviceModel": getprop("ro.product.model"),
        "deviceManufacturer": getprop("ro.product.manufacturer"),
        "androidVersion": getprop("ro.build.version.release"),
        "timezone": getprop("persist.sys.timezone"),
    }
    for key, value in fields.items():
        if value:
            result[key] = value

    sdk = getprop("ro.build.version.sdk")
    if sdk:
        try:
            result["sdkInt"] = int(sdk)
        except ValueError:
            pass

    return result

def read_uptime():
    try:
        with open("/proc/uptime", "r", encoding="utf-8") as f:
            return {"uptimeSeconds": float(f.read().split()[0])}
    except Exception:
        return {}

def read_memory():
    values = {}
    try:
        with open("/proc/meminfo", "r", encoding="utf-8") as f:
            for line in f:
                if ":" not in line:
                    continue
                key, rest = line.split(":", 1)
                if key in {"MemTotal", "MemAvailable"}:
                    parts = rest.strip().split()
                    if parts:
                        values[key] = float(parts[0]) / 1024.0
    except Exception:
        return {}

    result = {}
    if "MemTotal" in values:
        result["memoryTotalMb"] = round(values["MemTotal"], 1)
    if "MemAvailable" in values:
        result["memoryAvailableMb"] = round(values["MemAvailable"], 1)
    return result

def read_storage():
    try:
        usage = shutil.disk_usage(os.path.expanduser("~"))
        gb = 1024 ** 3
        return {
            "storageTotalGb": round(usage.total / gb, 2),
            "storageFreeGb": round(usage.free / gb, 2),
        }
    except Exception:
        return {}

def read_brightness():
    value = run_text(["settings", "get", "system", "screen_brightness"], timeout=5)
    try:
        parsed = float(value)
        if parsed >= 0:
            return {"screenBrightness": parsed}
    except Exception:
        pass
    return {}

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
            "User-Agent": "LR-Sense-Termux/0.2",
        },
    )
    with urllib.request.urlopen(req, timeout=20) as response:
        return response.status, response.read().decode("utf-8", errors="replace")

device_static = read_device_static()
last_location = {}
last_location_at = 0.0

print("LR Sense collector v0.2 started. Ctrl+C to stop.")

while True:
    started = time.time()
    payload = {}

    payload.update(device_static)
    payload.update(read_battery())
    payload.update(read_uptime())
    payload.update(read_memory())
    payload.update(read_storage())
    payload.update(read_brightness())

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

    try:
        status, _ = post(payload)
        print(
            datetime.now().strftime("%H:%M:%S"),
            "uploaded",
            status,
            "battery=" + str(payload.get("batteryPercent")),
            "temp=" + str(payload.get("batteryTemperatureC")),
            "freeMB=" + str(payload.get("memoryAvailableMb")),
            "light=" + str(payload.get("lightLux")),
        )
    except urllib.error.HTTPError as exc:
        print("HTTP error:", exc.code, exc.read().decode("utf-8", errors="replace"))
    except Exception as exc:
        print("Upload failed:", exc)

    elapsed = time.time() - started
    time.sleep(max(5, UPLOAD_INTERVAL - elapsed))
