import QRCode from "qrcode";
import { useEffect, useRef, useState } from "react";
import { api, type LoginStatus } from "@/lib/api";
import { generateKeys, saveApprovedDevice } from "@/lib/browser-device";
import { confirmNumber } from "@/lib/confirm";
import { deviceFingerprint } from "@/lib/pair";

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
export function PhoneSignIn({ username, onApproved, onFailed }: { username?: string; onApproved: () => void; onFailed: (message: string) => void }) {
  const [qr, setQr] = useState<string | null>(null);
  const [code, setCode] = useState<string | null>(null);
  const [number, setNumber] = useState<number | null>(null);
  const [attempt, setAttempt] = useState(0);

  // Latest callbacks, so a parent re-render does not restart the sign-in request (and its code).
  const approvedRef = useRef(onApproved);
  const failedRef = useRef(onFailed);
  approvedRef.current = onApproved;
  failedRef.current = onFailed;

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
        setCode(req.code ?? null);
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
          approvedRef.current();
        } else if (st.status === "declined") {
          clearInterval(timer);
          failedRef.current("Not confirmed: a different number was tapped on the phone.");
        } else if (st.status === "expired" || Date.now() - started > 5 * 60_000) {
          clearInterval(timer);
          if (username) failedRef.current("The request expired. Try again.");
          else setAttempt((a) => a + 1); // a fresh QR code
        } else if (st.challenge) {
          setNumber(await confirmNumber(st.challenge, keys.encPublic, keys.signPublic));
        }
      }, 2000);
    })().catch(() => !stopped && failedRef.current("Could not start signing in. Reload the page."));
    return () => {
      stopped = true;
      if (timer) clearInterval(timer);
    };
  }, [username, attempt]);

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
      {code && (
        <div className="text-center">
          <p className="text-xs text-muted-foreground">Can&apos;t scan? Type this code in the app:</p>
          <p className="font-mono text-3xl font-semibold tracking-widest" aria-label={`Code ${code.split("").join(" ")}`}>
            {code.slice(0, 4)} {code.slice(4)}
          </p>
        </div>
      )}
    </div>
  );
}

