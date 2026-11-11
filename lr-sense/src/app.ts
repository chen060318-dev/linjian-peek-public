import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createMcpExpressApp } from "@modelcontextprotocol/sdk/server/express.js";
import type { Request, Response, NextFunction } from "express";
import { timingSafeEqual } from "node:crypto";
import { z } from "zod/v4";

const MCP_API_TOKEN = process.env.MCP_API_TOKEN;
const MCP_URL_SECRET = process.env.MCP_URL_SECRET;
const SENSOR_API_TOKEN = process.env.SENSOR_API_TOKEN;
const RENDER_EXTERNAL_HOSTNAME = process.env.RENDER_EXTERNAL_HOSTNAME;
const DEFAULT_LATITUDE_RAW = process.env.DEFAULT_LATITUDE?.trim() || null;
const DEFAULT_LONGITUDE_RAW = process.env.DEFAULT_LONGITUDE?.trim() || null;
const DEFAULT_LATITUDE = DEFAULT_LATITUDE_RAW === null ? NaN : Number(DEFAULT_LATITUDE_RAW);
const DEFAULT_LONGITUDE = DEFAULT_LONGITUDE_RAW === null ? NaN : Number(DEFAULT_LONGITUDE_RAW);
const DEFAULT_SCENE = process.env.DEFAULT_SCENE?.trim() || null;

function hasDefaultLocation() {
  return (
    DEFAULT_LATITUDE_RAW !== null &&
    DEFAULT_LONGITUDE_RAW !== null &&
    Number.isFinite(DEFAULT_LATITUDE) &&
    Number.isFinite(DEFAULT_LONGITUDE)
  );
}

type SensorState = {
  batteryPercent: number | null;
  charging: boolean | null;
  batteryHealth: string | null;
  batteryPlugged: string | null;
  batteryStatus: string | null;
  batteryTemperatureC: number | null;
  batteryCurrentUa: number | null;

  deviceModel: string | null;
  deviceManufacturer: string | null;
  androidVersion: string | null;
  sdkInt: number | null;
  timezone: string | null;

  uptimeSeconds: number | null;
  memoryTotalMb: number | null;
  memoryAvailableMb: number | null;
  storageTotalGb: number | null;
  storageFreeGb: number | null;
  screenBrightness: number | null;

  lightLux: number | null;
  lightSensorDiagnostics: string | null;
  lightSensorEvents: string | null;
  soundScene: string | null;
  soundConfidence: number | null;
  motionState: string | null;

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
  precipitationProbabilityPercent: number | null;
  weatherCode: number | null;
  cloudCoverPercent: number | null;
  visibilityM: number | null;
  windSpeedKmh: number | null;
  windGustsKmh: number | null;
  windDirectionDeg: number | null;
  isDay: boolean | null;
  summary: string | null;
  sunrise: string | null;
  sunset: string | null;
  uvIndexMax: number | null;
};

type AirQualityState = {
  europeanAqi: number | null;
  usAqi: number | null;
  pm25: number | null;
  pm10: number | null;
  ozone: number | null;
  nitrogenDioxide: number | null;
};

let latestSensor: SensorState | null = null;
let weatherCache:
  | { key: string; fetchedAtMs: number; value: WeatherState }
  | null = null;
let airCache:
  | { key: string; fetchedAtMs: number; value: AirQualityState }
  | null = null;

function secureTokenMatch(actualHeader: string | undefined, token: string | undefined) {
  if (!actualHeader || !token) return false;
  const expected = `Bearer ${token}`;
  if (actualHeader.length !== expected.length) return false;
  return timingSafeEqual(Buffer.from(actualHeader), Buffer.from(expected));
}

