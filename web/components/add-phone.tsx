import QRCode from "qrcode";
import { useEffect, useRef, useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api, ApiError } from "@/lib/api";
import { ensureDevice, rawVaultKeys } from "@/lib/browser-device";
import { base64ToBytes } from "@/lib/crypto";
import { hpkeSeal, wrapContext } from "@/lib/hpke";
import { addPhoneLink, linkProof, newLinkSecret, typedLinkSecret } from "@/lib/link";
import { PHONES_CHANGED } from "@/lib/phones";
import { useSession } from "@/lib/session";

const toB64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));

/**
 * "Add another phone": a QR code the new phone scans. The phone joins with the pairing code and
 * proves it saw this QR code (lib/link.ts); this browser then gives it the vault key itself, so
 * nothing has to be confirmed on any other phone.
 */
function AddPhoneDialog({ onClose }: { onClose: () => void }) {
  const { me, vault } = useSession();
  const secret = useRef(newLinkSecret());
  const handled = useRef(new Set<string>());
  const [code, setCode] = useState<string | null>(null);
  // Every 8-digit code shown so far: a phone may have typed an earlier one.
  const [shortCodes, setShortCodes] = useState<string[]>([]);
  const typedSecrets = useRef(new Map<string, Uint8Array<ArrayBuffer>>());
  const [qr, setQr] = useState<string | null>(null);
  const [linked, setLinked] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const ready = vault.status === "ready";

  useEffect(() => {
    if (!ready) return;
    let stopped = false;
    const renew = () =>
      api.pairingCode().then(
        async (c) => {
          if (stopped) return;
          setCode(c.code);
          if (c.shortCode) {
            typedSecrets.current.set(c.shortCode, await typedLinkSecret(c.shortCode));
            setShortCodes((s) => [...s, c.shortCode!]);
          }
        },
        () => !stopped && setError("Could not create a code. Close this and try again."),
      );
    void renew();
    const timer = setInterval(renew, 9 * 60_000); // codes last 10 minutes
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [ready]);

  useEffect(() => {
    if (!code) return;
    QRCode.toDataURL(addPhoneLink(window.location.origin, code, secret.current), { margin: 1, width: 240, errorCorrectionLevel: "M" }).then(setQr, () =>
      setError("Could not draw the QR code."),
    );
  }, [code]);

  // Watch for the phone that scanned this QR code, check its proof and approve it.
  useEffect(() => {
    if (!ready || !me) return;
    let stopped = false;
    let busy = false;
    const timer = setInterval(async () => {
      if (busy) return;
      busy = true;
      try {
        const device = await ensureDevice(me.userId);
        for (const p of await api.browserPending(device.deviceId)) {
          if (stopped || handled.current.has(p.id) || !p.linkProof) continue;
          // Another QR code's phone, or keys the server swapped: never approve those.
          const secrets = [secret.current, ...typedSecrets.current.values()];
          let matches = false;
          for (const s of secrets) if ((await linkProof(s, p.encPublicKey, p.signPublicKey)) === p.linkProof) matches = true;
          if (!matches) continue;
          handled.current.add(p.id);
          const raw = await rawVaultKeys(device);
          try {
            const keys = [];
            for (const [version, vk] of raw) {
              keys.push({ keyVersion: version, wrappedKey: toB64(await hpkeSeal(base64ToBytes(p.encPublicKey), vk, wrapContext(p.id, version))) });
            }
            await api.browserApprove(device.deviceId, p.id, keys);
          } finally {
            raw.forEach((vk) => vk.fill(0));
          }
          setLinked((l) => [...l, p.name]);
          window.dispatchEvent(new Event(PHONES_CHANGED));
        }
      } catch (e) {
        if (e instanceof ApiError && e.code === "not_trusted") {
          setError("This browser was opened with the recovery key, so it cannot add phones. Sign out and sign in again by scanning with your phone.");
          stopped = true;
          clearInterval(timer);
        }
        /* otherwise keep trying: brief network trouble is fine */
      } finally {
        busy = false;
      }
    }, 2000);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [ready, me]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-black/50 p-4" role="dialog" aria-modal="true" aria-labelledby="add-phone-title">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle id="add-phone-title">Add another phone</CardTitle>
          <CardDescription>Its SMS then show here too, live, with their own tab.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {!ready ? (
            <Alert tone="error">Open your messages first, then add a phone.</Alert>
          ) : (
            <>
              <ol className="list-decimal space-y-1 pl-5 text-sm">
                <li>
                  On the new phone, install SMS Bridge (<a className="underline" href="/download/sms-bridge.apk">download</a>).
                </li>
                <li>
                  Open it and tap <strong>Scan QR code</strong>, or <strong>Type code</strong>.
                </li>
                <li>Scan this QR code, or type the 8 digits under it.</li>
              </ol>
              <p className="text-xs text-muted-foreground">
                If that phone is already linked somewhere else, the app asks to move it here. Its messages stay on the phone.
              </p>
              <div className="flex justify-center">
                {qr ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={qr} alt="QR code to add a phone to SMS Bridge" width={240} height={240} className="rounded-md border bg-white p-1" />
                ) : (
                  <div className="h-[240px] w-[240px] animate-pulse rounded-md bg-muted" />
                )}
              </div>
              {shortCodes.length > 0 && (
                <div className="text-center">
                  <p className="text-xs text-muted-foreground">Can&apos;t scan? Type this code in the app:</p>
                  <p className="font-mono text-3xl font-semibold tracking-widest">
                    {shortCodes.at(-1)!.slice(0, 4)} {shortCodes.at(-1)!.slice(4)}
                  </p>
                </div>
              )}
              {linked.map((name) => (
                <Alert key={name} tone="info">
                  ✓ {name} is added. Its new messages appear here within seconds.
                </Alert>
              ))}
              {linked.length === 0 && <p className="text-center text-xs text-muted-foreground">Waiting for the phone to scan…</p>}
            </>
          )}
          {error && <Alert tone="error">{error}</Alert>}
          <Button className="w-full" variant={linked.length ? "default" : "outline"} onClick={onClose}>
            {linked.length ? "Done" : "Close"}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

export function AddPhoneButton({ className, size }: { className?: string; size?: "sm" | "default" }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button className={className} size={size} onClick={() => setOpen(true)}>
        + Add another phone
      </Button>
      {open && <AddPhoneDialog onClose={() => setOpen(false)} />}
    </>
  );
}
