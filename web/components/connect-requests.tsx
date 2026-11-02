import { useEffect, useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api, ApiError } from "@/lib/api";

type Pending = { id: string; name: string; createdAt: string; options: number[] };

/**
 * A phone without a camera asked to join this account: it shows a number, and the owner taps the
 * same number here. Checked every few seconds while signed in.
 */
export function ConnectRequests() {
  const [pending, setPending] = useState<Pending[]>([]);
  const [result, setResult] = useState<{ tone: "info" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let stopped = false;
    const load = () => api.connectRequests().then((p) => !stopped && setPending(p), () => {});
    void load();
    const timer = setInterval(load, 4000);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, []);

  const current = pending[0];
  if (!current && !result) return null;

  async function pick(n: number | null) {
    if (!current) return;
    setBusy(true);
    try {
      if (n == null) {
        await api.rejectConnect(current.id);
        setResult({ tone: "info", text: `${current.name} was not connected.` });
      } else {
        const r = await api.confirmConnect(current.id, n);
        setResult(
          r.approved
            ? { tone: "info", text: `${current.name} is connecting. Follow the steps on the phone.` }
            : { tone: "error", text: `That number did not match, so ${current.name} was not connected. Try again from the phone.` },
        );
      }
      setPending((p) => p.filter((x) => x.id !== current.id));
    } catch (e) {
      setResult({ tone: "error", text: e instanceof ApiError ? e.message : "Something went wrong." });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4" role="dialog" aria-modal="true">
      <Card className="w-full max-w-sm">
        {current ? (
          <>
            <CardHeader>
              <CardTitle>{current.name} wants to connect</CardTitle>
              <CardDescription>Tap the number shown on that phone. If it was not you, choose None of these.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex justify-center gap-3">
                {current.options.map((n) => (
                  <Button key={n} className="h-16 w-20 text-2xl" disabled={busy} onClick={() => void pick(n)}>
                    {n}
                  </Button>
                ))}
              </div>
              <Button variant="ghost" className="w-full" disabled={busy} onClick={() => void pick(null)}>
                None of these
              </Button>
            </CardContent>
          </>
        ) : (
          <CardContent className="space-y-3 pt-5">
            {result && <Alert tone={result.tone}>{result.text}</Alert>}
            <Button className="w-full" onClick={() => setResult(null)}>
              OK
            </Button>
          </CardContent>
        )}
      </Card>
    </div>
  );
}