function secureRawMatch(actual: string | undefined, expected: string | undefined) {
  if (!actual || !expected) return false;
  if (actual.length !== expected.length) return false;
  return timingSafeEqual(Buffer.from(actual), Buffer.from(expected));
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

function batteryThermalSummary(temp: number | null) {
  if (temp === null) return null;
  if (temp < 10) return "偏冷";
  if (temp < 35) return "温度正常";
  if (temp < 42) return "有点热";
  return "偏热";
}

function getResolvedLocation() {
  if (
    latestSensor &&
    latestSensor.latitude !== null &&
    latestSensor.longitude !== null
  ) {
    return {
      latitude: latestSensor.latitude,
      longitude: latestSensor.longitude,
      source: "phone" as const,
    };
  }
  if (hasDefaultLocation()) {
    return {
      latitude: DEFAULT_LATITUDE,
      longitude: DEFAULT_LONGITUDE,
      source: "server_default" as const,
    };
  }
  return null;
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
      "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,precipitation_probability,weather_code,cloud_cover,visibility,wind_speed_10m,wind_gusts_10m,wind_direction_10m,is_day",
    daily: "sunrise,sunset,uv_index_max",
    timezone: "auto",
    forecast_days: "1",
  });

  const response = await fetch(`https://api.open-meteo.com/v1/forecast?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Weather request failed with HTTP ${response.status}`);
  }

  const body = (await response.json()) as {
    current?: Record<string, number | undefined>;
    daily?: {
      sunrise?: string[];
      sunset?: string[];
      uv_index_max?: number[];
    };
  };
  const current = body.current ?? {};
  const daily = body.daily ?? {};
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
    precipitationProbabilityPercent:
      typeof current.precipitation_probability === "number"
        ? current.precipitation_probability
        : null,
    weatherCode: code,
    cloudCoverPercent:
      typeof current.cloud_cover === "number" ? current.cloud_cover : null,
    visibilityM:
      typeof current.visibility === "number" ? current.visibility : null,
    windSpeedKmh:
      typeof current.wind_speed_10m === "number" ? current.wind_speed_10m : null,
    windGustsKmh:
      typeof current.wind_gusts_10m === "number" ? current.wind_gusts_10m : null,
    windDirectionDeg:
      typeof current.wind_direction_10m === "number" ? current.wind_direction_10m : null,
    isDay: typeof current.is_day === "number" ? current.is_day === 1 : null,
    summary: weatherSummary(code),
    sunrise: Array.isArray(daily.sunrise) ? daily.sunrise[0] ?? null : null,
    sunset: Array.isArray(daily.sunset) ? daily.sunset[0] ?? null : null,
    uvIndexMax: Array.isArray(daily.uv_index_max)
      ? daily.uv_index_max[0] ?? null
      : null,
  };

  weatherCache = { key, fetchedAtMs: now, value };
  return value;
}

async function getAirQuality(lat: number, lon: number): Promise<AirQualityState> {
  const roundedLat = Math.round(lat * 1000) / 1000;
  const roundedLon = Math.round(lon * 1000) / 1000;
  const key = `${roundedLat},${roundedLon}`;
  const now = Date.now();

  if (airCache && airCache.key === key && now - airCache.fetchedAtMs < 15 * 60_000) {
    return airCache.value;
  }

  const params = new URLSearchParams({
    latitude: String(lat),
    longitude: String(lon),
    current:
      "european_aqi,us_aqi,pm2_5,pm10,ozone,nitrogen_dioxide",
    timezone: "auto",
  });

  const response = await fetch(
    `https://air-quality-api.open-meteo.com/v1/air-quality?${params.toString()}`,
  );
  if (!response.ok) {
    throw new Error(`Air-quality request failed with HTTP ${response.status}`);
  }

  const body = (await response.json()) as {
    current?: Record<string, number | undefined>;
  };
  const current = body.current ?? {};

  const value: AirQualityState = {
    europeanAqi:
      typeof current.european_aqi === "number" ? current.european_aqi : null,
    usAqi: typeof current.us_aqi === "number" ? current.us_aqi : null,
    pm25: typeof current.pm2_5 === "number" ? current.pm2_5 : null,
    pm10: typeof current.pm10 === "number" ? current.pm10 : null,
    ozone: typeof current.ozone === "number" ? current.ozone : null,
    nitrogenDioxide:
      typeof current.nitrogen_dioxide === "number" ? current.nitrogen_dioxide : null,
  };

  airCache = { key, fetchedAtMs: now, value };
  return value;
}

