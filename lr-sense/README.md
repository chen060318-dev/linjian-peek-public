# LR Sense

Headless environment-sensing service for ChatGPT.

No front-end UI is required. The phone uploads a small sensor snapshot to Render, and ChatGPT reads the latest state through a read-only MCP tool.

## Current MVP

- battery percentage
- charging state
- Android ambient-light lux
- optional scene label, e.g. `寝室`, `家`, `在外面`
- latitude/longitude upload
- Open-Meteo current weather lookup
- coordinates returned to ChatGPT are rounded to 3 decimals
- read-only MCP tool: `get_environment_snapshot`

No raw microphone audio is accepted in v0.1.

## Render setup

Create a new Render Web Service from this repository and select branch:

`lr-sense-service`

Set **Root Directory** to:

`lr-sense`

Use:

- Runtime: Node
- Build Command: `npm install --include=dev && npm run build`
- Start Command: `npm start`
- Health Check Path: `/health`

Add two secret environment variables:

- `MCP_API_TOKEN` — used only by ChatGPT
- `SENSOR_API_TOKEN` — used only by the phone uploader

Generate two different long random values.

The public MCP endpoint will be:

`https://<your-render-service>.onrender.com/mcp`

The phone upload endpoint will be:

`https://<your-render-service>.onrender.com/sensor/update`

## Test a phone upload

```bash
curl -X POST "https://<your-render-service>.onrender.com/sensor/update" \
  -H "Authorization: Bearer YOUR_SENSOR_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "batteryPercent": 72,
    "charging": false,
    "lightLux": 38,
    "latitude": 27.99,
    "longitude": 120.70,
    "scene": "寝室"
  }'
```

## ChatGPT plugin connection

Connect the remote MCP endpoint with Bearer authentication using `MCP_API_TOKEN`.

The server exposes only one tool in v0.1:

- `get_environment_snapshot`

## Important MVP limitation

The latest sensor snapshot is currently held in memory. On a Render restart or free-tier cold start, the snapshot is empty until the phone uploads again.

That is intentional for the first proof-of-concept. Once the round-trip works, add persistent storage only if it is actually needed.
