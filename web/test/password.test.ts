import { describe, expect, it } from "vitest";
import { hpkeOpen, hpkeSeal, wrapContext } from "@/lib/hpke";
import { otpOf, simLabel } from "@/lib/messages";
import { loginSecret, passwordKey } from "@/lib/password";

describe("password keys", () => {
  it("derive the same key from the same password and salt, and open what was sealed for it", async () => {
    const salt = "AAECAwQFBgcICQoLDA0ODw==";
    const a = await passwordKey("correct horse battery", salt, 1000);
    const b = await passwordKey("correct horse battery", salt, 1000);
    expect(a.publicB64).toBe(b.publicB64);
    expect(a.verifierB64).toBe(b.verifierB64);
    expect((await passwordKey("wrong horse battery", salt, 1000)).publicB64).not.toBe(a.publicB64);
    const vk = crypto.getRandomValues(new Uint8Array(32));
    const sealed = await hpkeSeal(a.publicKey, vk, wrapContext("password", 1));
    expect(await hpkeOpen(b.privateKey, b.publicKey, sealed, wrapContext("password", 1))).toEqual(vk);
  });

  it("send a login secret that depends on the username, not the password itself", async () => {
    const s = await loginSecret(" Amrita ", "correct horse battery");
    expect(s).toBe(await loginSecret("amrita", "correct horse battery"));
    expect(s).not.toBe(await loginSecret("someone", "correct horse battery"));
    expect(s).not.toContain("horse");
    expect(s).toHaveLength(44);
  }, 20_000);
});

describe("message helpers", () => {
  it("find one-time codes only in code messages", () => {
    expect(otpOf("Your verification code is 482913. Do not share it.")).toBe("482913");
    expect(otpOf("bKash: Your OTP is 1234, valid 2 min")).toBe("1234");
    expect(otpOf("আপনার কোড 55821")).toBe("55821");
    expect(otpOf("Meet at 1930 near gate 4")).toBeNull();
    expect(otpOf("Your balance is 12,500.50 Tk")).toBeNull();
    expect(otpOf("(IVACBD) For security, type the following sequence when prompted Seven_Zero_Three_Eight_Seven_Five .")).toBe("703875");
    expect(otpOf("Your PIN: one-two three four")).toBe("1234");
    expect(otpOf("I have one or two ideas")).toBeNull();
  });

  it("label SIMs", () => {
    expect(simLabel({ simSlot: 2, carrier: "Robi" })).toBe("SIM 2 · Robi");
    expect(simLabel({ simSlot: 1, carrier: null })).toBe("SIM 1");
    expect(simLabel({ simSlot: null, carrier: "GP" })).toBeNull();
  });
});