function dataFreshness() {
  if (!latestSensor) {
    return {
      online: false,
      stale: true,
      ageSeconds: null,
      lastSeenAt: null,
    };
  }
  const ageSeconds = Math.max(
    0,
    Math.round((Date.now() - Date.parse(latestSensor.receivedAt)) / 1000),
  );
  return {
    online: ageSeconds <= 180,
    stale: ageSeconds > 600,
    ageSeconds,
    lastSeenAt: latestSensor.receivedAt,
  };
}

async function buildSnapshot() {
  if (!latestSensor) {
    return {
      available: false,
      reason: "No sensor upload has been received yet.",
      presence: dataFreshness(),
    };
  }

  const loc = getResolvedLocation();
  let weather: WeatherState | null = null;
  let airQuality: AirQualityState | null = null;
  let weatherError: string | null = null;
  let airQualityError: string | null = null;

  if (loc) {
    try {
      weather = await getWeather(loc.latitude, loc.longitude);
    } catch (error) {
      weatherError = error instanceof Error ? error.message : "Weather lookup failed";
    }
    try {
      airQuality = await getAirQuality(loc.latitude, loc.longitude);
    } catch (error) {
      airQualityError =
        error instanceof Error ? error.message : "Air-quality lookup failed";
    }
  }

  const light = lightSummary(latestSensor.lightLux);
  const feelParts: string[] = [];
  if (light) feelParts.push(`周围光线${light}`);
  if (weather?.summary) feelParts.push(`外面是${weather.summary}`);
  if (weather?.apparentTemperatureC !== null && weather?.apparentTemperatureC !== undefined) {
    feelParts.push(`体感约${weather.apparentTemperatureC}℃`);
  }
  if (weather?.humidityPercent !== null && weather?.humidityPercent !== undefined) {
    feelParts.push(`湿度${weather.humidityPercent}%`);
  }
  const thermal = batteryThermalSummary(latestSensor.batteryTemperatureC);
  if (thermal) feelParts.push(`手机电池${thermal}`);

  return {
    available: true,
    presence: dataFreshness(),
    scene: latestSensor.scene ?? DEFAULT_SCENE,
    capturedAt: latestSensor.capturedAt,
    device: {
      model: latestSensor.deviceModel,
      manufacturer: latestSensor.deviceManufacturer,
      androidVersion: latestSensor.androidVersion,
      sdkInt: latestSensor.sdkInt,
      timezone: latestSensor.timezone,
      uptimeSeconds: latestSensor.uptimeSeconds,
      memoryTotalMb: latestSensor.memoryTotalMb,
      memoryAvailableMb: latestSensor.memoryAvailableMb,
      storageTotalGb: latestSensor.storageTotalGb,
      storageFreeGb: latestSensor.storageFreeGb,
      screenBrightness: latestSensor.screenBrightness,
    },
    battery: {
      percent: latestSensor.batteryPercent,
      charging: latestSensor.charging,
      health: latestSensor.batteryHealth,
      plugged: latestSensor.batteryPlugged,
      status: latestSensor.batteryStatus,
      temperatureC: latestSensor.batteryTemperatureC,
      thermalDescription: thermal,
      currentUa: latestSensor.batteryCurrentUa,
    },
    ambient: {
      lightLux: latestSensor.lightLux,
      lightDescription: light,
      lightSensorDiagnostics: latestSensor.lightSensorDiagnostics,
      lightSensorEvents: latestSensor.lightSensorEvents,
      soundScene: latestSensor.soundScene,
      soundConfidence: latestSensor.soundConfidence,
      motionState: latestSensor.motionState,
    },
    location: latestSensor.latitude !== null && latestSensor.longitude !== null
      ? {
          latitude: Math.round(latestSensor.latitude * 1000) / 1000,
          longitude: Math.round(latestSensor.longitude * 1000) / 1000,
        }
      : null,
    environmentLocationSource: loc?.source ?? null,
    weather,
    weatherError,
    airQuality,
    airQualityError,
    feelHint: feelParts.length ? feelParts.join("，") : null,
    privacyNote:
      "Phone-provided coordinates are rounded before exposure to ChatGPT. Future sound sensing is designed to upload classification labels, not raw audio.",
  };
}

