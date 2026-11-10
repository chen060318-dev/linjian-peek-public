import { app } from "./app.js";

const PORT = parseInt(process.env.PORT || "10000", 10);

if (!process.env.MCP_API_TOKEN) {
  console.warn("WARNING: MCP_API_TOKEN is missing.");
}
if (!process.env.SENSOR_API_TOKEN) {
  console.warn("WARNING: SENSOR_API_TOKEN is missing.");
}

app.listen(PORT, "0.0.0.0", () => {
  console.log(`LR Sense listening on port ${PORT}`);
});

process.on("SIGINT", () => {
  console.log("Shutting down...");
  process.exit(0);
});
