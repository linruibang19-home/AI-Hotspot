export type Problem = {
  status: number;
  code?: string;
  detail?: string;
  title?: string;
  fieldErrors?: Record<string, string>;
};

export class ApiError extends Error {
  problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? "请求失败");
    this.problem = problem;
  }
}

let csrfToken: string | null = null;

async function getCsrfToken() {
  if (csrfToken) return csrfToken;
  const response = await fetch("/api/core/api/v1/auth/csrf", {
    credentials: "include",
    cache: "no-store",
  });
  if (!response.ok) throw new ApiError(await response.json());
  const body = (await response.json()) as { token: string };
  csrfToken = body.token;
  return csrfToken;
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    headers.set("X-XSRF-TOKEN", await getCsrfToken());
  }
  const response = await fetch(`/api/core/api/v1${path}`, {
    ...init,
    method,
    headers,
    credentials: "include",
    cache: "no-store",
  });
  if (!response.ok) {
    let problem: Problem = { status: response.status, detail: `请求失败（${response.status}）` };
    try { problem = await response.json(); } catch { /* non-JSON upstream error */ }
    if (response.status === 403 && problem.code === "CSRF_TOKEN_INVALID") csrfToken = null;
    throw new ApiError(problem);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export function resetCsrfToken() {
  csrfToken = null;
}
