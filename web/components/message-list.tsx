import { useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { search, type Message } from "@/lib/messages";
import { formatDateTime } from "@/lib/utils";

const PAGE = 100;

/** Read-only list with client-side search. Bodies are rendered as text (never as HTML). */
export function MessageList({ messages, empty }: { messages: Message[]; empty: string }) {
  const [query, setQuery] = useState("");
  const [shown, setShown] = useState(PAGE);
  const filtered = useMemo(() => search(messages, query), [messages, query]);

  return (
    <div className="space-y-3">
      <Input
        type="search"
        placeholder="Search number or text"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setShown(PAGE);
        }}
        className="max-w-sm"
        aria-label="Search messages"
      />
      <p className="text-xs text-muted-foreground">
        {filtered.length.toLocaleString()} message{filtered.length === 1 ? "" : "s"}
      </p>
      {filtered.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul className="divide-y rounded-lg border">
          {filtered.slice(0, shown).map((m) => (
            <li key={m.key} className="space-y-1 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
                <span className="font-medium">{m.address || "(no number)"}</span>
                <span className="flex items-center gap-2 text-xs text-muted-foreground">
                  {m.box === "INBOX" && !m.read && <Badge>Unread</Badge>}
                  {m.box === "FAILED" && <Badge variant="destructive">Failed</Badge>}
                  {m.deviceIds.length > 1 && <Badge variant="outline">{m.deviceIds.length} phones</Badge>}
                  {formatDateTime(m.date)}
                </span>
              </div>
              <p className="whitespace-pre-wrap break-words text-sm">{m.body}</p>
            </li>
          ))}
        </ul>
      )}
      {shown < filtered.length && (
        <button className="text-sm text-primary underline-offset-4 hover:underline" onClick={() => setShown((n) => n + PAGE)}>
          Show more
        </button>
      )}
    </div>
  );
}
