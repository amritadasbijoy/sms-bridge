import Head from "next/head";
import { useRouter } from "next/router";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { api, ApiError } from "@/lib/api";
import { PhoneSignIn } from "@/components/phone-sign-in";
import { adoptWithPassword } from "@/lib/browser-device";
import { passwordSignIn } from "@/lib/account";
import { useSession } from "@/lib/session";

/**
 * Sign in with a username and password. The password stays in this browser: the server gets a
 * derived login secret, and the password itself opens the messages (lib/password.ts). Accounts
 * made before 0.10.0 sent the plain password; they are upgraded on their first sign-in here.
 */
function PasswordForm({ onBack }: { onBack: () => void }) {
  const { refreshMe } = useSession();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [needCode, setNeedCode] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const codeRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (needCode) codeRef.current?.focus();
  }, [needCode]);

  const signIn = (totp?: string) => passwordSignIn(username, password, totp);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const me = await signIn(needCode ? code : undefined);
      try {
        await adoptWithPassword(me.userId, password);
      } catch {
        /* The password does not open messages yet: the Messages page asks a phone instead. */
      }
      setPassword("");
      setCode("");
      await refreshMe();
    } catch (err) {
      if (err instanceof ApiError && err.code === "totp_required") {
        setNeedCode(true);
      } else if (err instanceof ApiError && err.status === 429) {
        setError("Too many attempts. Wait a minute and try again.");
      } else if (err instanceof ApiError && err.code === "invalid_credentials") {
        setError("Wrong username or password.");
        if (needCode) setCode("");
      } else {
        setError(err instanceof ApiError ? err.message : "Could not reach the server.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle>Sign in</CardTitle>
        <CardDescription>
          {needCode ? "Enter the 6-digit code from your authenticator app." : "With the username and password you saved after linking your phone."}
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
                <Input id="password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
              </div>
            </>
          )}
          {needCode && (
            <div className="space-y-2">
              <Label htmlFor="code">Authentication code</Label>
              <Input id="code" ref={codeRef} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9 ]{6,7}" value={code} onChange={(e) => setCode(e.target.value)} required />
            </div>
          )}
          {error && <Alert tone="error">{error}</Alert>}
          <Button type="submit" className="w-full" disabled={busy}>
            {busy ? "Opening your messages…" : needCode ? "Verify" : "Sign in"}
          </Button>
          <Button type="button" variant="ghost" className="w-full" onClick={onBack}>
            Scan a QR code instead
          </Button>
          <p className="text-center text-xs text-muted-foreground">
            No account yet? Link your phone with the QR code, then choose &ldquo;Save your account&rdquo;.
          </p>
        </form>
      </CardContent>
    </Card>
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
  const [mode, setMode] = useState<"phone" | "password">("phone");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const invite = router.isReady && typeof router.query.invite === "string" ? router.query.invite : "";

  useEffect(() => {
    if (me) void router.replace("/dashboard/");
  }, [me, router]);

  const approved = useCallback(() => void refreshMe(), [refreshMe]);
  const failed = useCallback((m: string) => setError(m), []);

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

  if (mode === "password") {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4 px-4 py-8">
        <Head>
          <title>Sign in · SMS Bridge</title>
        </Head>
        <PasswordForm onBack={() => setMode("phone")} />
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
                <p className="text-sm text-muted-foreground">New messages appear here the moment your phone gets them. No account needed.</p>
              </div>
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
                      Open SMS Bridge and tap <strong>Link a computer</strong>.
                    </p>
                  </li>
                  <li className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">3</span>
                    <p>Scan the QR code on this screen, or type the 8-digit code. That&apos;s it.</p>
                  </li>
                </ol>
              {invite && (
                <Button variant="outline" disabled={busy} onClick={() => void acceptInvite()}>
                  {busy ? "Please wait…" : "Accept the invite"}
                </Button>
              )}
            </div>
            <div className="flex flex-col items-center gap-3 md:w-[260px]">
              {error && <Alert tone="error">{error}</Alert>}
              <PhoneSignIn onApproved={approved} onFailed={failed} />
              <p className="text-center text-xs text-muted-foreground">The codes refresh on their own.</p>
            </div>
          </CardContent>
        </Card>
        <div className="mt-3 flex flex-wrap justify-center gap-1 text-sm">
          <Button variant="ghost" size="sm" onClick={() => setMode("password")}>Have an account? Sign in with username and password</Button>
        </div>
      </div>
    </div>
  );
}
