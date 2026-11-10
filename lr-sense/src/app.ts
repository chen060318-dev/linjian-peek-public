import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createMcpExpressApp } from "@modelcontextprotocol/sdk/server/express.js";
import type { Request, Response, NextFunction } from "express";
import { timingSafeEqual } from "node:crypto";
import { z } from "zod/v4";

const MCP_API_TOKEN = process.env.MCP_API_TOKEN;
const SENSOR_API_TOKEN = process.env.SENSOR_API_TOKEN;
const RENDER_EXTERNAL_HOSTNAME = process.env.RENDER_EXTERNAL_HOSTNAME;
const DEFAULT_LATITUDE = Number(process.env.DEFAULT_LATITUDE ?? "");
const DEFAULT_LONGITUDE = Number(process.env.DEFAULT_LONGITUDE ?? "");
const DEFAULT_SCENE = process.env.DEFAULT_SCENE?.trim() || null;

function hasDefaultLocation() {
  return Number.isFinite(DEFAULT_LATITUDE) && Number.isFinite(DEFAULT_LONGITUDE);
}

type SensorState = {
  batteryPercent: number | null;
  charging: boolean | null;
  lightLux: number | null;
  latitude: number | null;
  longitude: number | null;
  scene: string | null;
  capturedAt: string;
  receivedAt: string;
};

type WeatherState = {
  temperatureC: number | null;
  apparentTemperatureC: number | null;
  humidityPercent: number | null;
  precipitationMm: number | null;
  weatherCode: number | null;
  cloudCoverPercent: number | null;
  windSpeedKmh: number | null;
  isDay: boolean | null;
  summary: string | null;
};

let latestSensor: SensorState | null = null;
let weatherCache:
  | { key: string; fetchedAtMs: number; value: WeatherState }
  | null = null;

function secureTokenMatch(actualHeader: string | undefined, token: string | undefined) {
  if (!actualHeader || !token) return false;
  const expected = `Bearer ${token}`;
  if (actualHeader.length !== expected.length) return false;
  return timingSafeEqual(Buffer.from(actualHeader), Buffer.from(expected));
}

function weatherSummary(code: number | null): string | null {
  if (code === null) return null;
  if (code === 0) return "晴";
  if ([1, 2].includes(code)) return "晴间多云";
  if (code === 3) return "阴";
  if ([45, 48].includes(code)) return "雾";
  if ([51, 53, 55, 56, 57].includes(code)) return "毛毛雨";
  if ([61, 63, 65, 66, 67, 80, 81, 82].includes(code)) return "雨";
  if ([71, 73, 75, 77, 85, 86].includes(code)) return "雪";
  if ([95, 96, 99].includes(code)) return "雷雨";
  return "天气变化";
}

function lightSummary(lux: number | null) {
  if (lux === null) return null;
  if (lux < 10) return "很暗";
  if (lux < 50) return "偏暗";
  if (lux < 200) return "柔和";
  if (lux < 500) return "明亮";
  return "很亮";
}

async function getWeather(lat: number, lon: number): Promise<WeatherState> {
  const roundedLat = Math.round(lat * 1000) / 1000;
  const roundedLon = Math.round(lon * 1000) / 1000;
  const key = `${roundedLat},${roundedLon}`;
  const now = Date.now();

  if (weatherCache && weatherCache.key === key && now - weatherCache.fetchedAtMs < 5 * 60_000) {
    return weatherCache.value;
  }

  const params = new URLSearchParams({
    latitude: String(lat),
    longitude: String(lon),
    current:
      "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,cloud_cover,wind_speed_10m,is_day",
    timezone: "auto",
  });

  const response = await fetch(`https://api.open-meteo.com/v1/forecast?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Weather request failed with HTTP ${response.status}`);
  }

  const body = (await response.json()) as {
    current?: Record<string, number | undefined>;
  };
  const current = body.current ?? {};
  const code = typeof current.weather_code === "number" ? current.weather_code : null;

  const value: WeatherState = {
    temperatureC:
      typeof current.temperature_2m === "number" ? current.temperature_2m : null,
    apparentTemperatureC:
      typeof current.apparent_temperature === "number"
        ? current.apparent_temperature
        : null,
    humidityPercent:
      typeof current.relative_humidity_2m === "number"
        ? current.relative_humidity_2m
        : null,
    precipitationMm:
      typeof current.precipitation === "number" ? current.precipitation : null,
    weatherCode: code,
    cloudCoverPercent:
      typeof current.cloud_cover === "number" ? current.cloud_cover : null,
    windSpeedKmh:
      typeof current.wind_speed_10m === "number" ? current.wind_speed_10m : null,
    isDay: typeof current.is_day === "number" ? current.is_day === 1 : null,
    summary: weatherSummary(code),
  };

  weatherCache = { key, fetchedAtMs: now, value };
  return value;
}

