import Head from "next/head";
import { useRouter } from "next/router";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { api, ApiError } from "@/lib/api";
import { useSession } from "@/lib/session";

export default function Login() {
  const { me, refreshMe } = useSession();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [needCode, setNeedCode] = useState(false);
  const [registrationOpen, setRegistrationOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const codeRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (me) void router.replace("/dashboard/");
  }, [me, router]);
  useEffect(() => {
    api.status().then((s) => setRegistrationOpen(s.registrationOpen), () => {});
  }, []);
  useEffect(() => {
    if (needCode) codeRef.current?.focus();
  }, [needCode]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (creating) await api.register(username, password);
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
    <div className="flex min-h-screen items-center justify-center px-4">
      <Head>
        <title>Sign in · SMS Bridge</title>
      </Head>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>{creating ? "Create the owner account" : "Sign in to SMS Bridge"}</CardTitle>
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
            {registrationOpen && !needCode && (
              <Button type="button" variant="ghost" className="w-full" onClick={() => setCreating((c) => !c)}>
                {creating ? "I already have an account" : "Create an account"}
              </Button>
            )}
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
