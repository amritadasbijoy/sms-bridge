import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, ApiError, type Me, type StoredEvent } from "./api";
import { deviceSafetyCode, ensureDevice, fetchVaultKeys, forgetLocal } from "./browser-device";
import { deviceFingerprint } from "./pair";
import { base64ToBytes, decryptEvent, parseRecoveryKey, unlockVaultKey } from "./crypto";
import { buildMessages, type DecryptedEvent, type EventPayload, type MessageStore } from "./messages";

type VaultState =
  | { status: "locked" }
  | { status: "connecting" }

  /** This browser waits for a phone to approve it; the phone must show the same code. */
  /**
   * This browser waits for a phone to approve it (by scanning its QR code). [noApprovedPhone]: no phone
   * is connected and approved yet, so the QR also carries a pairing code to connect one.
   */
  | { status: "approval"; safetyCode: string; deviceId: string; fingerprint: string; noApprovedPhone: boolean }
  | { status: "unlocking" }
  | { status: "loading"; loaded: number }
  | { status: "ready" };

interface Session {
  me: Me | null | undefined; // undefined while checking
  refreshMe: () => Promise<void>;
  signOut: () => Promise<void>;
  vault: VaultState;
  vaultError: string | null;
  unlock: (recoveryKey: string) => Promise<boolean>;
  /** Opens the vault with this browser's own approved key (no recovery key). */
  openWithBrowser: () => Promise<void>;
  /** Revokes this browser's approval and forgets its key. */
  forgetBrowser: () => Promise<void>;
  lock: () => void;
  store: MessageStore;
  undecryptable: number;
  latestSequence: number;
  live: boolean;
}

const SessionContext = createContext<Session | null>(null);
const EMPTY: MessageStore = { messages: [], deletedOnPhone: 0, skippedEvents: 0 };
const decoder = new TextDecoder();

