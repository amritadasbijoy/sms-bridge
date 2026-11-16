import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, type Device } from "./api";
import { simLabel, type Message } from "./messages";
import { useSession } from "./session";

export interface Phone {
  id: string;
  /** The user's name for it ("Office phone"), else the model, with " (2)" when two phones share one. */
  label: string;
  /** The model the phone reported ("Samsung SM-A525F"). */
  model: string;
  /** True when the user named it. */
  named: boolean;
  removed: boolean;
  /** "SIM 1 · GP", "SIM 2 · Robi": the SIMs seen in its messages. */
  sims: string[];
}

interface Phones {
  phones: Phone[];
  /** null = all phones. */
  selected: string | null;
  select: (id: string | null) => void;
  /** Only the selected phone's messages (all of them when none is selected). */
  filter: (messages: Message[]) => Message[];
  labelOf: (deviceId: string) => string;
  /** Renames a phone (null: back to its model). */
  rename: (deviceId: string, label: string | null) => Promise<void>;
  reload: () => void;
}

const PhonesContext = createContext<Phones | null>(null);
const SELECTED = "sms-bridge.phone";

/** Fired after a phone is added, so lists update without a reload. */
export const PHONES_CHANGED = "sms-bridge:phones-changed";

/** The account's phones (for "All phones · Phone 1 · Phone 2" tabs) and the chosen one. */
export function PhonesProvider({ children }: { children: ReactNode }) {
  const { me, vault, store } = useSession();
  const [devices, setDevices] = useState<Device[]>([]);
  const [selected, setSelected] = useState<string | null>(null);

  const reload = useCallback(() => {
    api.devices().then(setDevices, () => {});
  }, []);
  useEffect(() => {
    if (!me) return setDevices([]);
    reload();
    window.addEventListener(PHONES_CHANGED, reload);
    const timer = setInterval(reload, 60_000);
    return () => {
      window.removeEventListener(PHONES_CHANGED, reload);
      clearInterval(timer);
    };
  }, [me, vault.status, reload]);
  useEffect(() => {
    try {
      setSelected(sessionStorage.getItem(SELECTED));
    } catch {
      /* storage blocked: start with all phones */
    }
  }, []);

  const select = useCallback((id: string | null) => {
    setSelected(id);
    try {
      if (id) sessionStorage.setItem(SELECTED, id);
      else sessionStorage.removeItem(SELECTED);
    } catch {
      /* fine */
    }
  }, []);

  const phones = useMemo(() => {
    const withMessages = new Set(store.messages.flatMap((m) => m.deviceIds));
    const list = devices.filter((d) => d.platform !== "web" && (!d.revokedAt || withMessages.has(d.id)));
    const sims = new Map<string, Map<number, string>>();
    for (const m of store.messages) {
      const label = simLabel(m);
      if (!label || m.simSlot == null) continue;
      for (const id of m.deviceIds) {
        const bySlot = sims.get(id) ?? new Map<number, string>();
        if (!bySlot.has(m.simSlot) || m.carrier) bySlot.set(m.simSlot, label);
        sims.set(id, bySlot);
      }
    }
    const seen = new Map<string, number>();
    return list.map((d): Phone => {
      const base = d.label?.trim() || d.name;
      const n = (seen.get(base) ?? 0) + 1;
      seen.set(base, n);
      const bySlot = sims.get(d.id);
      return {
        id: d.id,
        label: n > 1 ? `${base} (${n})` : base,
        model: d.name,
        named: Boolean(d.label?.trim()),
        removed: Boolean(d.revokedAt),
        sims: bySlot ? [...bySlot.entries()].sort((a, b) => a[0] - b[0]).map((e) => e[1]) : [],
      };
    });
  }, [devices, store]);

  const current = selected && phones.some((p) => p.id === selected) ? selected : null;
  const filter = useCallback((m: Message[]) => (current ? m.filter((x) => x.deviceIds.includes(current)) : m), [current]);
  const labelOf = useCallback((id: string) => phones.find((p) => p.id === id)?.label ?? "Phone", [phones]);

  const rename = useCallback(
    async (id: string, label: string | null) => {
      await api.setLabel(id, label?.trim() || null);
      reload();
    },
    [reload],
  );

  const value = useMemo(
    () => ({ phones, selected: current, select, filter, labelOf, rename, reload }),
    [phones, current, select, filter, labelOf, rename, reload],
  );
  return <PhonesContext.Provider value={value}>{children}</PhonesContext.Provider>;
}

export function usePhones(): Phones {
  const v = useContext(PhonesContext);
  if (!v) throw new Error("usePhones outside PhonesProvider");
  return v;
}
