import { useEffect, useMemo, useState } from "react";
import { Shell } from "@/components/shell";
import { Alert } from "@/components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { UnlockCard } from "@/components/vault-gate";
import { api, type Device } from "@/lib/api";
import { useSession } from "@/lib/session";

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-4 py-2 text-sm">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right font-medium">{value}</dd>
    </div>
  );
}

export default function Sync() {
  const { vault, store, undecryptable, latestSequence, live } = useSession();
  const [devices, setDevices] = useState<Device[]>([]);
  useEffect(() => {
    api.devices().then(setDevices, () => {});
  }, []);
  const perDevice = useMemo(() => {
    const counts = new Map<string, number>();
    for (const m of store.messages) for (const d of m.deviceIds) counts.set(d, (counts.get(d) ?? 0) + 1);
    return counts;
  }, [store]);

  return (
    <Shell title="Sync">
      <div className="space-y-6">
        <Card>
          <CardHeader>
            <CardTitle>How sync works</CardTitle>
            <CardDescription>
              Each phone encrypts messages before upload. The server stores only ciphertext and cannot read it. This tab
              decrypts them locally with the vault key it unlocked from your recovery key.
            </CardDescription>
          </CardHeader>
        </Card>
        {vault.status !== "ready" ? (
          <UnlockCard />
        ) : (
          <>
            <Card>
              <CardHeader>
                <CardTitle>This browser</CardTitle>
              </CardHeader>
              <CardContent>
                <dl className="divide-y">
                  <Row label="Connection" value={live ? "Live" : "Reconnecting…"} />
                  <Row label="Events on server" value={latestSequence.toLocaleString()} />
                  <Row label="Messages after merging duplicates" value={store.messages.length.toLocaleString()} />
                  <Row label="Deleted on a phone (hidden)" value={store.deletedOnPhone.toLocaleString()} />
                  <Row label="Other events" value={store.skippedEvents.toLocaleString()} />
                </dl>
              </CardContent>
            </Card>
            {undecryptable > 0 && (
              <Alert tone="warning">
                {undecryptable.toLocaleString()} event(s) could not be decrypted and are not shown. This happens if they were
                encrypted with a key this recovery key does not unlock, or were altered on the server.
              </Alert>
            )}
            <Card>
              <CardHeader>
                <CardTitle>Messages per phone</CardTitle>
              </CardHeader>
              <CardContent>
                <dl className="divide-y">
                  {devices.map((d) => (
                    <Row key={d.id} label={d.name} value={(perDevice.get(d.id) ?? 0).toLocaleString()} />
                  ))}
                </dl>
              </CardContent>
            </Card>
          </>
        )}
      </div>
    </Shell>
  );
}
