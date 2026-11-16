import { useEffect, useState } from "react";
import { Alert } from "@/components/ui/alert";
import { pendingMove, resumeMove } from "@/lib/account";
import { useSession } from "@/lib/session";

/** After "I already have an account": waits for the scanned phone to arrive and approves it. */
export function MoveWatcher() {
  const { me, vault } = useSession();
  const [state, setState] = useState<"idle" | "waiting" | "done" | "failed">("idle");

  useEffect(() => {
    if (!me || vault.status !== "ready" || state !== "idle" || pendingMove()?.userId !== me.userId) return;
    setState("waiting");
    // Its recent SMS then arrive live, like any new message.
    void resumeMove(me.userId).then((ok) => setState(ok ? "done" : "failed"));
  }, [me, vault.status, state]);

  if (state === "waiting") return <Alert tone="info" className="mb-4">Moving your phone into this account… keep this page open, it takes a few seconds.</Alert>;
  if (state === "done") return <Alert tone="info" className="mb-4">✓ Your phone is now in this account. Its recent messages appear in a moment.</Alert>;
  if (state === "failed") return <Alert tone="warning" className="mb-4">The phone did not arrive. Make sure it has SMS Bridge 0.10.4 or newer and is online, then use + Add another phone.</Alert>;
  return null;
}
