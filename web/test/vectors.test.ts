import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { base64ToBytes, decryptEvent, parseRecoveryKey, safetyCode, unlockVaultKey } from "@/lib/crypto";
import { buildMessages, type DecryptedEvent, type EventPayload } from "@/lib/messages";

// Produced by the phone's real CloudCrypto (android CrossLanguageVectorsTest). Synthetic data.
interface Vectors {
  recoveryKey: string;
  recoveryKeys: { keyVersion: number; wrappedKey: string }[];
  events: {
    eventId: string; deviceId: string; deviceSequence: number; serverSequence: number; keyVersion: number;
    ciphertext: string; valid: boolean; plaintext: string | null;
  }[];
  safetyCode: { encPublic: string; signPublic: string; code: string };
}
const vectors: Vectors = JSON.parse(readFileSync(path.join(__dirname, "../../testdata/sync-vectors-v1.json"), "utf8"));

async function vaultKey() {
  const rk = parseRecoveryKey(vectors.recoveryKey)!;
  const w = vectors.recoveryKeys[0]!;
  return unlockVaultKey(base64ToBytes(w.wrappedKey), rk, w.keyVersion);
}

describe("Android-produced vectors", () => {
  it("unlocks the vault key as a non-extractable key", async () => {
    const key = await vaultKey();
    expect(key.extractable).toBe(false);
    expect(key.usages).toEqual(["decrypt"]);
  });

  it("rejects a wrong recovery key", async () => {
    const wrong = parseRecoveryKey("0000-0000-0000-0000-0000-0000-00")!;
    const w = vectors.recoveryKeys[0]!;
    await expect(unlockVaultKey(base64ToBytes(w.wrappedKey), wrong, w.keyVersion)).rejects.toThrow();
    // The right key under the wrong version label fails too (AAD binds the version).
    await expect(unlockVaultKey(base64ToBytes(w.wrappedKey), parseRecoveryKey(vectors.recoveryKey)!, 2)).rejects.toThrow();
  });

  it("decrypts every valid event exactly and rejects the tampered one", async () => {
    const key = await vaultKey();
    const dec = new TextDecoder();
    for (const e of vectors.events) {
      const attempt = decryptEvent(key, base64ToBytes(e.ciphertext), e.eventId, e.deviceId, e.deviceSequence, e.keyVersion);
      if (e.valid) expect(dec.decode(await attempt)).toBe(e.plaintext);
      else await expect(attempt).rejects.toThrow();
    }
  });

  it("binds each event to its id, device and sequence", async () => {
    const key = await vaultKey();
    const e = vectors.events[0]!;
    const ct = base64ToBytes(e.ciphertext);
    await expect(decryptEvent(key, ct, vectors.events[1]!.eventId, e.deviceId, e.deviceSequence, 1)).rejects.toThrow();
    await expect(decryptEvent(key, ct, e.eventId, vectors.events[3]!.deviceId, e.deviceSequence, 1)).rejects.toThrow();
    await expect(decryptEvent(key, ct, e.eventId, e.deviceId, e.deviceSequence + 1, 1)).rejects.toThrow();
  });

  it("computes the same safety code as the phone", async () => {
    const s = vectors.safetyCode;
    expect(await safetyCode(base64ToBytes(s.encPublic), base64ToBytes(s.signPublic))).toBe(s.code);
  });

  it("builds one message per SMS across phones, with the newest state", async () => {
    const key = await vaultKey();
    const dec = new TextDecoder();
    const events: DecryptedEvent[] = [];
    for (const e of vectors.events.filter((x) => x.valid)) {
      const plain = await decryptEvent(key, base64ToBytes(e.ciphertext), e.eventId, e.deviceId, e.deviceSequence, e.keyVersion);
      events.push({ eventId: e.eventId, deviceId: e.deviceId, serverSequence: e.serverSequence, payload: JSON.parse(dec.decode(plain)) as EventPayload });
    }
    const { messages } = buildMessages(events);
    expect(messages).toHaveLength(2);
    const hello = messages.find((m) => m.box === "INBOX")!;
    expect(hello.body).toBe('Synthetic "hello" from A\nline two');
    expect(hello.read).toBe(true); // status-changed event wins over the first upload
    expect(hello.deviceIds).toHaveLength(2); // same SMS from phone B ("5555550101") merged
  });
});
