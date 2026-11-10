# Headless Android collector (Termux)

This is the no-UI phone side for LR Sense.

## Requirements

Install the Termux:API Android add-on from the same trusted source/signing family as your Termux app, then in Termux:

```bash
pkg update
pkg install python termux-api
```

Test the APIs:

```bash
termux-battery-status
termux-sensor -l
termux-sensor -s Light -n 1
termux-location -p network -r once
```

Android will ask for location permission when needed.

## Configure

After Render creates the service, export:

```bash
export LR_SENSE_URL="https://YOUR-SERVICE.onrender.com"
export SENSOR_API_TOKEN="YOUR_SENSOR_TOKEN"
export LR_SCENE="寝室"
```

Optional tuning:

```bash
export LR_UPLOAD_INTERVAL=60
export LR_LOCATION_INTERVAL=300
```

## Start

```bash
termux-wake-lock
python termux_collector.py
```

The collector has no front-end. It uploads battery + light roughly once per minute and refreshes network location roughly every five minutes.

For the first MVP, keep the Termux process alive manually. After the round-trip works, add Termux:Boot or an Android foreground service for automatic startup.
