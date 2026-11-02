import { useMemo } from "react";
import { MessageList } from "@/components/message-list";
import { Shell } from "@/components/shell";
import { VaultGate } from "@/components/vault-gate";
import { usePhones } from "@/lib/phones";
import { useSession } from "@/lib/session";

export default function Inbox() {
  const { store } = useSession();
  const { filter } = usePhones();
  const inbox = useMemo(() => filter(store.messages).filter((m) => m.box === "INBOX"), [store, filter]);
  return (
    <Shell title="Inbox" phoneTabs>
      <VaultGate>
        <MessageList messages={inbox} empty="No received messages." />
      </VaultGate>
    </Shell>
  );
}
