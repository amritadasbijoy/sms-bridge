import Head from "next/head";
import Link from "next/link";
import { useRouter } from "next/router";
import { useEffect, type ReactNode } from "react";
import { AddPhoneButton } from "@/components/add-phone";
import { ConnectRequests } from "@/components/connect-requests";
import { MoveWatcher } from "@/components/move-watcher";
import { NewMessageToast, PhoneTabs } from "@/components/phone-tabs";
import { Button } from "@/components/ui/button";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

const NAV = [
  { href: "/dashboard/", label: "Messages" },
  { href: "/devices/", label: "Phones & browsers" },
  { href: "/settings/", label: "Settings" },
];

/** Signed-in layout. Sends signed-out visitors to /login. [phoneTabs]: the page lists messages, so show the phone tabs. */
export function Shell({ title, children, phoneTabs }: { title: string; children: ReactNode; phoneTabs?: boolean }) {
  const { me, vault, signOut } = useSession();
  const router = useRouter();

  useEffect(() => {
    if (me === null) void router.replace("/login/");
  }, [me, router]);

  if (!me) {
    return <div className="p-8 text-sm text-muted-foreground">Loading…</div>;
  }
  return (
    <div className="min-h-screen">
      <Head>
        <title>{`${title} · SMS Bridge`}</title>
      </Head>
      <header className="border-b">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <Link href="/dashboard/" className="font-semibold">
            SMS Bridge
          </Link>
          <div className="flex items-center gap-2 text-sm">
            {vault.status === "ready" && <AddPhoneButton size="sm" className="hidden sm:inline-flex" />}
            <span className="hidden max-w-[12rem] truncate text-muted-foreground sm:inline" title="Signed in as">
              👤 {me.username}
            </span>
            <Button variant="ghost" size="sm" onClick={() => void signOut().then(() => router.replace("/login/"))}>
              Sign out
            </Button>
          </div>
        </div>
        <nav className="mx-auto flex max-w-6xl gap-1 overflow-x-auto px-4 pb-2" aria-label="Main">
          {NAV.map((n) => (
            <Link
              key={n.href}
              href={n.href}
              aria-current={router.pathname === n.href.replace(/\/$/, "") ? "page" : undefined}
              className={cn(
                "whitespace-nowrap rounded-md px-3 py-1.5 text-sm text-muted-foreground hover:bg-accent hover:text-accent-foreground",
                router.pathname === n.href.replace(/\/$/, "") && "bg-accent font-medium text-accent-foreground",
              )}
            >
              {n.label}
            </Link>
          ))}
          <span className="ml-auto self-center whitespace-nowrap pl-2 text-sm text-muted-foreground sm:hidden">👤 {me.username}</span>
        </nav>
      </header>
      <ConnectRequests />
      <NewMessageToast />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-5 text-xl font-semibold">{title}</h1>
        <MoveWatcher />
        {phoneTabs && vault.status === "ready" && <PhoneTabs />}
        {children}
      </main>
    </div>
  );
}
