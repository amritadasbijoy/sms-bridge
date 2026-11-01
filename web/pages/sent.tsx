import { useMemo } from "react";
import { MessageList } from "@/components/message-list";
import { Shell } from "@/components/shell";
import { VaultGate } from "@/components/vault-gate";
import { useSession } from "@/lib/session";

const OUTGOING = new Set(["SENT", "OUTBOX", "FAILED", "QUEUED"]);

export default function Sent() {
  const { store } = useSession();
  const sent = useMemo(() => store.messages.filter((m) => OUTGOING.has(m.box)), [store]);
  return (
    <Shell title="Sent">
      <VaultGate>
        <MessageList messages={sent} empty="No sent messages." />
      </VaultGate>
    </Shell>
  );
}
