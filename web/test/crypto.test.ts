import { describe, expect, it } from "vitest";
import { parseRecoveryKey } from "@/lib/crypto";

describe("parseRecoveryKey", () => {
  it("accepts case, spaces, dashes and look-alike letters", () => {
    const a = parseRecoveryKey("Z2S6-VFD6-SQYY-W2ZN-JAY6-DSZE-3R")!;
    expect(a).toHaveLength(16);
    expect(parseRecoveryKey("z2s6 vfd6 sqyy w2zn jay6 dsze 3r")).toEqual(a);
    expect(parseRecoveryKey("0000-0000-0000-0000-0000-0000-00")).toEqual(parseRecoveryKey("OOOO-oooo-0000-0000-0000-0000-00"));
    expect(parseRecoveryKey("1111-1111-1111-1111-1111-1111-11")).toEqual(parseRecoveryKey("IIII-llll-LLLL-1111-1111-1111-11"));
  });

  it("rejects malformed input", () => {
    expect(parseRecoveryKey("")).toBeNull();
    expect(parseRecoveryKey("ABCD")).toBeNull();
    expect(parseRecoveryKey("UUUU-UUUU-UUUU-UUUU-UUUU-UUUU-UU")).toBeNull(); // U is not Crockford
    expect(parseRecoveryKey("Z2S6-VFD6-SQYY-W2ZN-JAY6-DSZE-3RX")).toBeNull();
  });
});