/**
 * Holds the signed-in account and, after an unlock, the vault keys (non-extractable CryptoKeys)
 * and decrypted messages, in memory only. Nothing decrypted is ever written to storage.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const [vault, setVault] = useState<VaultState>({ status: "locked" });
  const [vaultError, setVaultError] = useState<string | null>(null);
  const [store, setStore] = useState<MessageStore>(EMPTY);
  const [undecryptable, setUndecryptable] = useState(0);
  const [latestSequence, setLatestSequence] = useState(0);
  const [live, setLive] = useState(false);

  const keys = useRef<Map<number, CryptoKey>>(new Map());
  const events = useRef<DecryptedEvent[]>([]);
  const after = useRef(0);
  const fetching = useRef<Promise<void> | null>(null);
  const socket = useRef<WebSocket | null>(null);

  const refreshMe = useCallback(async () => {
    try {
      setMe(await api.me());
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setMe(null);
      else throw e;
    }
  }, []);

  useEffect(() => {
    refreshMe().catch(() => setMe(null));
  }, [refreshMe]);

  const lock = useCallback(() => {
    keys.current = new Map();
    events.current = [];
    after.current = 0;
    socket.current?.close();
    socket.current = null;
    setLive(false);
    setStore(EMPTY);
    setUndecryptable(0);
    setLatestSequence(0);
    setVault({ status: "locked" });
  }, []);

  const decryptPage = useCallback(async (page: StoredEvent[]) => {
    let failed = 0;
    const out = await Promise.all(
      page.map(async (e): Promise<DecryptedEvent | null> => {
        const key = keys.current.get(e.keyVersion);
        if (!key) {
          failed++;
          return null;
        }
        try {
          const plain = await decryptEvent(key, base64ToBytes(e.ciphertext), e.eventId, e.deviceId, e.deviceSequence, e.keyVersion);
          return { eventId: e.eventId, deviceId: e.deviceId, serverSequence: e.serverSequence, payload: JSON.parse(decoder.decode(plain)) as EventPayload };
        } catch {
          failed++; // tampered or not ours: never shown
          return null;
        }
      }),
    );
    if (failed) setUndecryptable((n) => n + failed);
    return out.filter((e): e is DecryptedEvent => e !== null);
  }, []);

  /** Pulls every event after the last one seen. Serialised so live notices cannot overlap. */
  const pull = useCallback((): Promise<void> => {
    if (fetching.current) return fetching.current;
    const run = (async () => {
      try {
        for (;;) {
          const page = await api.events(after.current);
          if (page.events.length === 0) {
            setLatestSequence(page.latest);
            break;
          }
          events.current.push(...(await decryptPage(page.events)));
          after.current = page.events[page.events.length - 1]!.serverSequence;
          setLatestSequence(page.latest);
          setVault((v) => (v.status === "loading" ? { status: "loading", loaded: events.current.length } : v));
        }
        setStore(buildMessages(events.current));
      } finally {
        fetching.current = null;
      }
    })();
    fetching.current = run;
    return run;
  }, [decryptPage]);

  const connectLive = useCallback(() => {
    const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
    const ws = new WebSocket(`${proto}//${window.location.host}/api/v1/sync/stream`);
    socket.current = ws;
    ws.onopen = () => setLive(true);
    ws.onmessage = (msg) => {
      try {
        const latest = JSON.parse(String(msg.data)).latest as number;
        if (latest > after.current) void pull();
      } catch {
        /* ignore malformed notices */
      }
    };
    ws.onclose = () => {
      setLive(false);
      if (socket.current === ws) {
        socket.current = null;
        // Reconnect while still unlocked.
        setTimeout(() => {
          if (keys.current.size > 0 && !socket.current) connectLive();
        }, 5000);
      }
    };
  }, [pull]);

  const load = useCallback(
    async (unlocked: Map<number, CryptoKey>) => {
      keys.current = unlocked;
      setVault({ status: "loading", loaded: 0 });
      await pull();
      setVault({ status: "ready" });
      connectLive();
    },
    [pull, connectLive],
  );

  const deviceId = useRef<string | null>(null);

  const openWithBrowser = useCallback(async () => {
    if (!me) return;
    setVaultError(null);
    setVault({ status: "connecting" });
    try {
      const info = await api.browserVault();
      const device = await ensureDevice(me.userId);
      deviceId.current = device.deviceId;
      const result = await fetchVaultKeys(device);
      if (result.state === "approved") return await load(result.keys);
      if (result.state === "revoked") {
        // Revoked on the Devices page: start over with a fresh key and a new approval.
        return await openWithBrowser();
      }
      setVault({
        status: "approval",
        safetyCode: await deviceSafetyCode(device),
        deviceId: device.deviceId,
        fingerprint: await deviceFingerprint(device.encPublic, device.signPublic),
        noApprovedPhone: !info.exists || info.approvedPhones === 0,
      });
    } catch (e) {
      keys.current = new Map();
      setVault({ status: "locked" });
      setVaultError(
        e instanceof ApiError
          ? e.message
          : "This browser cannot keep a device key (private window or blocked site data?). Use the recovery key instead.",
      );
    }
  }, [me, load]);

  // While waiting for approval, check every few seconds.
  useEffect(() => {
    if (vault.status !== "approval" || !me) return;
    let stopped = false;
    const timer = setInterval(async () => {
      try {
        const device = await ensureDevice(me.userId);
        const result = await fetchVaultKeys(device);
        if (stopped) return;
        if (result.state === "approved") {
          clearInterval(timer);
          await load(result.keys);
        } else if (result.state === "revoked") {
          clearInterval(timer);
          await openWithBrowser();
        } else {
          // Keep the hint current (e.g. once the phone starts over, it can approve this browser).
          const info = await api.browserVault();
          const noApprovedPhone = !info.exists || info.approvedPhones === 0;
          setVault((v) => (v.status === "approval" && v.noApprovedPhone !== noApprovedPhone ? { ...v, noApprovedPhone } : v));
        }
      } catch {
        /* keep waiting; transient network errors are fine */
      }
    }, 4000);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [vault.status, me, load, openWithBrowser]);

  // Signed in with a locked vault: try this browser's own key once per sign-in.
  const autoTried = useRef<string | null>(null);
  useEffect(() => {
    if (me && vault.status === "locked" && autoTried.current !== me.userId) {
      autoTried.current = me.userId;
      void openWithBrowser();
    }
    if (me === null) autoTried.current = null;
  }, [me, vault.status, openWithBrowser]);

  const forgetBrowser = useCallback(async () => {
    if (!me) return;
    const device = deviceId.current;
    lock();
    try {
      if (device) await api.revoke(device);
    } finally {
      await forgetLocal(me.userId);
      deviceId.current = null;
    }
  }, [me, lock]);

  const unlock = useCallback(
    async (text: string) => {
      setVaultError(null);
      const recoveryKey = parseRecoveryKey(text);
      if (!recoveryKey) {
        setVaultError("That is not a valid recovery key. It has 26 letters and digits, like ABCD-EFGH-…");
        return false;
      }
      setVault({ status: "unlocking" });
      try {
        const v = await api.browserVault();
        if (!v.exists || v.recoveryKeys.length === 0) {
          setVaultError("No vault yet. Turn on encrypted sync on a phone first.");
          setVault({ status: "locked" });
          return false;
        }
        const unlocked = new Map<number, CryptoKey>();
        for (const k of v.recoveryKeys) {
          unlocked.set(k.keyVersion, await unlockVaultKey(base64ToBytes(k.wrappedKey), recoveryKey, k.keyVersion));
        }
        await load(unlocked);
        return true;
      } catch (e) {
        keys.current = new Map();
        setVault({ status: "locked" });
        setVaultError(
          e instanceof ApiError ? e.message : "The recovery key does not match this vault. Check it and try again.",
        );
        return false;
      } finally {
        recoveryKey.fill(0);
      }
    },
    [load],
  );

  const signOut = useCallback(async () => {
    lock();
    try {
      await api.logout();
    } finally {
      setMe(null);
    }
  }, [lock]);

  // Lock after 15 minutes without interaction.
  useEffect(() => {
    if (vault.status !== "ready") return;
    let timer = setTimeout(lock, 15 * 60_000);
    const reset = () => {
      clearTimeout(timer);
      timer = setTimeout(lock, 15 * 60_000);
    };
    const kinds = ["pointerdown", "keydown", "scroll"] as const;
    kinds.forEach((k) => window.addEventListener(k, reset, { passive: true }));
    return () => {
      clearTimeout(timer);
      kinds.forEach((k) => window.removeEventListener(k, reset));
    };
  }, [vault.status, lock]);

  const value = useMemo<Session>(
    () => ({ me, refreshMe, signOut, vault, vaultError, unlock, openWithBrowser, forgetBrowser, lock, store, undecryptable, latestSequence, live }),
    [me, refreshMe, signOut, vault, vaultError, unlock, openWithBrowser, forgetBrowser, lock, store, undecryptable, latestSequence, live],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const s = useContext(SessionContext);
  if (!s) throw new Error("useSession outside SessionProvider");
  return s;
}
