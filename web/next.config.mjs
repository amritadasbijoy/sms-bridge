/**
 * Static export: the dashboard is plain files served next to the API (same origin), so the
 * session cookie stays SameSite=Strict and no Node server runs in production.
 * The Pages Router is used because its export has no inline executable scripts, which lets the
 * server send a strict `script-src 'self'` Content-Security-Policy.
 */
import path from "node:path";
import { fileURLToPath } from "node:url";

const isDev = process.env.NODE_ENV === "development";

/** @type {import('next').NextConfig} */
const config = {
  reactStrictMode: true,
  poweredByHeader: false,
  outputFileTracingRoot: path.dirname(fileURLToPath(import.meta.url)),
  trailingSlash: true,
  images: { unoptimized: true },
  ...(isDev
    ? {
        // `npm run dev`: forward the API to the local Compose stack.
        async rewrites() {
          return [{ source: "/api/:path*", destination: "http://127.0.0.1:8080/api/:path*" }];
        },
      }
    : { output: "export" }),
};

export default config;
