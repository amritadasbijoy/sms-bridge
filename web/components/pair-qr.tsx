import QRCode from "qrcode";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { pairLink } from "@/lib/pair";

/**
 * A pairing QR code for the phone to scan. With [withCode], it carries a fresh one-time pairing code
 * (renewed before it expires) so an unconnected phone can connect; with a device id and fingerprint,
 * the phone also approves this browser.
 */
export function PairQr({ withCode, deviceId, fingerprint }: { withCode: boolean; deviceId?: string; fingerprint?: string }) {
  const [code, setCode] = useState<string | null>(null);
  const [image, setImage] = useState<string | null>(null);
  const [link, setLink] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!withCode) return;
    let stopped = false;
    const renew = () =>
      api.pairingCode().then(
        (c) => !stopped && setCode(c.code),
        () => !stopped && setError("Could not create a pairing code. Reload the page."),
      );
    void renew();
    const timer = setInterval(renew, 9 * 60_000); // codes last 10 minutes
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [withCode]);

  useEffect(() => {
    if (withCode && !code) return;
    const text = pairLink({ server: window.location.origin, code: withCode ? code : null, deviceId, fingerprint });
    setLink(text);
    QRCode.toDataURL(text, { margin: 1, width: 240, errorCorrectionLevel: "M" }).then(setImage, () => setError("Could not draw the QR code."));
  }, [withCode, code, deviceId, fingerprint]);

  if (error) return <p className="text-sm text-destructive">{error}</p>;
  return (
    <div className="flex flex-col items-center gap-2">
      {image ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={image} alt="Pairing QR code for the SMS Bridge app" width={240} height={240} className="rounded-md border bg-white p-1" />
      ) : (
        <div className="h-[240px] w-[240px] animate-pulse rounded-md bg-muted" />
      )}
      {link && (
        <details className="w-full text-xs text-muted-foreground">
          <summary className="cursor-pointer">Open on this phone or copy the link</summary>
          <p className="mt-1">
            On the phone itself, <a className="underline" href={link}>tap here to open SMS Bridge</a>. Otherwise copy this
            link and paste it in the app (Sync → Enter the address and code instead → address field):
          </p>
          <p className="mt-1 break-all font-mono">{link}</p>
        </details>
      )}
    </div>
  );
}