const optionalString = z.string().trim().min(1).max(120).optional();

const sensorInput = z
  .object({
    batteryPercent: z.number().min(0).max(100).optional(),
    charging: z.boolean().optional(),
    batteryHealth: optionalString,
    batteryPlugged: optionalString,
    batteryStatus: optionalString,
    batteryTemperatureC: z.number().min(-30).max(100).optional(),
    batteryCurrentUa: z.number().min(-20_000_000).max(20_000_000).optional(),

    deviceModel: optionalString,
    deviceManufacturer: optionalString,
    androidVersion: optionalString,
    sdkInt: z.number().int().min(1).max(1000).optional(),
    timezone: optionalString,

    uptimeSeconds: z.number().min(0).optional(),
    memoryTotalMb: z.number().min(0).optional(),
    memoryAvailableMb: z.number().min(0).optional(),
    storageTotalGb: z.number().min(0).optional(),
    storageFreeGb: z.number().min(0).optional(),
    screenBrightness: z.number().min(0).max(1000).optional(),

    lightLux: z.number().min(0).max(500000).optional(),
    lightSensorDiagnostics: z.string().trim().min(1).max(4000).optional(),
    lightSensorEvents: z.string().trim().min(1).max(12000).optional(),
    soundScene: optionalString,
    soundConfidence: z.number().min(0).max(1).optional(),
    motionState: optionalString,

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
  });

function mergeSensor(incoming: z.infer<typeof sensorInput>, now: string): SensorState {
  const old = latestSensor;
  return {
    batteryPercent: incoming.batteryPercent ?? old?.batteryPercent ?? null,
    charging: incoming.charging ?? old?.charging ?? null,
    batteryHealth: incoming.batteryHealth ?? old?.batteryHealth ?? null,
    batteryPlugged: incoming.batteryPlugged ?? old?.batteryPlugged ?? null,
    batteryStatus: incoming.batteryStatus ?? old?.batteryStatus ?? null,
    batteryTemperatureC:
      incoming.batteryTemperatureC ?? old?.batteryTemperatureC ?? null,
    batteryCurrentUa: incoming.batteryCurrentUa ?? old?.batteryCurrentUa ?? null,

    deviceModel: incoming.deviceModel ?? old?.deviceModel ?? null,
    deviceManufacturer:
      incoming.deviceManufacturer ?? old?.deviceManufacturer ?? null,
    androidVersion: incoming.androidVersion ?? old?.androidVersion ?? null,
    sdkInt: incoming.sdkInt ?? old?.sdkInt ?? null,
    timezone: incoming.timezone ?? old?.timezone ?? null,

    uptimeSeconds: incoming.uptimeSeconds ?? old?.uptimeSeconds ?? null,
    memoryTotalMb: incoming.memoryTotalMb ?? old?.memoryTotalMb ?? null,
    memoryAvailableMb:
      incoming.memoryAvailableMb ?? old?.memoryAvailableMb ?? null,
    storageTotalGb: incoming.storageTotalGb ?? old?.storageTotalGb ?? null,
    storageFreeGb: incoming.storageFreeGb ?? old?.storageFreeGb ?? null,
    screenBrightness:
      incoming.screenBrightness ?? old?.screenBrightness ?? null,

    lightLux: incoming.lightLux ?? old?.lightLux ?? null,
    lightSensorDiagnostics:
      incoming.lightSensorDiagnostics ?? old?.lightSensorDiagnostics ?? null,
    lightSensorEvents:
      incoming.lightSensorEvents ?? old?.lightSensorEvents ?? null,
    soundScene: incoming.soundScene ?? old?.soundScene ?? null,
    soundConfidence: incoming.soundConfidence ?? old?.soundConfidence ?? null,
    motionState: incoming.motionState ?? old?.motionState ?? null,

    latitude: incoming.latitude ?? old?.latitude ?? null,
    longitude: incoming.longitude ?? old?.longitude ?? null,
    scene: incoming.scene ?? old?.scene ?? null,

    capturedAt: incoming.capturedAt ?? now,
    receivedAt: now,
  };
}

