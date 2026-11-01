import { useState, type FormEvent, type ReactNode } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { useSession } from "@/lib/session";

/** Shows its children only once the vault is unlocked in this tab; otherwise the unlock form. */
export function VaultGate({ children }: { children: ReactNode }) {
  const { vault } = useSession();
  if (vault.status === "ready") return <>{children}</>;
  return <UnlockCard />;
}

export function UnlockCard() {
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
        <CardTitle>Unlock your messages</CardTitle>
        <CardDescription>
          Messages are end-to-end encrypted. Enter the recovery key you saved when you turned on sync. It is used only in
          this tab; it is never sent to the server or stored.
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
          <Button type="submit" disabled={busy || key.trim().length === 0}>
            {vault.status === "unlocking" && "Unlocking…"}
            {vault.status === "loading" && `Decrypting… ${vault.loaded.toLocaleString()} events`}
            {!busy && "Unlock"}
          </Button>
        </form>
        <p className="mt-4 text-xs text-muted-foreground">
          The page locks again when you reload, close the tab, press Lock, or after 15 minutes without activity.
        </p>
      </CardContent>
    </Card>
  );
}
