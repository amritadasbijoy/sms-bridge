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

async function request<T>(method: string, path: string, body?: unknown, extraHeaders: Record<string, string> = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json", ...extraHeaders };
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

export interface Me { userId: string; username: string; totpEnabled: boolean; hasPassword: boolean }
export interface LoginStatus { status: string; challenge?: string | null; deviceId?: string | null; userId?: string | null }
export interface Device {
  id: string; name: string; platform: string; createdAt: string;
  lastSeenAt: string | null; revokedAt: string | null; vaultTrusted: boolean;
  /** Name chosen by the user ("Office phone"); [name] is the model. */
  label?: string | null;
  expiresAt?: string | null;
}
export interface WrappedKey { keyVersion: number; wrappedKey: string }
export interface PendingDevice {
  id: string; name: string; platform: string; encPublicKey: string; signPublicKey: string; createdAt: string;
  linkProof?: string | null;
}
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
  pairingCode: () => request<{ code: string; expiresAt: string; shortCode?: string | null }>("POST", "/devices/pairing-codes"),
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
    request<{ trusted: boolean; currentVersion: number | null; keys: WrappedKey[]; challenge: string | null }>(
      "GET",
      `/browser/devices/${encodeURIComponent(deviceId)}/keys`,
    ),
  /** Phones waiting for the vault key; only an approved browser may ask. */
  browserPending: (deviceId: string) => request<PendingDevice[]>("GET", `/browser/devices/${encodeURIComponent(deviceId)}/pending`),
  browserApprove: (deviceId: string, target: string, keys: WrappedKey[]) =>
    request<void>("POST", `/browser/devices/${encodeURIComponent(deviceId)}/approve/${encodeURIComponent(target)}`, { keys }),
  requestConfirm: (deviceId: string) => request<void>("POST", `/browser/devices/${encodeURIComponent(deviceId)}/confirm`, {}),
  connectRequests: () => request<{ id: string; name: string; createdAt: string; options: number[] }[]>("GET", "/connect-requests"),
  confirmConnect: (id: string, number: number) =>
    request<{ approved: boolean }>("POST", `/connect-requests/${encodeURIComponent(id)}/confirm`, { number }),
  rejectConnect: (id: string) => request<void>("POST", `/connect-requests/${encodeURIComponent(id)}/reject`, {}),
  invites: () => request<{ createdAt: string; expiresAt: string; used: boolean; usedAt: string | null }[]>("GET", "/invites"),
  createInvite: () => request<{ code: string; expiresAt: string }>("POST", "/invites", {}),
  /** A new account without a password ("Get started"). */
  start: (invite?: string) => request<Me>("POST", "/auth/start", invite ? { invite } : {}),
  /** Asks to be signed in by a phone (QR), or by the phones of [username] (number). */
  createLoginRequest: (name: string, encPublicKey: string, signPublicKey: string, username?: string) =>
    request<{ requestId: string; secret: string; expiresAt: string; code?: string | null }>("POST", "/auth/login-requests", {
      name,
      encPublicKey,
      signPublicKey,
      ...(username ? { username } : {}),
    }),
  loginStatus: (id: string, secret: string) =>
    request<LoginStatus>("GET", `/auth/login-requests/${encodeURIComponent(id)}`, undefined, { "X-Request-Secret": secret }),
  setPassword: (password: string, currentPassword?: string) =>
    request<void>("POST", "/auth/password", { password, ...(currentPassword ? { currentPassword } : {}) }),
  registerWithInvite: (username: string, password: string, invite: string) =>
    request<Me>("POST", "/auth/register", { username, password, invite }),
  /** Moving a scanned phone from this temporary account into the user's own one (lib/account.ts). */
  moveStart: (deviceId: string) => request<{ token: string }>("POST", "/account/move/start", { deviceId }),
  moveTarget: (token: string, linkSecret: string) => request<void>("POST", "/account/move/target", { token, linkSecret }),
  setLabel: (deviceId: string, label: string | null) => request<void>("PUT", `/devices/${encodeURIComponent(deviceId)}/label`, { label }),
  /** "Save your account": [password] is the browser-derived login secret (lib/password.ts). */
  saveAccount: (username: string, password: string) => request<void>("POST", "/auth/account", { username, password }),
  passwordVault: () => request<{ salt: string; iterations: number; keys: WrappedKey[] }>("GET", "/vault/password"),
  setPasswordVault: (body: { deviceId: string; salt: string; iterations: number; publicKey: string; verifier: string; keys: WrappedKey[] }) =>
    request<void>("PUT", "/vault/password", body),
  selfApprove: (deviceId: string, verifier: string, keys: WrappedKey[]) =>
    request<void>("POST", `/browser/devices/${encodeURIComponent(deviceId)}/self-approve`, { verifier, keys }),
};
