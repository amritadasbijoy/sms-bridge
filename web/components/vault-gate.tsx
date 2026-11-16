import { useState, type FormEvent, type ReactNode } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { PhoneSignIn } from "@/components/phone-sign-in";
import { api } from "@/lib/api";
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
  const [error, setError] = useState<string | null>(null);

  if (useRecovery) return <RecoveryKeyCard onBack={() => setUseRecovery(false)} />;

  if (vault.status === "approval") {
    const pending = vault.deviceId;
    return (
      <Card className="max-w-xl">
        <CardHeader>
          <CardTitle>Allow this browser</CardTitle>
          <CardDescription>
            Open SMS Bridge on your phone and tap <strong>Scan QR code</strong>, or <strong>Type code</strong> and type the 8 digits.
            This page opens by itself once you allow it.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {error && <Alert tone="error">{error}</Alert>}
          <PhoneSignIn
            onApproved={() => {
              // The browser entry that waited before is not needed any more.
              void api.revoke(pending).catch(() => {}).finally(() => window.location.reload());
            }}
            onFailed={setError}
          />
          <p className="text-center text-xs text-muted-foreground">
            No app yet? Install it from <a className="underline" href="/download/sms-bridge.apk">this link</a> on the phone.
          </p>
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
