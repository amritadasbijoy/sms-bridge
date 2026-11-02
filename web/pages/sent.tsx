import { useMemo } from "react";
import { MessageList } from "@/components/message-list";
import { Shell } from "@/components/shell";
import { VaultGate } from "@/components/vault-gate";
import { usePhones } from "@/lib/phones";
import { useSession } from "@/lib/session";

const OUTGOING = new Set(["SENT", "OUTBOX", "FAILED", "QUEUED"]);

export default function Sent() {
  const { store } = useSession();
  const { filter } = usePhones();
  const sent = useMemo(() => filter(store.messages).filter((m) => OUTGOING.has(m.box)), [store, filter]);
  return (
    <Shell title="Sent" phoneTabs>
      <VaultGate>
        <MessageList messages={sent} empty="No sent messages." />
      </VaultGate>
    </Shell>
  );
}
