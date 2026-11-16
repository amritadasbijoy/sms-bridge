import { useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { api, ApiError } from "@/lib/api";
import { ensureDevice, savePasswordVault } from "@/lib/browser-device";
import { moveIntoAccount } from "@/lib/account";
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
  /** "I already have an account": sign in to it and move this phone there. */
  const [existing, setExisting] = useState(false);
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
    if (existing) {
      setBusy(true);
      try {
        await moveIntoAccount(me.userId, username.trim(), password);
        setPassword("");
        window.location.reload(); // open the other account; MoveWatcher finishes the move there
      } catch (err) {
        setError(
          err instanceof ApiError && err.code === "invalid_credentials"
            ? "Wrong username or password."
            : err instanceof ApiError
              ? err.message
              : "Could not sign in. Check your connection and try again.",
        );
        setBusy(false);
      }
      return;
    }
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
        <CardTitle className="text-base">{existing ? "Sign in to your account" : "Save your account"}</CardTitle>
        <CardDescription>
          {existing
            ? "This phone moves into your account by itself, and you see all your phones there."
            : "Choose a username and password to see these messages from any browser, without scanning again."}
        </CardDescription>
        <div className="flex gap-1 pt-2 text-sm" role="group" aria-label="Account">
          {[false, true].map((v) => (
            <button
              key={String(v)}
              type="button"
              onClick={() => { setExisting(v); setError(null); }}
              aria-pressed={existing === v}
              className={existing === v ? "rounded-full border border-primary bg-primary/10 px-3 py-1 font-medium text-primary" : "rounded-full border px-3 py-1 hover:bg-accent"}
            >
              {v ? "I already have an account" : "I'm new"}
            </button>
          ))}
        </div>
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
              <Input id="save-password" type={show ? "text" : "password"} autoComplete={existing ? "current-password" : "new-password"} value={password} onChange={(e) => setPassword(e.target.value)} required />
              <Button type="button" variant="ghost" size="sm" onClick={() => setShow((s) => !s)}>{show ? "Hide" : "Show"}</Button>
            </div>
          </div>
          <div className="flex gap-2">
            <Button type="submit" disabled={busy}>{busy ? (existing ? "Signing in…" : "Saving…") : existing ? "Sign in" : "Save"}</Button>
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
          {existing ? "Your password opens your messages; it is never sent to the server." : <>At least {MIN_PASSWORD} characters.</>}{" "}
          {!existing && "Your password opens your messages, so keep it safe: it is never sent to the server."}
        </p>
        {error && <Alert tone="error" className="mt-2">{error}</Alert>}
      </CardContent>
    </Card>
  );
}
