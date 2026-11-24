import Head from "next/head";
import type { ReactNode } from "react";

export const SUPPORT_EMAIL = "support@techistbd.com";

/** Layout for pages anyone can open without signing in (privacy, terms, account deletion). */
export function PublicPage({ title, updated, children }: { title: string; updated?: string; children: ReactNode }) {
  return (
    <div className="min-h-screen px-4 py-8">
      <Head>
        <title>{`${title} · SMS Bridge`}</title>
      </Head>
      <div className="mx-auto w-full max-w-2xl">
        <a href="/" className="text-lg font-semibold">SMS Bridge</a>
        <article className="mt-6 space-y-4 rounded-lg border bg-card p-6 text-sm leading-relaxed shadow-sm sm:p-8 [&_h2]:pt-2 [&_h2]:text-base [&_h2]:font-semibold [&_ul]:list-disc [&_ul]:space-y-1 [&_ul]:pl-5 [&_a]:underline">
          <h1 className="text-2xl font-semibold">{title}</h1>
          {updated && <p className="text-muted-foreground">Last updated {updated}</p>}
          {children}
        </article>
        <PublicFooter />
      </div>
    </div>
  );
}

export function PublicFooter() {
  return (
    <nav className="mt-4 flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
      <a href="/privacy/" className="hover:underline">Privacy</a>
      <a href="/terms/" className="hover:underline">Terms</a>
      <a href="/delete-account/" className="hover:underline">Delete account</a>
      <a href={`mailto:${SUPPORT_EMAIL}`} className="hover:underline">{SUPPORT_EMAIL}</a>
    </nav>
  );
}
