import { PublicPage, SUPPORT_EMAIL } from "@/components/public-page";

export default function Privacy() {
  return (
    <PublicPage title="Privacy policy" updated="24 November 2026">
      <p>
        SMS Bridge shows the SMS from your Android phone in a browser you choose. It is made by Amrita Techworks. This page explains
        what the app and the website at sms.techistbd.com collect, why, and how to delete it. Questions:{" "}
        <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>.
      </p>

      <h2>The short version</h2>
      <ul>
        <li>Your messages are encrypted on your phone before they leave it. Our server stores them but cannot read them.</li>
        <li>Your password never reaches our server. Your browser turns it into keys first.</li>
        <li>No ads, no trackers, no analytics. We do not sell or share your data.</li>
        <li>You can remove a phone or delete your whole account at any time, and the server copies go with it.</li>
      </ul>

      <h2>What the app reads on your phone</h2>
      <ul>
        <li>
          <strong>SMS messages</strong> (sender, text, time), only after you link a computer and agree to share. You choose the range:
          new messages only, or the last 7 or 30 days.
        </li>
        <li>
          <strong>SIM information</strong> (SIM slot and carrier name), only if you allow the Phone permission, so messages can be labelled
          &quot;SIM 1&quot; or &quot;SIM 2&quot;. This is optional.
        </li>
        <li>
          <strong>Notifications</strong>, only to show that the app is connected and to tell you about new browsers.
        </li>
      </ul>
      <p>Backups and phone-to-phone transfers made in the app stay on your devices. They are not uploaded to our server.</p>

      <h2>What our server stores</h2>
      <ul>
        <li><strong>Encrypted messages</strong> from the phones you linked. Only your phones and the browsers you approved hold the keys.</li>
        <li><strong>Your account:</strong> a username (one is made up for you if you never pick one) and, if you set a password, a check value derived from it. Not the password itself.</li>
        <li><strong>Your devices:</strong> the names you give them, the phone model, when they were last online, and their public keys.</li>
        <li><strong>A security log</strong> of sign-ins, linking and unlinking, with the IP address it came from. It helps us stop abuse.</li>
      </ul>

      <h2>How long we keep it</h2>
      <ul>
        <li>Messages: until you remove the phone, delete your account, or they get older than the range you chose in the app.</li>
        <li>Accounts made just by scanning that are unused for 7 days and have no phone left: deleted automatically.</li>
        <li>Security log: 90 days.</li>
        <li>Encrypted server backups: 14 days, then they are overwritten.</li>
      </ul>

      <h2>Who else sees it</h2>
      <p>
        Nobody. The server is rented from a hosting company, which stores the encrypted data but has no access to its contents. We do not
        use third-party services that receive your data. We only share information if the law requires it, and even then your messages
        stay unreadable to us.
      </p>

      <h2>Your choices</h2>
      <ul>
        <li><strong>Stop sharing</strong> with a browser from the app at any time.</li>
        <li><strong>Remove a phone</strong> on the website, or <strong>Unlink this phone</strong> in the app: its messages are deleted from the server.</li>
        <li><strong>Delete your account</strong>: see <a href="/delete-account/">how to delete your account</a>.</li>
      </ul>

      <h2>Security</h2>
      <p>
        Everything travels over HTTPS. Messages are end-to-end encrypted (X25519 and AES-256-GCM). Passwords are stretched in your browser
        and hashed again on the server with Argon2id.
      </p>

      <h2>Children</h2>
      <p>SMS Bridge is not meant for children and we do not knowingly collect their data.</p>

      <h2>Changes</h2>
      <p>If this policy changes, the new version is posted here with a new date.</p>
    </PublicPage>
  );
}
