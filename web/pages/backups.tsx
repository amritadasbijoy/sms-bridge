import { Shell } from "@/components/shell";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export default function Backups() {
  return (
    <Shell title="Backups">
      <div className="max-w-2xl space-y-6">
        <Card>
          <CardHeader>
            <CardTitle>Backups stay with you</CardTitle>
            <CardDescription>
              Backup files are created on the phone and saved where you choose (phone storage, a USB drive, or a cloud folder
              you pick). They are never uploaded to this server.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <p>
              <span className="font-medium">To make a backup:</span> in the SMS Bridge app open Backup → Create backup. Choose
              Encrypted (.sbbackup, protected with a passphrase) or a plain .zip that other SMS apps can import.
            </p>
            <p>
              <span className="font-medium">To restore:</span> open Backup → Restore on the phone and pick the file. You will see
              what will be added before anything changes; existing messages are never overwritten.
            </p>
            <p className="text-muted-foreground">
              The synced archive shown in this dashboard is separate from backups. Keep at least one backup: sync is not a
              backup if a phone deletes messages.
            </p>
          </CardContent>
        </Card>
      </div>
    </Shell>
  );
}
