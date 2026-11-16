/** Signing in with a username and password, and moving a scanned phone into that account. */
import { api, ApiError, type Me } from "./api";
import { adoptWithPassword, ensureDevice, rawVaultKeys, storedDeviceId } from "./browser-device";
import { base64ToBytes, type Bytes } from "./crypto";
import { hpkeSeal, wrapContext } from "./hpke";
import { linkProof, newLinkSecret } from "./link";
import { loginSecret } from "./password";
import { PHONES_CHANGED } from "./phones";

const toB64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));

/**
 * Signs in with the browser-derived login secret. An older account that still has the plain
 * password signs in once with it and is switched over.
 */
export async function passwordSignIn(username: string, password: string, totp?: string): Promise<Me> {
  const secret = await loginSecret(username, password);
  try {
    return await api.login(username, secret, totp);
  } catch (err) {
    if (!(err instanceof ApiError) || err.code !== "invalid_credentials") throw err;
    const me = await api.login(username, password, totp);
    await api.setPassword(secret, password).catch(() => {});
    return me;
  }
}

/**
 * "I already have an account": from a temporary account made by scanning, signs in to the user's
 * own account and moves the scanned phone into it. The phone follows on its own; this browser
 * approves it when it shows up with the right proof.
 */
export async function moveIntoAccount(tempUserId: string, username: string, password: string): Promise<Me> {
  const browser = await storedDeviceId(tempUserId);
  if (!browser) throw new ApiError(403, "not_trusted", "Open your messages in this browser first.");
  const { token } = await api.moveStart(browser);
  const me = await passwordSignIn(username, password);
  try {
    await adoptWithPassword(me.userId, password);
  } catch (e) {
    if (e instanceof ApiError && e.code === "no_password_vault") {
      throw new ApiError(409, "no_password_vault", "Signed in, but your password does not open your messages yet. Scan the QR code with a phone of that account once.");
    }
    throw e;
  }
  const secret = newLinkSecret();
  await api.moveTarget(token, toB64(secret));
  // The page reloads into the other account; the watch resumes there (MoveWatcher).
  try {
    sessionStorage.setItem(MOVE_KEY, JSON.stringify({ userId: me.userId, secret: toB64(secret), until: Date.now() + 5 * 60_000 }));
  } catch {
    void approveWhenItArrives(me.userId, secret, Date.now() + 5 * 60_000);
  }
  return me;
}

const MOVE_KEY = "sms-bridge.move";

/** A phone move this tab is waiting for: { userId, until }, or null. */
export function pendingMove(): { userId: string; secret: string; until: number } | null {
  try {
    const v = JSON.parse(sessionStorage.getItem(MOVE_KEY) ?? "null");
    return v && v.until > Date.now() ? v : null;
  } catch {
    return null;
  }
}

/** Resumes waiting for the moving phone after the reload; resolves true once it was approved. */
export async function resumeMove(userId: string): Promise<boolean> {
  const move = pendingMove();
  if (!move || move.userId !== userId) return false;
  const done = await approveWhenItArrives(userId, base64ToBytes(move.secret), move.until);
  try {
    sessionStorage.removeItem(MOVE_KEY);
  } catch {
    /* fine */
  }
  return done;
}

/** Watches for the moving phone for a few minutes and gives it the vault key once its proof matches. */
async function approveWhenItArrives(userId: string, secret: Bytes, until: number): Promise<boolean> {
  while (Date.now() < until) {
    await new Promise((r) => setTimeout(r, 2000));
    try {
      const device = await ensureDevice(userId);
      for (const p of await api.browserPending(device.deviceId)) {
        if (!p.linkProof || (await linkProof(secret, p.encPublicKey, p.signPublicKey)) !== p.linkProof) continue;
        const raw = await rawVaultKeys(device);
        try {
          const keys = [];
          for (const [v, vk] of raw) keys.push({ keyVersion: v, wrappedKey: toB64(await hpkeSeal(base64ToBytes(p.encPublicKey), vk, wrapContext(p.id, v))) });
          await api.browserApprove(device.deviceId, p.id, keys);
        } finally {
          raw.forEach((k) => k.fill(0));
        }
        window.dispatchEvent(new Event(PHONES_CHANGED));
        return true;
      }
    } catch {
      /* keep trying through brief network trouble */
    }
  }
  return false;
}
