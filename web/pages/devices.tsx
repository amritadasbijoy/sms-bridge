import { useCallback, useEffect, useState } from "react";
import { AddPhoneButton } from "@/components/add-phone";
import { Shell } from "@/components/shell";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api, ApiError, type Device } from "@/lib/api";
import { storedDeviceId } from "@/lib/browser-device";
import { usePhones } from "@/lib/phones";
import { useSession } from "@/lib/session";
import { formatDateTime } from "@/lib/utils";

/** "5 min ago", "yesterday", or a date. */
function ago(iso: string | null): string {
  if (!iso) return "never";
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60_000);
  if (minutes < 2) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 24 * 60) return `${Math.round(minutes / 60)} h ago`;
  if (minutes < 48 * 60) return "yesterday";
  return formatDateTime(iso);
}

function Row({ title, detail, badge, action }: { title: string; detail: string; badge?: string; action?: React.ReactNode }) {
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 py-3">
      <div className="min-w-0 space-y-0.5">
        <div className="flex items-center gap-2">
          <span className="font-medium">{title}</span>
          {badge && <Badge variant="secondary">{badge}</Badge>}
        </div>
        <p className="text-xs text-muted-foreground">{detail}</p>
      </div>
      {action}
    </li>
  );
}

export default function Devices() {
  const { me } = useSession();
  const { phones, rename } = usePhones();
  const [devices, setDevices] = useState<Device[] | null>(null);
  const [thisBrowser, setThisBrowser] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<Device | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.devices().then(setDevices, (e) => setError(e instanceof ApiError ? e.message : "Could not load the list."));
  }, []);
  useEffect(load, [load]);
  useEffect(() => {
    if (me) void storedDeviceId(me.userId).then(setThisBrowser);
  }, [me]);

  const live = (devices ?? []).filter((d) => !d.revokedAt);
  const phoneRows = live.filter((d) => d.platform !== "web");
  const browserRows = live.filter((d) => d.platform === "web" && d.vaultTrusted);

  async function remove(d: Device) {
    setBusy(true);
    setError(null);
    try {
      await api.revoke(d.id);
      setConfirming(null);
      // A removed phone's messages are gone from the server: start the page fresh.
      if (d.platform !== "web") window.location.reload();
      else load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not remove it. Try again.");
    } finally {
      setBusy(false);
    }
  }

  async function saveName(id: string) {
    try {
      await rename(id, draft);
      setEditing(null);
      load();
    } catch {
      setError("Could not rename. Try again.");
    }
  }

  const name = (d: Device) => d.label?.trim() || d.name;

  return (
    <Shell title="Phones & browsers">
      <div className="max-w-3xl space-y-6">
        {error && <Alert tone="error">{error}</Alert>}

        <Card>
          <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3 space-y-0">
            <div className="space-y-1">
              <CardTitle>Phones</CardTitle>
              <CardDescription>Phones that send their SMS here. Removing a phone also deletes its messages from the server.</CardDescription>
            </div>
            <AddPhoneButton size="sm" />
          </CardHeader>
          <CardContent>
            {!devices ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : phoneRows.length === 0 ? (
              <p className="text-sm text-muted-foreground">No phone yet. Tap Add another phone.</p>
            ) : (
              <ul className="divide-y">
                {phoneRows.map((d) =>
                  editing === d.id ? (
                    <li key={d.id} className="py-3">
                      <form
                        className="flex flex-wrap items-center gap-2"
                        onSubmit={(e) => {
                          e.preventDefault();
                          void saveName(d.id);
                        }}
                      >
                        <input
                          autoFocus
                          value={draft}
                          maxLength={40}
                          placeholder={d.name}
                          onChange={(e) => setDraft(e.target.value)}
                          aria-label={`Name for ${d.name}`}
                          className="w-56 rounded-md border bg-background px-3 py-1.5 text-sm"
                        />
                        <Button size="sm" type="submit">Save</Button>
                        <Button size="sm" variant="ghost" type="button" onClick={() => setEditing(null)}>Cancel</Button>
                      </form>
                    </li>
                  ) : (
                    <Row
                      key={d.id}
                      title={name(d)}
                      badge={d.vaultTrusted ? undefined : "Finishing setup"}
                      detail={[d.label ? d.name : null, ...(phones.find((p) => p.id === d.id)?.sims ?? []), `last seen ${ago(d.lastSeenAt)}`]
                        .filter(Boolean)
                        .join(" · ")}
                      action={
                        <div className="flex gap-2">
                          <Button variant="ghost" size="sm" onClick={() => { setDraft(d.label ?? ""); setEditing(d.id); }}>Rename</Button>
                          <Button variant="outline" size="sm" onClick={() => setConfirming(d)}>Remove</Button>
                        </div>
                      }
                    />
                  ),
                )}
              </ul>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Browsers</CardTitle>
            <CardDescription>Browsers that can open your messages. Removing one signs it out at once.</CardDescription>
          </CardHeader>
          <CardContent>
            {!devices ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : (
              <ul className="divide-y">
                {browserRows.map((d) => (
                  <Row
                    key={d.id}
                    title={name(d)}
                    badge={d.id === thisBrowser ? "This browser" : undefined}
                    detail={[`last opened ${ago(d.lastSeenAt)}`, d.expiresAt ? `unlinks ${formatDateTime(d.expiresAt)}` : null].filter(Boolean).join(" · ")}
                    action={d.id === thisBrowser ? undefined : <Button variant="outline" size="sm" onClick={() => setConfirming(d)}>Remove</Button>}
                  />
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>

      {confirming && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" role="dialog" aria-modal="true" aria-labelledby="remove-title">
          <Card className="w-full max-w-md">
            <CardHeader>
              <CardTitle id="remove-title">Remove {name(confirming)}?</CardTitle>
              <CardDescription>
                {confirming.platform === "web"
                  ? "It is signed out and can no longer open your messages."
                  : "It stops sending SMS here, and all of its messages are deleted from the server. The messages on the phone itself are not touched."}
                {confirming.platform !== "web" && phoneRows.filter((d) => d.vaultTrusted).length === 1 && confirming.vaultTrusted
                  ? " This is your only phone: after this, no new SMS arrive here until you add a phone again."
                  : ""}
              </CardDescription>
            </CardHeader>
            <CardContent className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setConfirming(null)} disabled={busy}>Cancel</Button>
              <Button variant="destructive" onClick={() => void remove(confirming)} disabled={busy}>
                {busy ? "Removing…" : confirming.platform === "web" ? "Remove" : "Remove and delete its messages"}
              </Button>
            </CardContent>
          </Card>
        </div>
      )}
    </Shell>
  );
}
