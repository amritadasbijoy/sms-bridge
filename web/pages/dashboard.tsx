import { useEffect, useMemo, useRef, useState } from "react";
import { useDesktopAlerts } from "@/components/phone-tabs";
import { SaveAccountCard } from "@/components/save-account";
import { Shell } from "@/components/shell";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { VaultGate } from "@/components/vault-gate";
import { conversations, otpOf, simLabel, type Conversation, type Message } from "@/lib/messages";
import { usePhones } from "@/lib/phones";
import { useSession } from "@/lib/session";
import { cn, formatDateTime, formatShort } from "@/lib/utils";

function CopyCode({ code }: { code: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      onClick={() => {
        void navigator.clipboard?.writeText(code).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        });
      }}
      className="mt-1.5 inline-flex items-center gap-1 rounded-md border border-current/30 bg-background/90 px-2 py-0.5 font-mono text-sm font-semibold text-foreground hover:bg-background"
      title="Copy the code"
    >
      {copied ? "Copied ✓" : `Copy ${code}`}
    </button>
  );
}

function Bubble({ m, phoneName }: { m: Message; phoneName: string | null }) {
  const outgoing = m.box !== "INBOX";
  const code = outgoing ? null : otpOf(m.body);
  const where = [phoneName, simLabel(m)].filter(Boolean).join(" · ");
  return (
    <div className={cn("flex", outgoing ? "justify-end" : "justify-start")}>
      <div
        className={cn(
          "max-w-[85%] rounded-lg px-3 py-2 text-sm",
          outgoing ? "bg-primary text-primary-foreground" : "bg-muted",
          m.box === "FAILED" && "bg-destructive text-destructive-foreground",
        )}
      >
        <p className="whitespace-pre-wrap break-words">{m.body}</p>
        {code && <CopyCode code={code} />}
        <p className="mt-1 text-[11px] opacity-70">
          {formatDateTime(m.date)}
          {where && ` · ${where}`}
          {m.box === "FAILED" && " · failed"}
        </p>
      </div>
    </div>
  );
}

function Thread({ c, onBack }: { c: Conversation; onBack: () => void }) {
  const { phones, labelOf } = usePhones();
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // Block body: scrollIntoView returns a Promise in newer browsers, which React would treat as a cleanup.
    end.current?.scrollIntoView({ block: "end" });
  }, [c]);
  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 border-b px-3 py-2.5">
        <button className="text-sm text-primary md:hidden" onClick={onBack} aria-label="Back to conversations">←</button>
        <span className="font-medium">{c.address}</span>
      </div>
      <div className="flex-1 space-y-2 overflow-y-auto p-3 sm:p-4">
        {c.messages.map((m) => (
          <Bubble key={m.key} m={m} phoneName={phones.length > 1 ? labelOf(m.deviceIds[0]!) : null} />
        ))}
        <div ref={end} />
      </div>
      <div className="border-t px-4 py-2 text-xs text-muted-foreground">Read-only: reply from your phone.</div>
    </div>
  );
}

function Messages() {
  const { store, live } = useSession();
  const { phones, selected, filter } = usePhones();
  const alerts = useDesktopAlerts();
  const [sim, setSim] = useState<number | null>(null);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<string | null>(null);

  const phoneMessages = useMemo(() => filter(store.messages), [store, filter]);
  // SIM filter: only offered when the shown messages come from more than one SIM slot.
  const slots = useMemo(() => [...new Set(phoneMessages.map((m) => m.simSlot).filter((s): s is number => s != null))].sort(), [phoneMessages]);
  useEffect(() => setSim(null), [selected]);
  const shown = useMemo(() => (sim == null ? phoneMessages : phoneMessages.filter((m) => m.simSlot === sim)), [phoneMessages, sim]);
  const all = useMemo(() => conversations(shown), [shown]);
  const list = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? all.filter((c) => c.address.toLowerCase().includes(q) || c.messages.some((m) => m.body.toLowerCase().includes(q))) : all;
  }, [all, query]);
  const current = all.find((c) => c.addressKey === open) ?? null;
  const carrierOf = (slot: number) => phoneMessages.find((m) => m.simSlot === slot && m.carrier)?.carrier;

  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        {slots.length > 1 && (
          <div className="flex gap-1" role="group" aria-label="Show SIM">
            {[null, ...slots].map((s) => (
              <button
                key={s ?? "all"}
                onClick={() => setSim(s)}
                aria-pressed={sim === s}
                className={cn("rounded-full border px-3 py-1", sim === s ? "border-primary bg-primary/10 font-medium text-primary" : "hover:bg-accent")}
              >
                {s == null ? "Both SIMs" : `SIM ${s}${carrierOf(s) ? ` · ${carrierOf(s)}` : ""}`}
              </button>
            ))}
          </div>
        )}
        <span className="ml-auto flex items-center gap-2 text-xs text-muted-foreground">
          <span className={cn("h-2 w-2 rounded-full", live ? "bg-green-500" : "bg-muted-foreground/40")} />
          {live ? "Live" : "Reconnecting…"}
          {alerts.supported && (
            <Button variant="ghost" size="sm" onClick={() => void alerts.toggle()}>
              {alerts.on ? "🔔 Alerts on" : "🔕 Turn on alerts"}
            </Button>
          )}
        </span>
      </div>
      <div className="grid h-[calc(100vh-15rem)] min-h-[420px] grid-cols-1 overflow-hidden rounded-lg border md:grid-cols-[340px_1fr]">
        <div className={cn("flex min-h-0 flex-col border-r", current && "hidden md:flex")}>
          <div className="border-b p-2">
            <Input type="search" placeholder="Search messages" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search messages" />
          </div>
          <ul className="flex-1 overflow-y-auto">
            {list.map((c) => {
              const code = c.latest.box === "INBOX" ? otpOf(c.latest.body) : null;
              return (
                <li key={c.addressKey}>
                  <button onClick={() => setOpen(c.addressKey)} className={cn("w-full px-4 py-3 text-left hover:bg-accent", c.addressKey === open && "bg-accent")}>
                    <div className="flex items-center justify-between gap-2 text-sm">
                      <span className="truncate font-medium">{c.address}</span>
                      <span className="shrink-0 text-xs text-muted-foreground">{formatShort(c.latest.date)}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="truncate text-xs text-muted-foreground">{c.latest.body}</span>
                      {code && <span className="shrink-0 rounded bg-primary/10 px-1.5 font-mono text-xs text-primary">{code}</span>}
                      {c.unread > 0 && <Badge className="ml-auto shrink-0">{c.unread}</Badge>}
                    </div>
                  </button>
                </li>
              );
            })}
            {list.length === 0 && (
              <li className="p-4 text-sm text-muted-foreground">
                {query ? "Nothing matches." : phones.length === 0 ? "Link a phone to see its messages here." : "No messages yet. New SMS appear here the moment the phone gets them."}
              </li>
            )}
          </ul>
        </div>
        <div className={cn("min-h-0", !current && "hidden md:block")}>
          {current ? (
            <Thread c={current} onBack={() => setOpen(null)} />
          ) : (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Choose a conversation.</div>
          )}
        </div>
      </div>
    </>
  );
}

/** The one page: phone tabs, conversations and the open chat, like WhatsApp Web. */
export default function MessagesPage() {
  return (
    <Shell title="Messages" phoneTabs>
      <SaveAccountCard />
      <VaultGate>
        <Messages />
      </VaultGate>
    </Shell>
  );
}
