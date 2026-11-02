import Head from "next/head";
import Link from "next/link";
import { useRouter } from "next/router";
import { useEffect, type ReactNode } from "react";
import { ConnectRequests } from "@/components/connect-requests";
import { Button } from "@/components/ui/button";
import { useSession } from "@/lib/session";
import { cn } from "@/lib/utils";

const NAV = [
  { href: "/dashboard/", label: "Dashboard" },
  { href: "/inbox/", label: "Inbox" },
  { href: "/sent/", label: "Sent" },
  { href: "/conversations/", label: "Conversations" },
  { href: "/devices/", label: "Devices" },
  { href: "/sync/", label: "Sync" },
  { href: "/backups/", label: "Backups" },
  { href: "/settings/", label: "Settings" },
];

/** Signed-in layout. Sends signed-out visitors to /login. */
export function Shell({ title, children }: { title: string; children: ReactNode }) {
  const { me, vault, lock, signOut } = useSession();
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
            <span className="hidden text-muted-foreground sm:inline">{me.username}</span>
            {vault.status === "ready" && (
              <Button variant="outline" size="sm" onClick={lock} title="Forget the vault key in this tab">
                Lock
              </Button>
            )}
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
        </nav>
      </header>
      <ConnectRequests />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-5 text-xl font-semibold">{title}</h1>
        {children}
      </main>
    </div>
  );
}
