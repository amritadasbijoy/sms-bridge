import { describe, expect, it } from "vitest";
import { addressKey, buildMessages, conversations, type DecryptedEvent, type EventPayload } from "@/lib/messages";

let seq = 0;
function ev(deviceId: string, p: Partial<EventPayload>): DecryptedEvent {
  seq++;
  return {
    eventId: `e${seq}`,
    deviceId,
    serverSequence: seq,
    payload: { v: 1, type: "SMS_RECEIVED", messageId: `m${seq}`, address: "+15555550101", body: "Synthetic", date: 1000, box: "INBOX", read: false, ...p },
  };
}

describe("addressKey", () => {
  it("matches AddressKey.kt", () => {
    expect(addressKey("+1 (555) 555-0101")).toBe("5555550101");
    expect(addressKey("05555550101")).toBe("5555550101");
    expect(addressKey("ACME-Bank")).toBe("acme-bank");
    expect(addressKey("  ")).toBe("");
    expect(addressKey(null)).toBe("");
  });
});

describe("buildMessages", () => {
  it("hides messages a phone reported deleted and counts them", () => {
    const a = ev("A", { messageId: "x" });
    const del = ev("A", { messageId: "x", type: "SMS_DELETED" });
    const store = buildMessages([a, del, ev("A", { body: "kept", date: 2 })]);
    expect(store.messages.map((m) => m.body)).toEqual(["kept"]);
    expect(store.deletedOnPhone).toBe(1);
  });

  it("does not merge different messages that share a timestamp", () => {
    const store = buildMessages([ev("A", { body: "one" }), ev("B", { body: "two" }), ev("A", { body: "one", box: "SENT" })]);
    expect(store.messages).toHaveLength(3);
  });

  it("skips non-message and incomplete events", () => {
    const store = buildMessages([ev("A", { type: "DEVICE_REGISTERED" }), ev("A", { messageId: null }), ev("A", { body: undefined })]);
    expect(store.messages).toHaveLength(0);
    expect(store.skippedEvents).toBe(3);
  });

  it("groups conversations by address key, newest first, counting unread", () => {
    const store = buildMessages([
      ev("A", { address: "+15555550101", date: 1 }),
      ev("A", { address: "5555550101", date: 3, body: "b" }),
      ev("A", { address: "ACME", date: 2, read: true }),
    ]);
    const c = conversations(store.messages);
    expect(c.map((x) => x.addressKey)).toEqual(["5555550101", "acme"]);
    expect(c[0]!.messages.map((m) => m.date)).toEqual([1, 3]);
    expect(c[0]!.unread).toBe(2);
    expect(c[1]!.unread).toBe(0);
  });
});
