import { useRouter } from "next/router";
import { Alert } from "@/components/ui/alert";
import { PublicPage, SUPPORT_EMAIL } from "@/components/public-page";

export default function DeleteAccountHelp() {
  const { query } = useRouter();
  return (
    <PublicPage title="Delete your SMS Bridge account">
      {query.done === "1" && <Alert>Your account and everything stored with it was deleted from our server.</Alert>}

      <h2>On the website (deletes everything)</h2>
      <ol className="list-decimal space-y-1 pl-5">
        <li>Open <a href="/">sms.techistbd.com</a> on a computer and sign in: scan the QR code with your phone, or use your username and password.</li>
        <li>Open <strong>Settings</strong> from the menu.</li>
        <li>Tap <strong>Delete my account</strong>, enter your password if you have one, and confirm.</li>
      </ol>

      <h2>In the app (deletes one phone&apos;s data)</h2>
      <p>
        On the phone, open SMS Bridge and tap <strong>Unlink this phone</strong> on the Home screen. That phone leaves your account and its
        messages are deleted from the server. Then you can uninstall the app.
      </p>

      <h2>Can&apos;t sign in?</h2>
      <p>
        Email <a href={`mailto:${SUPPORT_EMAIL}?subject=Delete%20my%20account`}>{SUPPORT_EMAIL}</a> with your username. We may ask you to
        prove the account is yours, then delete it within 7 days.
      </p>

      <h2>What is deleted</h2>
      <ul>
        <li>Your account, username and password check value.</li>
        <li>Every linked phone and browser, and their keys.</li>
        <li>All encrypted messages stored on the server.</li>
        <li>Your link to the security log. Its entries lose your account and IP address, and the rest is removed after 90 days.</li>
      </ul>
      <p>
        Encrypted server backups are overwritten within 14 days. The SMS on your phone are never touched: they stay in your phone&apos;s
        messaging app.
      </p>
    </PublicPage>
  );
}
