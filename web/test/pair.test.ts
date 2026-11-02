import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { deviceFingerprint, pairLink } from "@/lib/pair";

const v = JSON.parse(readFileSync(path.join(__dirname, "../../testdata/hpke-vectors-v1.json"), "utf8"));

describe("pairing QR link", () => {
  it("computes the same fingerprint as the phone (android PairLinkTest)", async () => {
    expect(await deviceFingerprint(v.encPublic, v.signPublic)).toBe("niUOofQkoq17tqL8R27m0TeNCJbZO6J3NG9uSdTO2lQ");
  });

  it("builds the link the phone parses", () => {
    const link = pairLink({ server: "https://sms.example.org", code: "AB12C-DE34F", deviceId: "7cc2df38-882c-4fd5-9e12-36fdbc308132", fingerprint: "abc" });
    expect(link).toBe(
      "smsbridge://pair?v=1&s=https%3A%2F%2Fsms.example.org&c=AB12C-DE34F&d=7cc2df38-882c-4fd5-9e12-36fdbc308132&f=abc",
    );
    // The browser part is only added when both halves are known.
    expect(pairLink({ server: "https://x", code: "C", deviceId: "d" })).toBe("smsbridge://pair?v=1&s=https%3A%2F%2Fx&c=C");
  });
});