function textResult(value: unknown) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }],
  };
}

export function createServer(): McpServer {
  const server = new McpServer(
    { name: "lr-sense", version: "0.2.0" },
    { capabilities: { logging: {} } },
  );

  server.registerTool(
    "get_environment_snapshot",
    {
      description:
        "Read a comprehensive current snapshot: phone presence, battery, device vitals, ambient sensing, coarse location if available, weather, air quality, daylight, and a sensory summary. Read-only.",
      inputSchema: {},
    },
    async () => textResult(await buildSnapshot()),
  );

  server.registerTool(
    "get_device_state",
    {
      description:
        "Read current phone/device state: battery percentage, charging source/status, battery health/temperature/current, device model, Android version, uptime, memory, storage, brightness when available, and last-seen freshness. Read-only.",
      inputSchema: {},
    },
    async () => {
      if (!latestSensor) return textResult({ available: false, presence: dataFreshness() });
      return textResult({
        available: true,
        presence: dataFreshness(),
        device: {
          model: latestSensor.deviceModel,
          manufacturer: latestSensor.deviceManufacturer,
          androidVersion: latestSensor.androidVersion,
          sdkInt: latestSensor.sdkInt,
          timezone: latestSensor.timezone,
          uptimeSeconds: latestSensor.uptimeSeconds,
          memoryTotalMb: latestSensor.memoryTotalMb,
          memoryAvailableMb: latestSensor.memoryAvailableMb,
          storageTotalGb: latestSensor.storageTotalGb,
          storageFreeGb: latestSensor.storageFreeGb,
          screenBrightness: latestSensor.screenBrightness,
        },
        battery: {
          percent: latestSensor.batteryPercent,
          charging: latestSensor.charging,
          health: latestSensor.batteryHealth,
          plugged: latestSensor.batteryPlugged,
          status: latestSensor.batteryStatus,
          temperatureC: latestSensor.batteryTemperatureC,
          currentUa: latestSensor.batteryCurrentUa,
        },
      });
    },
  );

  server.registerTool(
    "get_presence_status",
    {
      description:
        "Check whether the phone collector is currently online, when it last uploaded, how old the latest snapshot is, and the current coarse scene label if configured. Read-only.",
      inputSchema: {},
    },
    async () =>
      textResult({
        ...dataFreshness(),
        scene: latestSensor?.scene ?? DEFAULT_SCENE,
      }),
  );

  server.registerTool(
    "get_weather_context",
    {
      description:
        "Read current outdoor weather context for the configured or phone-provided coarse location: temperature, apparent temperature, humidity, precipitation, cloud cover, visibility, wind, gusts, day/night, sunrise, sunset and UV. Read-only.",
      inputSchema: {},
    },
    async () => {
      const loc = getResolvedLocation();
      if (!loc) {
        return textResult({
          available: false,
          reason: "No phone location or server default location is configured yet.",
        });
      }
      try {
        return textResult({
          available: true,
          locationSource: loc.source,
          weather: await getWeather(loc.latitude, loc.longitude),
        });
      } catch (error) {
        return textResult({
          available: false,
          error: error instanceof Error ? error.message : "Weather lookup failed",
        });
      }
    },
  );

  server.registerTool(
    "get_air_quality_context",
    {
      description:
        "Read outdoor air-quality context for the configured or phone-provided coarse location: AQI, PM2.5, PM10, ozone and nitrogen dioxide. Read-only.",
      inputSchema: {},
    },
    async () => {
      const loc = getResolvedLocation();
      if (!loc) {
        return textResult({
          available: false,
          reason: "No phone location or server default location is configured yet.",
        });
      }
      try {
        return textResult({
          available: true,
          locationSource: loc.source,
          airQuality: await getAirQuality(loc.latitude, loc.longitude),
        });
      } catch (error) {
        return textResult({
          available: false,
          error: error instanceof Error ? error.message : "Air-quality lookup failed",
        });
      }
    },
  );

  server.registerTool(
    "get_ambient_light",
    {
      description:
        "Read the latest ambient-light lux and human-friendly brightness label. Tool is reserved now even if the current phone collector cannot provide light yet; a future sensor uploader can populate it without changing the plugin tool list.",
      inputSchema: {},
    },
    async () =>
      textResult({
        available: latestSensor?.lightLux !== null && latestSensor?.lightLux !== undefined,
        lightLux: latestSensor?.lightLux ?? null,
        description: lightSummary(latestSensor?.lightLux ?? null),
        diagnostics: latestSensor?.lightSensorDiagnostics ?? null,
        events: latestSensor?.lightSensorEvents ?? null,
        note:
          latestSensor?.lightLux === null || latestSensor?.lightLux === undefined
            ? "Reserved for the future Android sensor uploader."
            : null,
      }),
  );

  server.registerTool(
    "get_sound_scene",
    {
      description:
        "Read a privacy-preserving ambient sound classification such as quiet, music, speech or rain. Reserved now; future Android code should upload labels/confidence only, not raw audio, so the plugin tool can stay unchanged.",
      inputSchema: {},
    },
    async () =>
      textResult({
        available: latestSensor?.soundScene !== null && latestSensor?.soundScene !== undefined,
        scene: latestSensor?.soundScene ?? null,
        confidence: latestSensor?.soundConfidence ?? null,
        rawAudioStored: false,
        note:
          latestSensor?.soundScene === null || latestSensor?.soundScene === undefined
            ? "Reserved for the future Android sound classifier."
            : null,
      }),
  );

  server.registerTool(
    "get_motion_state",
    {
      description:
        "Read a coarse future motion/activity label such as still, walking or moving. Reserved now so accelerometer/activity data can be added later without changing the plugin tool list.",
      inputSchema: {},
    },
    async () =>
      textResult({
        available: latestSensor?.motionState !== null && latestSensor?.motionState !== undefined,
        motionState: latestSensor?.motionState ?? null,
        note:
          latestSensor?.motionState === null || latestSensor?.motionState === undefined
            ? "Reserved for the future Android motion sensor uploader."
            : null,
      }),
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
    version: "0.2.0",
    hasSensorData: latestSensor !== null,
    presence: dataFreshness(),
    tools: [
      "get_environment_snapshot",
      "get_device_state",
      "get_presence_status",
      "get_weather_context",
      "get_air_quality_context",
      "get_ambient_light",
      "get_sound_scene",
      "get_motion_state"
    ]
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

function mcpPathAuth(req: Request, res: Response, next: NextFunction) {
  if (!MCP_URL_SECRET) {
    res.status(503).json({
      jsonrpc: "2.0",
      error: { code: -32004, message: "MCP_URL_SECRET is not configured" },
      id: null,
    });
    return;
  }

  const rawSecret = req.params.secret;
  const secret = Array.isArray(rawSecret) ? rawSecret[0] : rawSecret;

  if (!secureRawMatch(secret, MCP_URL_SECRET)) {
    res.status(404).json({
      jsonrpc: "2.0",
      error: { code: -32003, message: "Not found" },
      id: null,
    });
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
  latestSensor = mergeSensor(parsed.data, now);
  res.json({ ok: true, receivedAt: now });
});

async function handleMcpRequest(req: Request, res: Response) {
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
}

app.post("/mcp", mcpAuth, handleMcpRequest);
app.post("/mcp/:secret", mcpPathAuth, handleMcpRequest);

app.get("/mcp/:secret", mcpPathAuth, (_req: Request, res: Response) => {
  res.status(405).json({
    jsonrpc: "2.0",
    error: { code: -32000, message: "Method not allowed" },
    id: null,
  });
});

app.delete("/mcp/:secret", mcpPathAuth, (_req: Request, res: Response) => {
  res.status(405).json({
    jsonrpc: "2.0",
    error: { code: -32000, message: "Method not allowed" },
    id: null,
  });
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
