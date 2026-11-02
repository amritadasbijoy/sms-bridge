import Head from "next/head";
import { useRouter } from "next/router";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import QRCode from "qrcode";
import { api, ApiError, type LoginStatus } from "@/lib/api";
import { generateKeys, saveApprovedDevice } from "@/lib/browser-device";
import { confirmNumber } from "@/lib/confirm";
import { deviceFingerprint } from "@/lib/pair";
import { useSession } from "@/lib/session";

function PasswordForm({ initialCreating, onBack }: { initialCreating: boolean; onBack: () => void }) {
  const { refreshMe } = useSession();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [needCode, setNeedCode] = useState(false);
  const [registrationOpen, setRegistrationOpen] = useState(false);
  const [creating, setCreating] = useState(initialCreating);
  const [invite, setInvite] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const codeRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api.status().then((s) => setRegistrationOpen(s.registrationOpen), () => {});
  }, []);
  // An invite link (…/login/?invite=XXXXX-XXXXX) opens account creation with the code filled in.
  useEffect(() => {
    if (!router.isReady) return;
    const code = typeof router.query.invite === "string" ? router.query.invite : "";
    if (code) {
      setInvite(code);
      setCreating(true);
    }
  }, [router.isReady, router.query.invite]);
  useEffect(() => {
    if (needCode) codeRef.current?.focus();
  }, [needCode]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (creating && invite.trim()) await api.registerWithInvite(username, password, invite);
      else if (creating) await api.register(username, password);
      else await api.login(username, password, needCode ? code : undefined);
      setPassword("");
      setCode("");
      await refreshMe();
    } catch (err) {
      if (err instanceof ApiError && err.code === "totp_required") {
        setNeedCode(true);
      } else if (err instanceof ApiError && err.status === 429) {
        setError("Too many attempts. Wait a minute and try again.");
      } else {
        setError(err instanceof ApiError ? err.message : "Could not reach the server.");
        if (needCode) setCode("");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>{creating ? (registrationOpen ? "Create the owner account" : "Create your account") : "Sign in to SMS Bridge"}</CardTitle>
          <CardDescription>
            {needCode ? "Enter the 6-digit code from your authenticator app." : "Your self-hosted message dashboard."}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={submit} className="space-y-4">
            {!needCode && (
              <>
                <div className="space-y-2">
                  <Label htmlFor="username">Username</Label>
                  <Input id="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="password">Password</Label>
                  <Input
                    id="password"
                    type="password"
                    autoComplete={creating ? "new-password" : "current-password"}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    minLength={creating ? 12 : undefined}
                    required
                  />
                  {creating && <p className="text-xs text-muted-foreground">At least 12 characters.</p>}
                </div>
                {creating && !registrationOpen && (
                  <div className="space-y-2">
                    <Label htmlFor="invite">Invite code</Label>
                    <Input
                      id="invite"
                      value={invite}
                      onChange={(e) => setInvite(e.target.value)}
                      placeholder="XXXXX-XXXXX"
                      autoCapitalize="characters"
                      spellCheck={false}
                      className="font-mono"
                      required
                    />
                  </div>
                )}
              </>
            )}
            {needCode && (
              <div className="space-y-2">
                <Label htmlFor="code">Authentication code</Label>
                <Input
                  id="code"
                  ref={codeRef}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="[0-9 ]{6,7}"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  required
                />
                <p className="text-xs text-muted-foreground">This browser will be remembered for 90 days.</p>
              </div>
            )}
            {error && <Alert tone="error">{error}</Alert>}
            <Button type="submit" className="w-full" disabled={busy}>
              {busy ? "Please wait…" : creating ? "Create account" : needCode ? "Verify" : "Sign in"}
            </Button>
            {needCode && (
              <Button type="button" variant="ghost" className="w-full" onClick={() => { setNeedCode(false); setCode(""); setPassword(""); }}>
                Back
              </Button>
            )}
            {!needCode && (
              <Button type="button" variant="ghost" className="w-full" onClick={() => { setCreating((c) => !c); setError(null); }}>
                {creating ? "I already have an account" : registrationOpen ? "Create an account with a password" : "I have an invite code"}
              </Button>
            )}
            <Button type="button" variant="ghost" className="w-full" onClick={onBack}>
              Sign in with your phone instead
            </Button>
          </form>
        </CardContent>
      </Card>
  );
}

type Keys = Awaited<ReturnType<typeof generateKeys>>;

