export async function GET(request: Request) {
  const coreBaseUrl = process.env.CORE_API_INTERNAL_URL ?? "http://localhost:8080";
  const correlationId = request.headers.get("x-correlation-id") ?? crypto.randomUUID();
  try {
    const response = await fetch(`${coreBaseUrl}/api/v1/health`, {
      cache: "no-store",
      headers: { "x-correlation-id": correlationId },
    });
    const core = await response.json();
    return Response.json(
      { status: response.ok ? "UP" : "DOWN", service: "web", core },
      {
        status: response.ok ? 200 : 503,
        headers: { "x-correlation-id": correlationId },
      },
    );
  } catch {
    return Response.json(
      { status: "DOWN", service: "web", core: null },
      { status: 503, headers: { "x-correlation-id": correlationId } },
    );
  }
}
