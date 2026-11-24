import QRCode from "qrcode";
import { useEffect, useState, type FormEvent } from "react";
import { Shell } from "@/components/shell";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { SaveAccountCard } from "@/components/save-account";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { api, ApiError } from "@/lib/api";
import { ensureDevice, forgetLocal, savePasswordVault } from "@/lib/browser-device";
import { loginSecret, MIN_PASSWORD } from "@/lib/password";
import { useSession } from "@/lib/session";
import { formatDateTime } from "@/lib/utils";

function TwoFactor() {
  const { me, refreshMe } = useSession();
  const [setup, setSetup] = useState<{ secret: string; qr: string } | null>(null);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState<{ tone: "info" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function start() {
    setMessage(null);
    try {
      const s = await api.totpSetup();
      // The QR code is drawn locally; the secret never leaves this page except to your authenticator.
      setSetup({ secret: s.secret, qr: await QRCode.toDataURL(s.otpauthUri, { margin: 1, width: 220 }) });
    } catch (e) {
      setMessage({ tone: "error", text: e instanceof ApiError ? e.message : "Could not start setup." });
    }
  }

  async function enable(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      await api.totpEnable(code);
      setSetup(null);
      setCode("");
      await refreshMe();
      setMessage({ tone: "info", text: "Two-factor authentication is on. New browsers will ask for a code." });
    } catch (err) {
      setMessage({ tone: "error", text: err instanceof ApiError ? err.message : "Could not turn it on." });
    } finally {
      setBusy(false);
    }
  }

  async function disable(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      await api.totpDisable(password, code);
      setPassword("");
      setCode("");
      await refreshMe();
      setMessage({ tone: "info", text: "Two-factor authentication is off and remembered browsers were forgotten." });
    } catch (err) {
      setMessage({ tone: "error", text: err instanceof ApiError ? err.message : "Could not turn it off." });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Two-factor authentication</CardTitle>
        <CardDescription>
          {me?.totpEnabled
            ? "On. A browser signing in for the first time needs a code from your authenticator app."
            : "Off. Turn it on so a stolen password alone cannot open this dashboard."}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {message && <Alert tone={message.tone}>{message.text}</Alert>}
        {!me?.totpEnabled && !setup && <Button onClick={start}>Set up</Button>}
        {!me?.totpEnabled && setup && (
          <form onSubmit={enable} className="space-y-4">
            <p className="text-sm">Scan this with an authenticator app (Aegis, 2FAS, Google Authenticator…), then enter the code it shows.</p>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={setup.qr} alt="QR code for your authenticator app" width={220} height={220} className="rounded-md border bg-white" />
            <p className="text-xs text-muted-foreground">
              Can&apos;t scan? Enter this key: <span className="break-all font-mono">{setup.secret}</span>
            </p>
            <div className="max-w-xs space-y-2">
              <Label htmlFor="enable-code">Code</Label>
              <Input id="enable-code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} required />
            </div>
            <div className="flex gap-2">
              <Button type="submit" disabled={busy}>Turn on</Button>
              <Button type="button" variant="ghost" onClick={() => setSetup(null)}>Cancel</Button>
            </div>
          </form>
        )}
        {me?.totpEnabled && (
          <form onSubmit={disable} className="max-w-xs space-y-3">
            <div className="space-y-2">
              <Label htmlFor="disable-password">Password</Label>
              <Input id="disable-password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="disable-code">Code</Label>
              <Input id="disable-code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} required />
            </div>
            <Button type="submit" variant="destructive" disabled={busy}>Turn off</Button>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

/** Username and password. Accounts made by scanning save them with the card on the Messages page. */
function Account() {
  const { me, vault, refreshMe } = useSession();
  const [password, setPassword] = useState("");
  const [current, setCurrent] = useState("");
  const [message, setMessage] = useState<{ tone: "info" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  if (!me) return null;
  if (!me.hasPassword) return <SaveAccountCard dismissible={false} />;

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!me) return;
    if (password.length < MIN_PASSWORD) return setMessage({ tone: "error", text: `Use at least ${MIN_PASSWORD} characters.` });
    setBusy(true);
    setMessage(null);
    try {
      const next = await loginSecret(me.username, password);
      // Older accounts may still have the plain password on the server.
      await api.setPassword(next, await loginSecret(me.username, current)).catch((err) => {
        if (err instanceof ApiError && err.code === "invalid_credentials") return api.setPassword(next, current);
        throw err;
      });
      if (vault.status === "ready") await savePasswordVault(await ensureDevice(me.userId), password);
      setPassword("");
      setCurrent("");
      await refreshMe();
      setMessage({ tone: "info", text: "Password saved. It opens your messages in any browser." });
    } catch (err) {
      setMessage({ tone: "error", text: err instanceof ApiError ? err.message : "Could not save the password." });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Account</CardTitle>
        <CardDescription>
          Username <span className="font-mono">{me.username}</span>. Sign in anywhere with it and your password, or scan with your phone.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={save} className="max-w-xs space-y-3">
          {message && <Alert tone={message.tone}>{message.text}</Alert>}
          <div className="space-y-2">
            <Label htmlFor="current-password">Current password</Label>
            <Input id="current-password" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
          </div>
          <div className="space-y-2">
            <Label htmlFor="new-password">New password</Label>
            <Input id="new-password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            <p className="text-xs text-muted-foreground">At least {MIN_PASSWORD} characters. You can enter the same password again to let it open your messages.</p>
          </div>
          <Button type="submit" variant="outline" disabled={busy}>{busy ? "Saving…" : "Save password"}</Button>
        </form>
      </CardContent>
    </Card>
  );
}

function ThisBrowser() {
  const { forgetBrowser } = useSession();
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState(false);
  return (
    <Card>
      <CardHeader>
        <CardTitle>This browser</CardTitle>
        <CardDescription>
          Once a phone approves this browser, it opens your messages without the recovery key. Forget it on shared or
          lost computers: its key is deleted and it must be approved again.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {done && <Alert>This browser was forgotten and revoked.</Alert>}
        {confirm ? (
          <div className="flex items-center gap-2">
            <Button variant="destructive" onClick={() => void forgetBrowser().then(() => { setDone(true); setConfirm(false); })}>
              Forget this browser
            </Button>
            <Button variant="ghost" onClick={() => setConfirm(false)}>Cancel</Button>
          </div>
        ) : (
          <Button variant="outline" onClick={() => setConfirm(true)}>Forget this browser…</Button>
        )}
      </CardContent>
    </Card>
  );
}

function Browsers() {
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState(false);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Remembered sign-ins</CardTitle>
        <CardDescription>Browsers that passed two-factor sign-in skip the code for 90 days.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {done && <Alert>Every browser, including this one, will need a code at its next sign-in.</Alert>}
        {confirm ? (
          <div className="flex items-center gap-2">
            <Button variant="destructive" onClick={() => void api.forgetBrowsers().then(() => { setDone(true); setConfirm(false); })}>
              Forget all browsers
            </Button>
            <Button variant="ghost" onClick={() => setConfirm(false)}>Cancel</Button>
          </div>
        ) : (
          <Button variant="outline" onClick={() => setConfirm(true)}>Forget all browsers…</Button>
        )}
      </CardContent>
    </Card>
  );
}

/** "Delete my account": everything on the server goes, after the password (if any) is confirmed. */
function DeleteAccount() {
  const { me, lock } = useSession();
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!me) return null;

  async function remove(e: FormEvent) {
    e.preventDefault();
    if (!me) return;
    setBusy(true);
    setError(null);
    try {
      if (me.hasPassword) {
        // Older accounts may still have the plain password on the server.
        await api.deleteAccount(await loginSecret(me.username, password)).catch((err) => {
          if (err instanceof ApiError && err.code === "wrong_password") return api.deleteAccount(password);
          throw err;
        });
      } else {
        await api.deleteAccount();
      }
      lock();
      await forgetLocal(me.userId).catch(() => {});
      window.location.replace("/delete-account/?done=1");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not delete the account. Try again.");
      setBusy(false);
    }
  }

  return (
    <Card className="border-destructive/40">
      <CardHeader>
        <CardTitle>Delete my account</CardTitle>
        <CardDescription>
          Deletes your account, every linked phone and browser, and all messages stored on the server. This cannot be undone. The
          SMS on your phones are not touched.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {!open ? (
          <Button variant="outline" onClick={() => setOpen(true)}>Delete my account…</Button>
        ) : (
          <form onSubmit={remove} className="max-w-xs space-y-3">
            {error && <Alert tone="error">{error}</Alert>}
            {me.hasPassword ? (
              <div className="space-y-2">
                <Label htmlFor="delete-password">Your password</Label>
                <Input id="delete-password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
              </div>
            ) : (
              <p className="text-sm">Are you sure? Your phones will need to be linked again to use SMS Bridge.</p>
            )}
            <div className="flex gap-2">
              <Button type="submit" variant="destructive" disabled={busy}>{busy ? "Deleting…" : "Delete everything"}</Button>
              <Button type="button" variant="ghost" onClick={() => { setOpen(false); setPassword(""); setError(null); }}>Cancel</Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

export default function Settings() {
  const { me } = useSession();
  return (
    <Shell title="Settings">
      <div className="max-w-2xl space-y-6">
        <Account />
        <ThisBrowser />
        {me?.totpEnabled && <TwoFactor />}
        {me?.totpEnabled && <Browsers />}
        <Card>
          <CardHeader>
            <CardTitle>Privacy</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm text-muted-foreground">
            <p>This page is read-only. To send or delete messages, use your phone.</p>
            <p>
              Messages are encrypted on your phone and opened only in browsers you linked or signed into with your password. The
              server never sees your messages or your password.
            </p>
            <p>
              <a className="underline" href="/privacy/">Privacy policy</a> · <a className="underline" href="/terms/">Terms of use</a>
            </p>
          </CardContent>
        </Card>
        <DeleteAccount />
      </div>
    </Shell>
  );
}
