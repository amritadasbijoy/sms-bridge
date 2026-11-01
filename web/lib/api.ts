/** Same-origin JSON client for the SMS Bridge API (cookie session + CSRF header). */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET") headers["X-Requested-With"] = "SmsBridge";
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(`/api/v1${path}`, {
    method,
    headers,
    credentials: "same-origin",
    cache: "no-store",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let data;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    // A proxy answered with plain text or HTML: report the status, not "could not reach the server".
    if (!res.ok) throw new ApiError(res.status, "http_error", text.trim().slice(0, 200) || `Request failed (${res.status}).`);
    throw new ApiError(res.status, "bad_response", "The server sent an unexpected response.");
  }
  if (!res.ok) {
    throw new ApiError(res.status, data?.error ?? "http_error", data?.message ?? `Request failed (${res.status}).`);
  }
  return data as T;
}

export interface Me { userId: string; username: string; totpEnabled: boolean }
export interface Device {
  id: string; name: string; platform: string; createdAt: string;
  lastSeenAt: string | null; revokedAt: string | null; vaultTrusted: boolean;
}
export interface WrappedKey { keyVersion: number; wrappedKey: string }
export interface BrowserVault { exists: boolean; currentVersion: number | null; recoveryKeys: WrappedKey[]; approvedPhones: number }
export interface StoredEvent {
  eventId: string; deviceId: string; deviceSequence: number; serverSequence: number;
  keyVersion: number; ciphertext: string; signature: string;
}
export interface EventsPage { events: StoredEvent[]; latest: number }

export const api = {
  status: () => request<{ registrationOpen: boolean }>("GET", "/auth/status"),
  me: () => request<Me>("GET", "/auth/me"),
  login: (username: string, password: string, totp?: string) =>
    request<Me>("POST", "/auth/login", { username, password, ...(totp ? { totp } : {}) }),
  register: (username: string, password: string) => request<Me>("POST", "/auth/register", { username, password }),
  logout: () => request<void>("POST", "/auth/logout"),
  devices: () => request<Device[]>("GET", "/devices"),
  pairingCode: () => request<{ code: string; expiresAt: string }>("POST", "/devices/pairing-codes"),
  revoke: (id: string) => request<void>("POST", `/devices/${encodeURIComponent(id)}/revoke`, {}),
  totpSetup: () => request<{ secret: string; otpauthUri: string }>("POST", "/auth/totp/setup", {}),
  totpEnable: (code: string) => request<void>("POST", "/auth/totp/enable", { code }),
  totpDisable: (password: string, code: string) => request<void>("POST", "/auth/totp/disable", { password, code }),
  forgetBrowsers: () => request<void>("POST", "/auth/browsers/forget", {}),
  browserVault: () => request<BrowserVault>("GET", "/vault/browser"),
  events: (after: number, limit = 1000) => request<EventsPage>("GET", `/sync/events?after=${after}&limit=${limit}`),
  registerBrowser: (encPublicKey: string, signPublicKey: string) =>
    request<{ deviceId: string }>("POST", "/browser/devices", { name: "", encPublicKey, signPublicKey }),
  browserKeys: (deviceId: string) =>
    request<{ trusted: boolean; currentVersion: number | null; keys: WrappedKey[] }>("GET", `/browser/devices/${encodeURIComponent(deviceId)}/keys`),
  invites: () => request<{ createdAt: string; expiresAt: string; used: boolean; usedAt: string | null }[]>("GET", "/invites"),
  createInvite: () => request<{ code: string; expiresAt: string }>("POST", "/invites", {}),
  registerWithInvite: (username: string, password: string, invite: string) =>
    request<Me>("POST", "/auth/register", { username, password, invite }),
};
