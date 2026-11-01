import { useRouter } from "next/router";
import { useEffect } from "react";
import { useSession } from "@/lib/session";

export default function Index() {
  const { me } = useSession();
  const router = useRouter();
  useEffect(() => {
    if (me === null) void router.replace("/login/");
    else if (me) void router.replace("/dashboard/");
  }, [me, router]);
  return <div className="p-8 text-sm text-muted-foreground">Loading…</div>;
}
