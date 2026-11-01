import QRCode from "qrcode";
import { useState, type FormEvent } from "react";
import { Shell } from "@/components/shell";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { api, ApiError } from "@/lib/api";
import { useSession } from "@/lib/session";

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

function Browsers() {
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState(false);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Remembered browsers</CardTitle>
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

export default function Settings() {
  return (
    <Shell title="Settings">
      <div className="max-w-2xl space-y-6">
        <TwoFactor />
        <Browsers />
        <Card>
          <CardHeader>
            <CardTitle>About browser access</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm text-muted-foreground">
            <p>
              This dashboard is read-only. To send or delete messages, use your phone; nothing here can change your
              messages.
            </p>
            <p>
              A browser reads messages by unlocking the vault with your recovery key, in memory only. Anyone with your
              recovery key and your sign-in can read your messages, so keep it offline. Approving a browser from your phone
              with a safety code, without typing the recovery key, is planned.
            </p>
          </CardContent>
        </Card>
      </div>
    </Shell>
  );
}
