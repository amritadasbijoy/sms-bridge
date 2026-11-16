/**
 * Turns decrypted sync events into the message list the dashboard shows.
 *
 * Each event carries the message's full current state (CloudRepository.payloadFor), so the newest
 * event per (device, messageId) wins. The same SMS can arrive from two phones (e.g. after a Wi-Fi
 * transfer, or one phone re-uploading); those copies are merged by content, the way the phone's
 * own archive dedups (same box, address key, timestamp and body).
 */

export type Box = "INBOX" | "SENT" | "DRAFT" | "OUTBOX" | "FAILED" | "QUEUED" | "UNKNOWN";

/** Plaintext of one event, as encrypted by the phone. */
export interface EventPayload {
  v: number;
  type: string;
  messageId: string | null;
  address?: string | null;
  body?: string;
  date?: number;
  dateSent?: number;
  box?: Box;
  read?: boolean;
  seen?: boolean;
  status?: number | null;
  sub?: number | null;
  /** SIM slot (1, 2) and carrier name, from app 0.10.0. */
  slot?: number | null;
  carrier?: string | null;
  src?: string | null;
}

export interface DecryptedEvent {
  eventId: string;
  deviceId: string;
  serverSequence: number;
  payload: EventPayload;
}

export interface Message {
  /** Stable id for React keys: the first (device, messageId) seen for this content. */
  key: string;
  address: string;
  addressKey: string;
  body: string;
  date: number;
  dateSent: number;
  box: Box;
  read: boolean;
  status: number | null;
  /** Devices that uploaded this message. */
  deviceIds: string[];
  /** SIM slot (1, 2) the message came in on or went out from, when the phone reported it. */
  simSlot: number | null;
  carrier: string | null;
}

export interface Conversation {
  addressKey: string;
  address: string;
  messages: Message[];
  latest: Message;
  unread: number;
}

/** Port of core/model/AddressKey.kt. */
export function addressKey(address: string | null | undefined): string {
  const trimmed = (address ?? "").trim();
  if (!trimmed) return "";
  if (/\p{L}/u.test(trimmed)) return trimmed.toLowerCase();
  const digits = trimmed.replace(/\D/g, "");
  return digits.length > 10 ? digits.slice(-10) : digits;
}

const MESSAGE_TYPES = new Set(["SMS_RECEIVED", "SMS_SENT", "SMS_STATUS_CHANGED", "SMS_IMPORTED"]);

export interface MessageStore {
  messages: Message[];
  /** Messages a phone reported as deleted; hidden from the lists, never removed silently. */
  deletedOnPhone: number;
  skippedEvents: number;
}

export function buildMessages(events: Iterable<DecryptedEvent>): MessageStore {
  // 1. Newest state per (device, messageId).
  const latest = new Map<string, DecryptedEvent>();
  const deleted = new Set<string>();
  let skipped = 0;
  for (const e of events) {
    const p = e.payload;
    if (!p.messageId) {
      skipped++;
      continue;
    }
    const id = `${e.deviceId}:${p.messageId}`;
    if (p.type === "SMS_DELETED") {
      deleted.add(id);
      continue;
    }
    if (!MESSAGE_TYPES.has(p.type) || p.body === undefined || p.date === undefined) {
      skipped++;
      continue;
    }
    const prev = latest.get(id);
    if (!prev || prev.serverSequence < e.serverSequence) latest.set(id, e);
  }

  // 2. Merge copies of the same SMS from different devices.
  const byContent = new Map<string, Message>();
  let deletedOnPhone = 0;
  for (const [id, e] of latest) {
    if (deleted.has(id)) {
      deletedOnPhone++;
      continue;
    }
    const p = e.payload;
    const box = p.box ?? "UNKNOWN";
    const key = addressKey(p.address);
    const contentId = `${box}|${key}|${p.date}|${p.body}`;
    const existing = byContent.get(contentId);
    if (existing) {
      if (!existing.deviceIds.includes(e.deviceId)) existing.deviceIds.push(e.deviceId);
      existing.read ||= p.read ?? false;
      continue;
    }
    byContent.set(contentId, {
      key: id,
      address: p.address ?? "",
      addressKey: key,
      body: p.body ?? "",
      date: p.date ?? 0,
      dateSent: p.dateSent ?? 0,
      box,
      read: p.read ?? false,
      status: p.status ?? null,
      deviceIds: [e.deviceId],
      simSlot: typeof p.slot === "number" && p.slot > 0 ? p.slot : null,
      carrier: p.carrier?.trim() || null,
    });
  }
  const messages = [...byContent.values()].sort((a, b) => b.date - a.date);
  return { messages, deletedOnPhone, skippedEvents: skipped };
}

export function conversations(messages: Message[]): Conversation[] {
  const groups = new Map<string, Message[]>();
  for (const m of messages) {
    const list = groups.get(m.addressKey);
    if (list) list.push(m);
    else groups.set(m.addressKey, [m]);
  }
  return [...groups.entries()]
    .map(([key, list]) => {
      list.sort((a, b) => a.date - b.date);
      const last = list[list.length - 1]!;
      return {
        addressKey: key,
        address: last.address || "(no number)",
        messages: list,
        latest: last,
        unread: list.filter((m) => m.box === "INBOX" && !m.read).length,
      };
    })
    .sort((a, b) => b.latest.date - a.latest.date);
}

export function search(messages: Message[], query: string): Message[] {
  const q = query.trim().toLowerCase();
  if (!q) return messages;
  return messages.filter((m) => m.body.toLowerCase().includes(q) || m.address.toLowerCase().includes(q));
}

/** "SIM 1 · Grameenphone", or null when the phone did not say. */
export function simLabel(m: Pick<Message, "simSlot" | "carrier">): string | null {
  if (m.simSlot == null) return null;
  return m.carrier ? `SIM ${m.simSlot} · ${m.carrier}` : `SIM ${m.simSlot}`;
}

const OTP_HINT = /\b(code|otp|pin|passcode|password|verification|verify|one[- ]time)\b|কোড|ওটিপি|পিন/i;

const DIGIT_WORDS: Record<string, string> = {
  zero: "0", one: "1", two: "2", three: "3", four: "4", five: "5", six: "6", seven: "7", eight: "8", nine: "9",
};
const WORD = Object.keys(DIGIT_WORDS).join("|");
// Four or more number words joined by _, -, spaces or commas: "Seven_Zero_Three_Eight_Seven_Five".
const SPELLED = new RegExp(`\\b(?:${WORD})(?:[\\s_,-]+(?:${WORD})){3,}\\b`, "i");

/**
 * The one-time code in a message ("Your code is 482913"), for the Copy button. Codes spelled out
 * in words ("type Seven_Zero_Three_Eight_Seven_Five") come back as digits ("703875").
 */
export function otpOf(body: string): string | null {
  const spelled = body.match(SPELLED);
  if (spelled) return spelled[0].split(/[\s_,-]+/).map((w) => DIGIT_WORDS[w.toLowerCase()]).join("");
  if (!OTP_HINT.test(body)) return null;
  const match = body.match(/(?<![\d.,:/-])(\d{4,8})(?![\d.,:/-]\d)/);
  return match ? match[1]! : null;
}