/** "Chrome on macOS", shown on the phone that approves this sign-in. */
function browserName(): string {
  const ua = typeof navigator === "undefined" ? "" : navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const os = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Mac OS X/.test(ua) ? "macOS" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${browser} on ${os}` : browser;
}

/**
 * Sign in by phone: this browser makes its own key pair and a sign-in request; a connected phone
 * scans the QR code (or, with [username], taps the number shown here). On approval this browser
 * has a session and is already allowed to read messages.
 */
function PhoneSignIn({ username, onApproved, onFailed }: { username?: string; onApproved: () => void; onFailed: (message: string) => void }) {
  const [qr, setQr] = useState<string | null>(null);
  const [number, setNumber] = useState<number | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setInterval> | undefined;
    (async () => {
      const keys: Keys = await generateKeys();
      const req = await api.createLoginRequest(browserName(), keys.encPublic, keys.signPublic, username);
      const fingerprint = await deviceFingerprint(keys.encPublic, keys.signPublic);
      if (stopped) return;
      if (!username) {
        const q = new URLSearchParams({ v: "1", s: window.location.origin, r: req.requestId, f: fingerprint });
        setQr(await QRCode.toDataURL(`smsbridge://login?${q.toString()}`, { margin: 1, width: 240 }));
      }
      const started = Date.now();
      timer = setInterval(async () => {
        let st: LoginStatus;
        try {
          st = await api.loginStatus(req.requestId, req.secret);
        } catch {
          return; // keep trying through brief network trouble
        }
        if (stopped) return;
        if (st.status === "approved" && st.userId && st.deviceId) {
          clearInterval(timer);
          await saveApprovedDevice(st.userId, st.deviceId, keys);
          onApproved();
        } else if (st.status === "declined") {
          clearInterval(timer);
          onFailed("Not confirmed: a different number was tapped on the phone.");
        } else if (st.status === "expired" || Date.now() - started > 5 * 60_000) {
          clearInterval(timer);
          if (username) onFailed("The request expired. Try again.");
          else setAttempt((a) => a + 1); // a fresh QR code
        } else if (st.challenge) {
          setNumber(await confirmNumber(st.challenge, keys.encPublic, keys.signPublic));
        }
      }, 2000);
    })().catch(() => !stopped && onFailed("Could not start signing in. Reload the page."));
    return () => {
      stopped = true;
      if (timer) clearInterval(timer);
    };
  }, [username, attempt, onApproved, onFailed]);

  if (username) {
    return (
      <div className="space-y-3 text-center">
        {number == null ? (
          <p className="py-6 text-sm text-muted-foreground">Your phone got a notification. Open it…</p>
        ) : (
          <>
            <p className="text-sm">On your phone, tap this number:</p>
            <p className="py-2 font-mono text-7xl font-semibold tracking-wider">{number}</p>
          </>
        )}
      </div>
    );
  }
  return (
    <div className="flex flex-col items-center gap-2">
      {qr ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={qr} alt="Sign-in QR code for the SMS Bridge app" width={240} height={240} className="rounded-md border bg-white p-1" />
      ) : (
        <div className="h-[240px] w-[240px] animate-pulse rounded-md bg-muted" />
      )}
    </div>
  );
}

export default function Login() {
  const { me, refreshMe } = useSession();
  const router = useRouter();
  const [mode, setMode] = useState<"phone" | "username" | "password" | "create">("phone");
  const [username, setUsername] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [registrationOpen, setRegistrationOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const invite = router.isReady && typeof router.query.invite === "string" ? router.query.invite : "";

  useEffect(() => {
    if (me) void router.replace("/dashboard/");
  }, [me, router]);
  useEffect(() => {
    api.status().then((s) => setRegistrationOpen(s.registrationOpen), () => {});
  }, []);

  const approved = useCallback(() => void refreshMe(), [refreshMe]);
  const failed = useCallback((m: string) => {
    setError(m);
    setSubmitted(null);
  }, []);

  async function getStarted() {
    setBusy(true);
    setError(null);
    try {
      await api.start(invite || undefined);
      await refreshMe();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not reach the server.");
    } finally {
      setBusy(false);
    }
  }

  const canStart = registrationOpen || Boolean(invite);
  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <Head>
        <title>Sign in · SMS Bridge</title>
      </Head>
      {mode === "password" || mode === "create" ? (
        <PasswordForm initialCreating={mode === "create"} onBack={() => setMode("phone")} />
      ) : (
        <Card className="w-full max-w-sm">
          <CardHeader>
            <CardTitle>{canStart ? "Welcome to SMS Bridge" : "Sign in with your phone"}</CardTitle>
            <CardDescription>
              {mode === "phone"
                ? "Open SMS Bridge on a connected phone, go to Sync and tap Scan a browser's QR code."
                : "Your connected phone confirms it: no password needed."}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {canStart && (
              <div className="space-y-2 rounded-md border p-3">
                <p className="text-sm font-medium">New here?</p>
                <Button className="w-full" disabled={busy} onClick={() => void getStarted()}>
                  {busy ? "Please wait…" : invite ? "Accept the invite and get started" : "Get started"}
                </Button>
                <p className="text-xs text-muted-foreground">No password needed: you connect your phone with one scan.</p>
              </div>
            )}
            {error && <Alert tone="error">{error}</Alert>}
            {mode === "phone" && <PhoneSignIn onApproved={approved} onFailed={failed} />}
            {mode === "username" && !submitted && (
              <form
                className="space-y-3"
                onSubmit={(e) => {
                  e.preventDefault();
                  setError(null);
                  setSubmitted(username.trim());
                }}
              >
                <Label htmlFor="login-username">Your username</Label>
                <Input id="login-username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
                <Button type="submit" className="w-full">Continue</Button>
              </form>
            )}
            {mode === "username" && submitted && <PhoneSignIn username={submitted} onApproved={approved} onFailed={failed} />}
            <div className="flex flex-col gap-1">
              {mode !== "username" ? (
                <Button variant="ghost" onClick={() => { setMode("username"); setError(null); }}>Can&apos;t scan? Use my username instead</Button>
              ) : (
                <Button variant="ghost" onClick={() => { setMode("phone"); setSubmitted(null); setError(null); }}>Scan a QR code instead</Button>
              )}
              <Button variant="ghost" onClick={() => setMode("password")}>Use a password</Button>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
