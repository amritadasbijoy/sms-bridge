import { useEffect, useMemo, useRef, useState } from "react";
import { usePhones } from "@/lib/phones";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

/** "All phones · Galaxy A52 · Pixel 7": which phone's messages the page shows. Hidden with one phone. */
export function PhoneTabs() {
  const { phones, selected, select } = usePhones();
  const { store } = useSession();
  const unread = useMemo(() => {
    const counts = new Map<string, number>();
    for (const m of store.messages) {
      if (m.box !== "INBOX" || m.read) continue;
      for (const d of m.deviceIds) counts.set(d, (counts.get(d) ?? 0) + 1);
    }
    return counts;
  }, [store]);
  if (phones.length < 2) return null;
  const tab = (id: string | null, label: string, count?: number) => (
    <button
      key={id ?? "all"}
      onClick={() => select(id)}
      aria-pressed={selected === id}
      className={cn(
        "flex shrink-0 items-center gap-1.5 rounded-full border px-3 py-1 text-sm",
        selected === id ? "border-primary bg-primary text-primary-foreground" : "hover:bg-accent",
      )}
    >
      {label}
      {count ? <span className={cn("rounded-full px-1.5 text-xs", selected === id ? "bg-primary-foreground/20" : "bg-muted")}>{count}</span> : null}
    </button>
  );
  return (
    <div className="mb-4 flex gap-2 overflow-x-auto pb-1" role="group" aria-label="Show messages from">
      {tab(null, "All phones")}
      {phones.map((p) => tab(p.id, p.removed ? `${p.label} (removed)` : p.label, unread.get(p.id)))}
    </div>
  );
}

/** A short pop-up when a new SMS arrives live, naming the phone that received it. */
export function NewMessageToast() {
  const { store, vault } = useSession();
  const { phones, labelOf } = usePhones();
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
      setToast({ from: latest.address || "Unknown number", body: latest.body, phone: phones.length > 1 ? labelOf(latest.deviceIds[0]!) : null });
    }
    newest.current = Math.max(newest.current ?? 0, latest.date);
  }, [store, vault.status, phones.length, labelOf]);

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
