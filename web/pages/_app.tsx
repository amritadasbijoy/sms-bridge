import type { AppProps } from "next/app";
import { PhonesProvider } from "@/lib/phones";
import { SessionProvider } from "@/lib/session";
import "@/styles/globals.css";

export default function App({ Component, pageProps }: AppProps) {
  return (
    <SessionProvider>
      <PhonesProvider>
        <Component {...pageProps} />
      </PhonesProvider>
    </SessionProvider>
  );
}
