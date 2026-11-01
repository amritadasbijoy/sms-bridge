import { useEffect, useMemo, useRef, useState } from "react";
import { Shell } from "@/components/shell";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { VaultGate } from "@/components/vault-gate";
import { conversations, type Conversation } from "@/lib/messages";
import { useSession } from "@/lib/session";
import { cn, formatDateTime, formatShort } from "@/lib/utils";

function Thread({ c }: { c: Conversation }) {
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // Block body: scrollIntoView returns a Promise in newer browsers, which React would treat as a cleanup.
    end.current?.scrollIntoView({ block: "end" });
  }, [c]);
  return (
    <div className="flex h-full flex-col">
      <div className="border-b px-4 py-3 font-medium">{c.address}</div>
      <div className="flex-1 space-y-2 overflow-y-auto p-4">
        {c.messages.map((m) => {
          const outgoing = m.box !== "INBOX";
          return (
            <div key={m.key} className={cn("flex", outgoing ? "justify-end" : "justify-start")}>
              <div
                className={cn(
                  "max-w-[80%] rounded-lg px-3 py-2 text-sm",
                  outgoing ? "bg-primary text-primary-foreground" : "bg-muted",
                  m.box === "FAILED" && "bg-destructive text-destructive-foreground",
                )}
              >
                <p className="whitespace-pre-wrap break-words">{m.body}</p>
                <p className="mt-1 text-[11px] opacity-70">
                  {formatDateTime(m.date)}
                  {m.box === "FAILED" && " · failed"}
                </p>
              </div>
            </div>
          );
        })}
        <div ref={end} />
      </div>
      <div className="border-t px-4 py-2 text-xs text-muted-foreground">Read-only: reply from your phone.</div>
    </div>
  );
}

function Conversations() {
  const { store } = useSession();
  const all = useMemo(() => conversations(store.messages), [store]);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const list = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? all.filter((c) => c.address.toLowerCase().includes(q) || c.messages.some((m) => m.body.toLowerCase().includes(q))) : all;
  }, [all, query]);
  const current = all.find((c) => c.addressKey === selected) ?? null;

  return (
    <div className="grid h-[70vh] grid-cols-1 overflow-hidden rounded-lg border md:grid-cols-[320px_1fr]">
      <div className={cn("flex min-h-0 flex-col border-r", current && "hidden md:flex")}>
        <div className="border-b p-2">
          <Input type="search" placeholder="Search conversations" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search conversations" />
        </div>
        <ul className="flex-1 overflow-y-auto">
          {list.map((c) => (
            <li key={c.addressKey}>
              <button
                onClick={() => setSelected(c.addressKey)}
                className={cn("w-full px-4 py-3 text-left hover:bg-accent", c.addressKey === selected && "bg-accent")}
              >
                <div className="flex items-center justify-between gap-2 text-sm">
                  <span className="truncate font-medium">{c.address}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">{formatShort(c.latest.date)}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="truncate text-xs text-muted-foreground">{c.latest.body}</span>
                  {c.unread > 0 && <Badge className="ml-auto shrink-0">{c.unread}</Badge>}
                </div>
              </button>
            </li>
          ))}
          {list.length === 0 && <li className="p-4 text-sm text-muted-foreground">No conversations.</li>}
        </ul>
      </div>
      <div className={cn("min-h-0", !current && "hidden md:block")}>
        {current ? (
          <div className="flex h-full flex-col">
            <button className="border-b px-4 py-2 text-left text-sm text-primary md:hidden" onClick={() => setSelected(null)}>
              ← All conversations
            </button>
            <div className="min-h-0 flex-1">
              <Thread c={current} />
            </div>
          </div>
        ) : (
          <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Choose a conversation.</div>
        )}
      </div>
    </div>
  );
}

export default function ConversationsPage() {
  return (
    <Shell title="Conversations">
      <VaultGate>
        <Conversations />
      </VaultGate>
    </Shell>
  );
}
