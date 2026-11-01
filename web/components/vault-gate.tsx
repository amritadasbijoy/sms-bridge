import { useState, type FormEvent, type ReactNode } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { useSession } from "@/lib/session";


/** Shows its children only once the vault is unlocked in this tab; otherwise how to open it. */
export function VaultGate({ children }: { children: ReactNode }) {
  const { vault } = useSession();
  if (vault.status === "ready") return <>{children}</>;
  return <UnlockCard />;
}

export function UnlockCard() {
  const { vault, vaultError, openWithBrowser } = useSession();
  const [useRecovery, setUseRecovery] = useState(false);

  if (useRecovery) return <RecoveryKeyCard onBack={() => setUseRecovery(false)} />;
  if (vault.status === "empty") {
    return (
      <Card className="max-w-xl">
        <CardHeader>
          <CardTitle>Connect a phone first</CardTitle>
          <CardDescription>
            No phone syncs to this account yet. Create a pairing code on the Devices page, then in the SMS Bridge app open
            Sync → Connect and enter this server&apos;s address and the code. Your messages appear here afterwards.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button variant="outline" onClick={() => void openWithBrowser()}>Check again</Button>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="max-w-xl">
      {vault.status === "approval" ? (
        <>
          <CardHeader>
            <CardTitle>Approve this browser on your phone</CardTitle>
            <CardDescription>
              Your messages are end-to-end encrypted. A phone that already syncs can let this browser read them.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="rounded-md border bg-muted px-4 py-3 text-center">
              <p className="text-xs text-muted-foreground">This browser&apos;s safety code</p>
              <p className="font-mono text-3xl tracking-widest">{vault.safetyCode}</p>
            </div>
            {vault.noApprovedPhone && (
              <Alert tone="warning">
                <p className="font-medium">No phone is approved on this account right now, so nothing can approve this browser yet.</p>
                <p className="mt-1">
                  On your phone open SMS Bridge → Sync. If it is not connected, create a pairing code on the{" "}
                  <a className="underline" href="/devices/">Devices</a> page and connect it. If it says{" "}
                  <span className="font-medium">No approved device is left</span>, tap{" "}
                  <span className="font-medium">Start over with this phone</span>. Then approve this browser as below.
                </p>
              </Alert>
            )}
            <ol className="list-decimal space-y-1 pl-5 text-sm">
              <li>On your phone open SMS Bridge → <span className="font-medium">Sync</span>.</li>
              <li>
                Under <span className="font-medium">Devices waiting for approval</span>, tap Refresh, then{" "}
                <span className="font-medium">Approve…</span> next to this browser.
              </li>
              <li>Approve only if the phone shows exactly the code above.</li>
            </ol>
            <p className="text-xs text-muted-foreground">Waiting for approval… this page opens by itself once approved.</p>
            <button className="text-sm text-primary underline-offset-4 hover:underline" onClick={() => setUseRecovery(true)}>
              Use the recovery key instead
            </button>
          </CardContent>
        </>
      ) : (
        <>
          <CardHeader>
            <CardTitle>Your messages are locked</CardTitle>
            <CardDescription>
              {vault.status === "loading"
                ? `Decrypting… ${vault.loaded.toLocaleString()} events`
                : vault.status === "connecting" || vault.status === "unlocking"
                  ? "Opening…"
                  : "Messages are decrypted only in this browser."}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {vaultError && <Alert tone="error">{vaultError}</Alert>}
            <Button onClick={() => void openWithBrowser()} disabled={vault.status !== "locked"}>
              Open messages
            </Button>
            <div>
              <button className="text-sm text-primary underline-offset-4 hover:underline" onClick={() => setUseRecovery(true)}>
                Use the recovery key instead
              </button>
            </div>
          </CardContent>
        </>
      )}
    </Card>
  );
}

function RecoveryKeyCard({ onBack }: { onBack: () => void }) {
  const { vault, vaultError, unlock } = useSession();
  const [key, setKey] = useState("");
  const busy = vault.status === "unlocking" || vault.status === "loading";

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (await unlock(key)) setKey("");
  }

  return (
    <Card className="max-w-xl">
      <CardHeader>
        <CardTitle>Unlock with the recovery key</CardTitle>
        <CardDescription>
          The key you saved when you turned on sync. It is used only in this tab; it is never sent to the server or stored.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="space-y-3" autoComplete="off">
          <div className="space-y-2">
            <Label htmlFor="recovery-key">Recovery key</Label>
            <Input
              id="recovery-key"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder="XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX"
              spellCheck={false}
              autoCapitalize="characters"
              autoComplete="off"
              className="font-mono"
              disabled={busy}
            />
          </div>
          {vaultError && <Alert tone="error">{vaultError}</Alert>}
          <div className="flex gap-2">
            <Button type="submit" disabled={busy || key.trim().length === 0}>
              {vault.status === "unlocking" ? "Unlocking…" : vault.status === "loading" ? `Decrypting… ${vault.loaded.toLocaleString()} events` : "Unlock"}
            </Button>
            <Button type="button" variant="ghost" onClick={onBack} disabled={busy}>
              Back
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
