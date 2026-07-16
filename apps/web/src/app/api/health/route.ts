export async function GET() {
  return Response.json({
    status: "UP",
    service: "web",
    version: process.env.AI_HOTSPOT_VERSION ?? "0.1.0",
    environment: process.env.AI_HOTSPOT_ENV ?? "development",
  });
}
