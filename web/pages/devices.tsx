import { useCallback, useEffect, useState } from "react";
import { AddPhoneButton } from "@/components/add-phone";
import { Shell } from "@/components/shell";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api, ApiError, type Device } from "@/lib/api";
import { formatDateTime } from "@/lib/utils";

export default function Devices() {
  const [devices, setDevices] = useState<Device[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);

  const load = useCallback(() => {
    api.devices().then(setDevices, (e) => setError(e instanceof ApiError ? e.message : "Could not load devices."));
  }, []);
  useEffect(load, [load]);

  async function revoke(id: string) {
    setError(null);
    try {
      await api.revoke(id);
      setConfirming(null);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not revoke the device.");
    }
  }

  return (
    <Shell title="Phones & browsers">
      <div className="space-y-6">
        <Card>
          <CardHeader>
            <CardTitle>Add a phone</CardTitle>
            <CardDescription>
              Show its messages here too, with their own tab. On the new phone, open SMS Bridge and tap Scan QR code or Type code.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <AddPhoneButton />
          </CardContent>
        </Card>

        {error && <Alert tone="error">{error}</Alert>}

        <Card>
          <CardHeader>
            <CardTitle>Phones and browsers</CardTitle>
            <CardDescription>
              Everything that can see your messages. Remove anything you don't recognise; it loses access at once.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {!devices ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : devices.length === 0 ? (
              <p className="text-sm text-muted-foreground">No devices connected yet.</p>
            ) : (
              <ul className="divide-y">
                {devices.map((d) => (
                  <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{d.label ? `${d.label} · ${d.name}` : d.name}</span>
                        <span className="text-xs text-muted-foreground">{d.platform === "web" ? "Browser" : "Phone"}</span>
                        {d.revokedAt ? (
                          <Badge variant="destructive">Revoked</Badge>
                        ) : d.vaultTrusted ? (
                          <Badge>Approved</Badge>
                        ) : (
                          <Badge variant="secondary">{d.platform === "web" ? "Approve on a phone" : "Waiting for approval"}</Badge>
                        )}
                      </div>
                      <p className="text-xs text-muted-foreground">
                        Added {formatDateTime(d.createdAt)} · Last seen {formatDateTime(d.lastSeenAt)}
                        {d.revokedAt && ` · Revoked ${formatDateTime(d.revokedAt)}`}
                        {!d.revokedAt && d.expiresAt && ` · Unlinks ${formatDateTime(d.expiresAt)}`}
                      </p>
                    </div>
                    {!d.revokedAt &&
                      (confirming === d.id ? (
                        <div className="flex items-center gap-2">
                          <span className="text-sm">
                            {d.platform !== "web" && d.vaultTrusted && devices.filter((x) => x.platform !== "web" && x.vaultTrusted && !x.revokedAt).length === 1
                              ? "This is your only approved phone. Afterwards nothing can approve new browsers or phones until a phone starts over. Revoke anyway?"
                              : "Revoke this device?"}
                          </span>
                          <Button variant="destructive" size="sm" onClick={() => void revoke(d.id)}>
                            Revoke
                          </Button>
                          <Button variant="ghost" size="sm" onClick={() => setConfirming(null)}>
                            Cancel
                          </Button>
                        </div>
                      ) : (
                        <Button variant="outline" size="sm" onClick={() => setConfirming(d.id)}>
                          Revoke…
                        </Button>
                      ))}
                  </li>
                ))}
              </ul>
            )}
            <p className="mt-4 text-xs text-muted-foreground">
              Revoking stops the phone&apos;s access immediately. Messages it already synced stay in your archive, and the
              phone keeps its own copy.
            </p>
          </CardContent>
        </Card>
      </div>
    </Shell>
  );
}
