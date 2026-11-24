import { PublicPage, SUPPORT_EMAIL } from "@/components/public-page";

export default function Terms() {
  return (
    <PublicPage title="Terms of use" updated="24 November 2026">
      <p>By using SMS Bridge (the Android app and the website sms.techistbd.com) you agree to these terms.</p>
      <ul>
        <li>Use it only for your own phone, or a phone whose owner has agreed. Do not use it to read someone else&apos;s messages.</li>
        <li>Do not misuse the service: no attacks, no attempts to break into other accounts, no automated abuse.</li>
        <li>SMS Bridge is free and provided &quot;as is&quot;. We try to keep it running and safe, but we cannot promise it is always available or free of errors. Keep your own copies of important messages.</li>
        <li>We may suspend accounts that break these terms, and we may change or end the service. If we end it, we will say so on this website first.</li>
        <li>Your data is handled as described in the <a href="/privacy/">privacy policy</a>. You can <a href="/delete-account/">delete your account</a> at any time.</li>
      </ul>
      <p>
        Questions: <a href={`mailto:${SUPPORT_EMAIL}`}>{SUPPORT_EMAIL}</a>.
      </p>
    </PublicPage>
  );
}
