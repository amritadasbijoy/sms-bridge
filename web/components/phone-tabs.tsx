import { useEffect, useMemo, useRef, useState } from "react";
import { usePhones } from "@/lib/phones";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

/**
 * "All phones · Office phone · Wife's phone": which phone's messages the page shows. Each tab shows
 * the user's name for the phone, its model and SIMs, and a pencil to rename it.
 */
export function PhoneTabs() {
  const { phones, selected, select, rename } = usePhones();
  const { store } = useSession();
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const unread = useMemo(() => {
    const counts = new Map<string, number>();
    for (const m of store.messages) {
      if (m.box !== "INBOX" || m.read) continue;
      for (const d of m.deviceIds) counts.set(d, (counts.get(d) ?? 0) + 1);
    }
    return counts;
  }, [store]);
  if (phones.length === 0) return null;

  async function save(id: string) {
    try {
      await rename(id, draft);
      setEditing(null);
      setError(null);
    } catch {
      setError("Could not rename. Try again.");
    }
  }

  return (
    <div className="mb-3 space-y-1">
      <div className="flex gap-2 overflow-x-auto pb-1" role="group" aria-label="Show messages from">
        {phones.length > 1 && (
          <button
            onClick={() => select(null)}
            aria-pressed={selected === null}
            className={cn("shrink-0 rounded-lg border px-3 py-1.5 text-sm", selected === null ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
          >
            All phones
          </button>
        )}
        {phones.map((p) => {
          const active = selected === p.id || phones.length === 1;
          const count = unread.get(p.id);
          if (editing === p.id) {
            return (
              <form
                key={p.id}
                className="flex shrink-0 items-center gap-1 rounded-lg border px-2 py-1"
                onSubmit={(e) => {
                  e.preventDefault();
                  void save(p.id);
                }}
              >
                <input
                  autoFocus
                  value={draft}
                  maxLength={40}
                  onChange={(e) => setDraft(e.target.value)}
                  placeholder={p.model}
                  aria-label={`Name for ${p.model}`}
                  className="w-36 rounded border bg-background px-2 py-0.5 text-sm"
                />
                <button type="submit" className="rounded px-2 text-sm font-medium text-primary">Save</button>
                <button type="button" className="rounded px-1 text-sm text-muted-foreground" onClick={() => setEditing(null)}>Cancel</button>
              </form>
            );
          }
          return (
            <div
              key={p.id}
              className={cn("flex shrink-0 items-stretch rounded-lg border", active ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent")}
            >
              <button onClick={() => select(p.id)} aria-pressed={active} className="px-3 py-1.5 text-left">
                <span className="flex items-center gap-1.5 text-sm font-medium">
                  {p.label}
                  {p.removed && <span className="text-xs opacity-75">(removed)</span>}
                  {count ? <span className={cn("rounded-full px-1.5 text-xs", active ? "bg-primary-foreground/20" : "bg-muted")}>{count}</span> : null}
                </span>
                <span className="block text-xs opacity-75">{[p.named ? p.model : null, ...p.sims].filter(Boolean).join(" · ") || "No SIM info yet"}</span>
              </button>
              <button
                onClick={() => {
                  setDraft(p.named ? p.label.replace(/ \(\d+\)$/, "") : "");
                  setEditing(p.id);
                }}
                className="px-2 text-sm opacity-75 hover:opacity-100"
                title="Rename this phone"
                aria-label={`Rename ${p.label}`}
              >
                ✎
              </button>
            </div>
          );
        })}
      </div>
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

const ALERTS = "sms-bridge.alerts";

/** Desktop notifications for new SMS: on when the user allowed them in this browser. */
export function useDesktopAlerts(): { on: boolean; supported: boolean; toggle: () => Promise<void> } {
  const [on, setOn] = useState(false);
  const supported = typeof window !== "undefined" && "Notification" in window;
  useEffect(() => {
    try {
      setOn(supported && Notification.permission === "granted" && localStorage.getItem(ALERTS) !== "off");
    } catch {
      setOn(false);
    }
  }, [supported]);
  const toggle = async () => {
    if (!supported) return;
    const next = on ? false : (await Notification.requestPermission()) === "granted";
    try {
      localStorage.setItem(ALERTS, next ? "on" : "off");
    } catch {
      /* fine: only this tab remembers */
    }
    setOn(next);
  };
  return { on, supported, toggle };
}

function chime() {
  try {
    const ctx = new AudioContext();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.15, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.4);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.4);
  } catch {
    /* no sound available */
  }
}

/** A short pop-up when a new SMS arrives live, naming the phone that received it. */
export function NewMessageToast() {
  const { store, vault } = useSession();
  const { phones, labelOf } = usePhones();
  const alerts = useDesktopAlerts();
  const newest = useRef<number | null>(null);
  const [toast, setToast] = useState<{ from: string; body: string; phone: string | null } | null>(null);

  useEffect(() => {
    if (vault.status !== "ready") {
      newest.current = null;
      return;
    }
    const latest = store.messages.find((m) => m.box === "INBOX");
    if (!latest) return;
    // The first look after loading only records the newest message; later ones are new arrivals.
    if (newest.current != null && latest.date > newest.current) {
      const phone = phones.length > 1 ? labelOf(latest.deviceIds[0]!) : null;
      setToast({ from: latest.address || "Unknown number", body: latest.body, phone });
      chime();
      try {
        if (alerts.on && document.visibilityState !== "visible") {
          new Notification(`${latest.address || "New SMS"}${phone ? ` · ${phone}` : ""}`, { body: latest.body.slice(0, 160), tag: latest.key });
        }
      } catch {
        /* notifications blocked */
      }
    }
    newest.current = Math.max(newest.current ?? 0, latest.date);
  }, [store, vault.status, phones.length, labelOf, alerts.on]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 8000);
    return () => clearTimeout(t);
  }, [toast]);

  if (!toast) return null;
  return (
    <div className="fixed inset-x-4 bottom-4 z-40 mx-auto max-w-sm rounded-lg border bg-card p-4 shadow-lg" role="status">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs text-muted-foreground">New SMS{toast.phone ? ` on ${toast.phone}` : ""}</p>
          <p className="truncate text-sm font-medium">{toast.from}</p>
          <p className="line-clamp-2 break-words text-sm">{toast.body}</p>
        </div>
        <button className="text-sm text-muted-foreground hover:text-foreground" onClick={() => setToast(null)} aria-label="Close">
          ✕
        </button>
      </div>
    </div>
  );
}
