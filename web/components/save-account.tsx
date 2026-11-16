import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { api, ApiError } from "@/lib/api";
import { ensureDevice, savePasswordVault } from "@/lib/browser-device";
import { loginSecret, MIN_PASSWORD } from "@/lib/password";
import { useSession } from "@/lib/session";

const HIDDEN = "sms-bridge.save-account-hidden";

/**
 * "Save your account": an account made by scanning gets a username and password, so the user can
 * sign in from any browser later and see these phones' messages without scanning again.
 */
export function SaveAccountCard({ dismissible = true }: { dismissible?: boolean }) {
  const { me, vault, refreshMe } = useSession();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hidden, setHidden] = useState(() => {
    try {
      return sessionStorage.getItem(HIDDEN) === "1";
    } catch {
      return false;
    }
  });
  if (!me || me.hasPassword || vault.status !== "ready" || (dismissible && hidden)) return null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!me) return;
    setError(null);
    if (password.length < MIN_PASSWORD) return setError(`Use at least ${MIN_PASSWORD} characters.`);
    setBusy(true);
    try {
      const secret = await loginSecret(username, password);
      await api.saveAccount(username.trim(), secret);
      await savePasswordVault(await ensureDevice(me.userId), password);
      setPassword("");
      await refreshMe();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not save. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mb-4">
      <CardHeader className="pb-3">
        <CardTitle className="text-base">Save your account</CardTitle>
        <CardDescription>Choose a username and password to see these messages from any browser, without scanning again.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <div className="space-y-1">
            <Label htmlFor="save-username">Username</Label>
            <Input id="save-username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required minLength={3} pattern="[A-Za-z0-9._\-]{3,64}" title="Letters, digits, dots, dashes or underscores" />
          </div>
          <div className="space-y-1">
            <Label htmlFor="save-password">Password</Label>
            <div className="flex gap-1">
              <Input id="save-password" type={show ? "text" : "password"} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
              <Button type="button" variant="ghost" size="sm" onClick={() => setShow((s) => !s)}>{show ? "Hide" : "Show"}</Button>
            </div>
          </div>
          <div className="flex gap-2">
            <Button type="submit" disabled={busy}>{busy ? "Saving…" : "Save"}</Button>
            {dismissible && <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setHidden(true);
                try {
                  sessionStorage.setItem(HIDDEN, "1");
                } catch {
                  /* fine */
                }
              }}
            >
              Not now
            </Button>}
          </div>
        </form>
        <p className="mt-2 text-xs text-muted-foreground">
          At least {MIN_PASSWORD} characters. Your password opens your messages, so keep it safe: it is never sent to the server.
        </p>
        {error && <Alert tone="error" className="mt-2">{error}</Alert>}
      </CardContent>
    </Card>
  );
}
