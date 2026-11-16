import { useRouter } from "next/router";
import { useEffect } from "react";

/** Old address: everything is on the one Messages page now. */
export default function Moved() {
  const router = useRouter();
  useEffect(() => void router.replace("/dashboard/"), [router]);
  return null;
}
