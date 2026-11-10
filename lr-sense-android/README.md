# LR Sense Sensor

A tiny standalone Android sensor uploader for LR Sense.

It is intentionally separate from the 掌心窗 UI. The only visible screen is a one-time setup page for the Render URL and SENSOR_API_TOKEN. After starting, it runs as a foreground service and uploads:

- ambient light (lux)
- coarse motion label: still / moving / active
- optional phone location for weather and air quality
- battery state and temperature
- phone model / Android version
- uptime, memory, storage, screen brightness

No microphone/audio is used in v0.1.

Endpoint:
- POST /sensor/update

The foreground notification shows whether Render is reachable and the latest light reading.