async function buildSnapshot() {
  if (!latestSensor) {
    return {
      available: false,
      reason: "No sensor upload has been received yet.",
    };
  }

  const ageSeconds = Math.max(
    0,
    Math.round((Date.now() - Date.parse(latestSensor.receivedAt)) / 1000),
  );
  const stale = ageSeconds > 10 * 60;

  let weather: WeatherState | null = null;
  let weatherError: string | null = null;

  const weatherLat =
    latestSensor.latitude !== null
      ? latestSensor.latitude
      : hasDefaultLocation()
        ? DEFAULT_LATITUDE
        : null;
  const weatherLon =
    latestSensor.longitude !== null
      ? latestSensor.longitude
      : hasDefaultLocation()
        ? DEFAULT_LONGITUDE
        : null;

  if (weatherLat !== null && weatherLon !== null) {
    try {
      weather = await getWeather(weatherLat, weatherLon);
    } catch (error) {
      weatherError = error instanceof Error ? error.message : "Weather lookup failed";
    }
  }

  const roundedLocation =
    latestSensor.latitude !== null && latestSensor.longitude !== null
      ? {
          latitude: Math.round(latestSensor.latitude * 1000) / 1000,
          longitude: Math.round(latestSensor.longitude * 1000) / 1000,
        }
      : null;

  const feelParts: string[] = [];
  const light = lightSummary(latestSensor.lightLux);
  if (light) feelParts.push(`周围光线${light}`);
  if (weather?.summary) feelParts.push(`外面是${weather.summary}`);
  if (weather?.apparentTemperatureC !== null && weather?.apparentTemperatureC !== undefined) {
    feelParts.push(`体感约${weather.apparentTemperatureC}℃`);
  }
  if (weather?.humidityPercent !== null && weather?.humidityPercent !== undefined) {
    feelParts.push(`湿度${weather.humidityPercent}%`);
  }

  return {
    available: true,
    stale,
    ageSeconds,
    scene: latestSensor.scene ?? DEFAULT_SCENE,
    capturedAt: latestSensor.capturedAt,
    receivedAt: latestSensor.receivedAt,
    device: {
      batteryPercent: latestSensor.batteryPercent,
      charging: latestSensor.charging,
    },
    ambient: {
      lightLux: latestSensor.lightLux,
      lightDescription: light,
    },
    location: roundedLocation,
    weatherLocationSource:
      latestSensor.latitude !== null && latestSensor.longitude !== null
        ? "phone"
        : hasDefaultLocation()
          ? "server_default"
          : null,
    weather,
    weatherError,
    feelHint: feelParts.length ? feelParts.join("，") : null,
    privacyNote:
      "Coordinates exposed to ChatGPT are rounded to 3 decimals; exact uploaded coordinates stay server-side.",
  };
}

const sensorInput = z
  .object({
    batteryPercent: z.number().min(0).max(100).optional(),
    charging: z.boolean().optional(),
    lightLux: z.number().min(0).max(200000).optional(),
    latitude: z.number().min(-90).max(90).optional(),
    longitude: z.number().min(-180).max(180).optional(),
    scene: z.string().trim().min(1).max(80).optional(),
    capturedAt: z.string().datetime().optional(),
  })
  .superRefine((value, ctx) => {
    const hasLat = value.latitude !== undefined;
    const hasLon = value.longitude !== undefined;
    if (hasLat !== hasLon) {
      ctx.addIssue({
        code: "custom",
        message: "latitude and longitude must be provided together",
      });
    }
    if (
      value.batteryPercent === undefined &&
      value.charging === undefined &&
      value.lightLux === undefined &&
      !hasLat &&
      value.scene === undefined
    ) {
      ctx.addIssue({
        code: "custom",
        message: "at least one sensor field is required",
      });
    }
  });

