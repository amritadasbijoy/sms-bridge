import { useMemo } from "react";
import { MessageList } from "@/components/message-list";
import { Shell } from "@/components/shell";
import { VaultGate } from "@/components/vault-gate";
import { useSession } from "@/lib/session";

export default function Inbox() {
  const { store } = useSession();
  const inbox = useMemo(() => store.messages.filter((m) => m.box === "INBOX"), [store]);
  return (
    <Shell title="Inbox">
      <VaultGate>
        <MessageList messages={inbox} empty="No received messages." />
      </VaultGate>
    </Shell>
  );
}
