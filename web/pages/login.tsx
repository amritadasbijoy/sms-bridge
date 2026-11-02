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

/** The Android app's current version, from the server's latest release. */
function useAppVersion(): string | null {
  const [version, setVersion] = useState<string | null>(null);
  useEffect(() => {
    fetch("/api/v1/app/latest", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((r: { versionName?: string } | null) => setVersion(r?.versionName ?? null), () => {});
  }, []);
  return version;
}

/**
 * The front page, like WhatsApp Web: the steps on one side, the QR code on the other. A phone
 * without an account that scans it gets one on its own, so there is nothing to sign up for.
 */
export default function Login() {
  const { me, refreshMe } = useSession();
  const router = useRouter();
  const version = useAppVersion();
  const [mode, setMode] = useState<"phone" | "username" | "password" | "create">("phone");
  const [username, setUsername] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const invite = router.isReady && typeof router.query.invite === "string" ? router.query.invite : "";

  useEffect(() => {
    if (me) void router.replace("/dashboard/");
  }, [me, router]);

  const approved = useCallback(() => void refreshMe(), [refreshMe]);
  const failed = useCallback((m: string) => {
    setError(m);
    setSubmitted(null);
  }, []);

  async function acceptInvite() {
    setBusy(true);
    setError(null);
    try {
      await api.start(invite);
      await refreshMe();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not reach the server.");
    } finally {
      setBusy(false);
    }
  }

  if (mode === "password" || mode === "create") {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 px-4 py-8">
        <Head>
          <title>Sign in · SMS Bridge</title>
        </Head>
        <PasswordForm initialCreating={mode === "create"} onBack={() => setMode("phone")} />
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center px-4 py-8">
      <Head>
        <title>SMS Bridge · Read your SMS on this computer</title>
      </Head>
      <div className="w-full max-w-3xl">
        <p className="mb-4 text-center text-lg font-semibold sm:text-left">SMS Bridge</p>
        <Card>
          <CardContent className="grid gap-8 p-6 sm:p-8 md:grid-cols-[1fr_auto]">
            <div className="space-y-5">
              <div className="space-y-1">
                <h1 className="text-2xl font-semibold">Read your SMS on this computer</h1>
                <p className="text-sm text-muted-foreground">New messages appear here the moment your phone gets them. No account, no password.</p>
              </div>
              {mode === "phone" ? (
                <ol className="space-y-4 text-sm">
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">1</span>
                    <div className="space-y-2">
                      <p>Install SMS Bridge on your Android phone.</p>
                      <a
                        href="/download/sms-bridge.apk"
                        download
                        className="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary/90"
                      >
                        Download the app{version ? ` (${version})` : ""}
                      </a>
                      <p className="text-xs text-muted-foreground">Open the downloaded file on the phone and tap Install. If Android asks, allow installing from your browser.</p>
                    </div>
                  </li>
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">2</span>
                    <p>
                      Open SMS Bridge and tap <strong>Scan QR code</strong>.
                    </p>
                  </li>
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">3</span>
                    <p>Point your phone at the code on this screen. That&apos;s it.</p>
                  </li>
                </ol>
              ) : (
                <p className="text-sm text-muted-foreground">Type your username. Your phone gets a notification and you tap the number shown here.</p>
              )}
              {invite && (
                <Button variant="outline" disabled={busy} onClick={() => void acceptInvite()}>
                  {busy ? "Please wait…" : "Accept the invite"}
                </Button>
              )}
            </div>
            <div className="flex flex-col items-center gap-3 md:w-[260px]">
              {error && <Alert tone="error">{error}</Alert>}
              {mode === "phone" && <PhoneSignIn onApproved={approved} onFailed={failed} />}
              {mode === "username" && !submitted && (
                <form
                  className="w-full space-y-3"
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
              {mode === "phone" && <p className="text-center text-xs text-muted-foreground">The code refreshes on its own.</p>}
            </div>
          </CardContent>
        </Card>
        <div className="mt-3 flex flex-wrap justify-center gap-1 text-sm">
          {mode !== "username" ? (
            <Button variant="ghost" size="sm" onClick={() => { setMode("username"); setError(null); }}>Can&apos;t scan? Use my username</Button>
          ) : (
            <Button variant="ghost" size="sm" onClick={() => { setMode("phone"); setSubmitted(null); setError(null); }}>Scan a QR code instead</Button>
          )}
          <Button variant="ghost" size="sm" onClick={() => setMode("password")}>Sign in with a password</Button>
        </div>
      </div>
    </div>
  );
}
