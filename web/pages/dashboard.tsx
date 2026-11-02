import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { Shell } from "@/components/shell";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { UnlockCard } from "@/components/vault-gate";
import { api, type Device } from "@/lib/api";
import { conversations } from "@/lib/messages";
import { useSession } from "@/lib/session";
import { formatShort } from "@/lib/utils";

function Stat({ label, value, href }: { label: string; value: string; href?: string }) {
  const body = (
    <Card className="h-full transition-colors hover:bg-accent/40">
      <CardHeader className="pb-2">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl">{value}</CardTitle>
      </CardHeader>
    </Card>
  );
  return href ? <Link href={href}>{body}</Link> : body;
}

export default function Dashboard() {
  const { vault, store, live } = useSession();
  const [devices, setDevices] = useState<Device[] | null>(null);
  // Reload when the vault opens: a phone connected by scanning appears without a page reload.
  useEffect(() => {
    api.devices().then(setDevices, () => setDevices([]));
  }, [vault.status]);

  const stats = useMemo(() => {
    const m = store.messages;
    return {
      total: m.length,
      inbox: m.filter((x) => x.box === "INBOX").length,
      unread: m.filter((x) => x.box === "INBOX" && !x.read).length,
      sent: m.filter((x) => x.box === "SENT").length,
      threads: conversations(m).length,
    };
  }, [store]);
  const active = devices?.filter((d) => !d.revokedAt && d.platform !== "web") ?? [];
  const ready = vault.status === "ready";

  return (
    <Shell title="Dashboard">
      <div className="space-y-6">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Stat label="Phones" value={devices ? String(active.length) : "…"} href="/devices/" />
          <Stat label="Inbox" value={ready ? stats.inbox.toLocaleString() : "Locked"} href="/inbox/" />
          <Stat label="Sent" value={ready ? stats.sent.toLocaleString() : "Locked"} href="/sent/" />
          <Stat label="Conversations" value={ready ? stats.threads.toLocaleString() : "Locked"} href="/conversations/" />
        </div>
        {!ready ? (
          <UnlockCard />
        ) : (
          <Card>
            <CardHeader>
              <CardTitle>Latest messages</CardTitle>
              <CardDescription>
                {stats.unread > 0 ? `${stats.unread.toLocaleString()} unread · ` : ""}
                {live ? "Live: new messages appear automatically." : "Not live; reconnecting…"}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {store.messages.length === 0 ? (
                <p className="text-sm text-muted-foreground">No messages synced yet.</p>
              ) : (
                <ul className="divide-y">
                  {store.messages.slice(0, 8).map((m) => (
                    <li key={m.key} className="flex items-baseline justify-between gap-4 py-2 text-sm">
                      <span className="min-w-0 flex-1 truncate">
                        <span className="font-medium">{m.box === "SENT" ? `To ${m.address}` : m.address}</span>
                        <span className="text-muted-foreground"> · {m.body}</span>
                      </span>
                      <span className="shrink-0 text-xs text-muted-foreground">{formatShort(m.date)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        )}
      </div>
    </Shell>
  );
}
