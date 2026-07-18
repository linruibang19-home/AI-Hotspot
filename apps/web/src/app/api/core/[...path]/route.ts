import type { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

const coreApi = process.env.CORE_API_INTERNAL_URL ?? "http://127.0.0.1:8080";

async function proxy(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const normalizedPath = path[0] === "api" && path[1] === "v1" ? path.slice(2) : path;
  const target = new URL(`/api/v1/${normalizedPath.map(encodeURIComponent).join("/")}`, coreApi);
  target.search = request.nextUrl.search;

  const headers = new Headers(request.headers);
  headers.delete("host");
  headers.delete("content-length");
  headers.delete("connection");
  headers.delete("accept-encoding");

  const method = request.method.toUpperCase();
  const upstream = await fetch(target, {
    method,
    headers,
    body: method === "GET" || method === "HEAD" ? undefined : await request.arrayBuffer(),
    cache: "no-store",
    redirect: "manual",
  });

  const responseHeaders = new Headers(upstream.headers);
  const setCookies = (
    upstream.headers as Headers & { getSetCookie?: () => string[] }
  ).getSetCookie?.();
  if (setCookies?.length) {
    responseHeaders.delete("set-cookie");
    setCookies.forEach((cookie) => responseHeaders.append("set-cookie", cookie));
  }
  responseHeaders.delete("content-encoding");
  responseHeaders.delete("content-length");
  responseHeaders.set("cache-control", "no-store");
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
export const OPTIONS = proxy;
