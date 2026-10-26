// One small fetch wrapper. The browser only talks to its own origin; nginx (or Vite in dev) forwards
// /api/<service>/... to the right service (ADR 0021). The token lives in memory only.

export type Service = "auth" | "payments" | "ledger";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly retryAfter?: string,
  ) {
    super(message);
  }
}

let token: string | null = null;
let onUnauthorized: () => void = () => {};

export const setToken = (t: string | null) => {
  token = t;
};
export const setUnauthorizedHandler = (fn: () => void) => {
  onUnauthorized = fn;
};

export async function api<T>(service: Service, path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (typeof init.body === "string") headers.set("Content-Type", "application/json");
  const response = await fetch(`/api/${service}${path}`, { ...init, headers });
  if (response.status === 401 && token) onUnauthorized();
  if (!response.ok) throw new ApiError(response.status, await describe(response), response.headers.get("Retry-After") ?? undefined);
  return (await response.json()) as T;
}

// The services answer errors with a reason, never with the rejected value (ADR 0019).
async function describe(response: Response): Promise<string> {
  let reason = "";
  try {
    const body = await response.json();
    reason = body.detail ?? body.message ?? body.error ?? "";
  } catch {
    // no JSON body
  }
  const base = { 401: "Not signed in", 403: "You are not allowed to do this", 429: "Too many requests" }[response.status];
  return [base ?? `Request failed (${response.status})`, reason].filter(Boolean).join(": ");
}

export const json = (body: unknown): RequestInit => ({ method: "POST", body: JSON.stringify(body) });
export const post: RequestInit = { method: "POST" };

export interface Account {
  id: string;
  ownerId: string;
  type: string;
  currency: string;
  status: string;
  balance: number;
  createdAt: string;
}
export interface Payment {
  id: string;
  merchantId: string;
  payerAccountId: string;
  amount: number;
  currency: string;
  status: string;
  bankRef: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface PaymentEvent {
  from: string | null;
  to: string;
  at: string;
  reason: string | null;
}
export interface ReconRun {
  id: string;
  fileName: string;
  status: string;
  periodStart: string;
  periodEnd: string;
  startedAt: string;
  finishedAt: string | null;
  matched: number;
  missingInLedger: number;
  missingInBank: number;
  amountMismatch: number;
  duplicateInBank: number;
  error: string | null;
}
export interface ReconItem {
  externalRef: string;
  outcome: string;
  ledgerAmountMinor: number | null;
  bankAmountMinor: number | null;
  detail: string | null;
}