export function createServer(): McpServer {
  const server = new McpServer(
    { name: "lr-sense", version: "0.1.0" },
    { capabilities: { logging: {} } },
  );

  server.registerTool(
    "get_environment_snapshot",
    {
      description:
        "Read the user's latest headless phone environment snapshot: battery, charging state, ambient light, coarse location, current weather, data freshness, and a short sensory hint. Use when the user asks about their surroundings, phone battery, room brightness, local weather context, or wants environment-aware conversation. This tool is read-only.",
      inputSchema: {},
    },
    async () => {
      const snapshot = await buildSnapshot();
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(snapshot, null, 2),
          },
        ],
      };
    },
  );

  return server;
}

export const app = createMcpExpressApp({
  host: "0.0.0.0",
  allowedHosts: RENDER_EXTERNAL_HOSTNAME ? [RENDER_EXTERNAL_HOSTNAME] : undefined,
});

app.get("/health", (_req: Request, res: Response) => {
  res.json({
    status: "ok",
    service: "lr-sense",
    hasSensorData: latestSensor !== null,
  });
});

function sensorAuth(req: Request, res: Response, next: NextFunction) {
  if (!SENSOR_API_TOKEN) {
    res.status(503).json({ ok: false, error: "SENSOR_API_TOKEN is not configured" });
    return;
  }
  if (!secureTokenMatch(req.headers.authorization, SENSOR_API_TOKEN)) {
    res.status(401).json({ ok: false, error: "Unauthorized" });
    return;
  }
  next();
}

function mcpAuth(req: Request, res: Response, next: NextFunction) {
  if (!MCP_API_TOKEN) {
    res.status(503).json({
      jsonrpc: "2.0",
      error: { code: -32002, message: "MCP_API_TOKEN is not configured" },
      id: null,
    });
    return;
  }
  if (!secureTokenMatch(req.headers.authorization, MCP_API_TOKEN)) {
    res.status(401).json({
      jsonrpc: "2.0",
      error: { code: -32001, message: "Unauthorized" },
      id: null,
    });
    return;
  }
  next();
}

app.post("/sensor/update", sensorAuth, (req: Request, res: Response) => {
  const parsed = sensorInput.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({
      ok: false,
      error: "Invalid sensor payload",
      details: parsed.error.issues,
    });
    return;
  }

  const now = new Date().toISOString();
  const incoming = parsed.data;

  latestSensor = {
    batteryPercent:
      incoming.batteryPercent ?? latestSensor?.batteryPercent ?? null,
    charging: incoming.charging ?? latestSensor?.charging ?? null,
    lightLux: incoming.lightLux ?? latestSensor?.lightLux ?? null,
    latitude: incoming.latitude ?? latestSensor?.latitude ?? null,
    longitude: incoming.longitude ?? latestSensor?.longitude ?? null,
    scene: incoming.scene ?? latestSensor?.scene ?? null,
    capturedAt: incoming.capturedAt ?? now,
    receivedAt: now,
  };

  res.json({ ok: true, receivedAt: now });
});

app.post("/mcp", mcpAuth, async (req: Request, res: Response) => {
  const server = createServer();
  try {
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
    });
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
    res.on("close", () => {
      transport.close();
      server.close();
    });
  } catch (error) {
    console.error("Error handling MCP request:", error);
    if (!res.headersSent) {
      res.status(500).json({
        jsonrpc: "2.0",
        error: { code: -32603, message: "Internal server error" },
        id: null,
      });
    }
  }
});

app.get("/mcp", (_req: Request, res: Response) => {
  res.status(405).json({
    jsonrpc: "2.0",
    error: { code: -32000, message: "Method not allowed" },
    id: null,
  });
});

app.delete("/mcp", (_req: Request, res: Response) => {
  res.status(405).json({
    jsonrpc: "2.0",
    error: { code: -32000, message: "Method not allowed" },
    id: null,
  });
});
