import { useState, type FormEvent, type ReactNode } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { PairQr } from "@/components/pair-qr";
import { useSession } from "@/lib/session";


/** Shows its children only once the vault is unlocked in this tab; otherwise how to open it. */
export function VaultGate({ children }: { children: ReactNode }) {
  const { vault } = useSession();
  if (vault.status === "ready") return <>{children}</>;
  return <UnlockCard />;
}

export function UnlockCard() {
  const { vault, vaultError, openWithBrowser, requestConfirm } = useSession();
  const [useRecovery, setUseRecovery] = useState(false);

  if (useRecovery) return <RecoveryKeyCard onBack={() => setUseRecovery(false)} />;

  if (vault.status === "approval" && vault.confirmRequested && !vault.noApprovedPhone) {
    return (
      <Card className="max-w-xl">
        <CardHeader>
          <CardTitle>Confirm on your phone</CardTitle>
          <CardDescription>
            {vault.confirmNumber == null
              ? "Your phone got a notification. Open it (or SMS Bridge → Sync → Confirm with a number)."
              : "On your phone, tap this number:"}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4 text-center">
          {vault.confirmNumber == null ? (
            <p className="py-6 text-sm text-muted-foreground">Waiting for your phone…</p>
          ) : (
            <p className="py-4 font-mono text-7xl font-semibold tracking-wider">{vault.confirmNumber}</p>
          )}
          <p className="text-xs text-muted-foreground">This page opens by itself once you tap it.</p>
        </CardContent>
      </Card>
    );
  }

  if (vault.status === "approval") {
    const connectPhone = vault.noApprovedPhone;
    return (
      <Card className="max-w-xl">
        <CardHeader>
          <CardTitle>{connectPhone ? "Connect your phone" : "Scan with your phone"}</CardTitle>
          <CardDescription>
            {connectPhone
              ? "Open SMS Bridge on your Android phone, go to Sync and tap Scan QR code."
              : "Open SMS Bridge on your phone, go to Sync and tap Scan to approve a browser."}{" "}
            This page opens by itself once it is done.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <PairQr withCode={connectPhone} deviceId={vault.deviceId} fingerprint={vault.fingerprint} />
          {connectPhone && (
            <p className="text-center text-xs text-muted-foreground">
              No app yet? Install it from <a className="underline" href="/download/sms-bridge.apk">this link</a> on the phone.
            </p>
          )}
          {vault.confirmDeclined && (
            <Alert tone="warning">Not confirmed: a different number was tapped on the phone. Try again.</Alert>
          )}
          {!connectPhone && (
            <Button variant="outline" className="w-full" onClick={() => void requestConfirm()}>
              Can&apos;t scan? Confirm on your phone instead
            </Button>
          )}
          <details className="text-sm">
            <summary className="cursor-pointer text-muted-foreground">More options</summary>
            <div className="mt-2 space-y-3">
              <div>
                <p className="text-muted-foreground">Compare codes: approve on a connected phone (Sync → Devices waiting for approval) only if it shows</p>
                <p className="text-center font-mono text-2xl tracking-widest">{vault.safetyCode}</p>
              </div>
              <button className="text-primary underline-offset-4 hover:underline" onClick={() => setUseRecovery(true)}>
                I have a recovery key
              </button>
            </div>
          </details>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="max-w-xl">
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
        <details className="text-sm">
          <summary className="cursor-pointer text-muted-foreground">More options</summary>
          <button className="mt-2 text-primary underline-offset-4 hover:underline" onClick={() => setUseRecovery(true)}>
            I have a recovery key
          </button>
        </details>
      </CardContent>
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
