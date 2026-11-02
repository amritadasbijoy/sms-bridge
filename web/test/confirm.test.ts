import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { confirmNumber } from "@/lib/confirm";

const v = JSON.parse(readFileSync(path.join(__dirname, "../../testdata/hpke-vectors-v1.json"), "utf8"));

describe("confirm number", () => {
  it("matches the phone for the shared vector (android ConfirmNumbersTest)", async () => {
    expect(await confirmNumber("AAECAwQFBgcICQoLDA0ODw==", v.encPublic, v.signPublic)).toBe(92);
  });

  it("changes with the salt", async () => {
    expect(await confirmNumber("AQECAwQFBgcICQoLDA0ODw==", v.encPublic, v.signPublic)).not.toBe(92);
  });
});
