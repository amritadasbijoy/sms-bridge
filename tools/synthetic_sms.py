#!/usr/bin/env python3
"""Generate a deterministic, entirely synthetic SMS backup XML for testing SMS Bridge.

Output uses the common <smses><sms .../></smses> layout that SMS Bridge restores.
All numbers are in the fictional +1-555-01xx range or are made-up sender ids.
Never put real messages, numbers or OTPs into test data.

Usage: python3 tools/synthetic_sms.py [count] [output.xml]
"""
import random
import sys
from xml.sax.saxutils import quoteattr

COUNT = int(sys.argv[1]) if len(sys.argv) > 1 else 5000
OUT = sys.argv[2] if len(sys.argv) > 2 else "synthetic-sms.xml"
rng = random.Random(20261101)  # fixed seed: same file every run

END = 1_793_500_000_000  # ~2026-11-01
START = END - 3 * 365 * 24 * 3600 * 1000

contacts = [f"+1555010{n:04d}" for n in range(120)]
# Same numbers in other formats, to exercise conversation grouping.
alt_formats = {c: [c, f"1 (555) 010-{c[-4:]}", f"555010{c[-4:]}"] for c in contacts[:30]}
alpha_senders = ["SYNTH-BANK", "TEST-ALERTS", "DemoShop", "Courier-X"]

english = [
    "Running ten minutes late, sorry!",
    "Can you pick up milk on the way home?",
    "Meeting moved to 3pm tomorrow.",
    "Happy birthday! Hope you have a great day.",
    "Did you see the match last night?",
    "Call me when you are free.",
    "Thanks, received the parcel.",
    "Weather looks rainy this weekend.",
]
bangla = [
    "আজ সন্ধ্যায় দেখা হবে।",
    "ভাত খেয়েছ?",
    "কাল সকালে ফোন করব।",
    "অনেক ধন্যবাদ!",
    "বাসায় পৌঁছে জানিও।",
]
emoji = ["😀", "👍🏽", "🎉", "❤️", "🙏", "☕"]


def body(i: int) -> str:
    kind = rng.random()
    if kind < 0.45:
        text = rng.choice(english)
    elif kind < 0.70:
        text = rng.choice(bangla)
    elif kind < 0.78:
        text = f"Your synthetic verification code is {rng.randint(100000, 999999)}. Do not share it. (TEST DATA)"
    elif kind < 0.86:
        text = rng.choice(english) + "\n" + rng.choice(bangla) + "\n— sent from a test generator"
    elif kind < 0.93:
        # Long: multipart in GSM-7 (> 160 chars).
        text = " ".join(rng.choice(english) for _ in range(6))
    else:
        # Long Bangla: multipart in UCS-2 (> 70 chars).
        text = " ".join(rng.choice(bangla) for _ in range(5))
    if rng.random() < 0.15:
        text += " " + rng.choice(emoji)
    if i % 997 == 0:
        text = 'Quotes "double" and \'single\', <tags> & ampersands, tab\there'
    if i % 1499 == 0:
        text = ""
    return text


def address(i: int):
    r = rng.random()
    if i % 1201 == 0:
        return None
    if r < 0.10:
        return rng.choice(alpha_senders)
    c = rng.choice(contacts)
    return rng.choice(alt_formats.get(c, [c]))


rows = []
for i in range(1, COUNT + 1):
    t = rng.random()
    msg_type = 1 if t < 0.80 else 2 if t < 0.98 else 5 if t < 0.99 else 3
    date = rng.randint(START, END)
    date_sent = (date - rng.randint(1000, 60000)) // 1000 * 1000 if msg_type == 1 else 0
    rows.append(dict(
        address=address(i), body=body(i), date=date, date_sent=date_sent, type=msg_type,
        read=1 if (msg_type != 1 or rng.random() < 0.9) else 0,
        sub_id=rng.choice([0, 1]),
    ))
rows.sort(key=lambda r: r["date"])
rows.append(dict(rows[10]))  # exact duplicate inside the file

with open(OUT, "w", encoding="utf-8") as f:
    f.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
    f.write(f'<smses count="{len(rows) + 1}" backup_set="synthetic-20261101" backup_date="{END}" type="full">\n')
    for r in rows:
        attrs = {
            "protocol": "0", "address": r["address"] or "null", "date": str(r["date"]), "type": str(r["type"]),
            "subject": "null", "body": r["body"], "toa": "null", "sc_toa": "null", "service_center": "null",
            "read": str(r["read"]), "status": "-1", "locked": "0", "date_sent": str(r["date_sent"]),
            "sub_id": str(r["sub_id"]), "readable_date": "synthetic", "contact_name": "(Unknown)",
        }
        # Newlines/tabs as character references so attribute normalisation cannot change them.
        parts = " ".join(
            f"{k}={quoteattr(v, {chr(10): '&#10;', chr(13): '&#13;', chr(9): '&#9;'})}" for k, v in attrs.items()
        )
        f.write(f"  <sms {parts} />\n")
    f.write('  <mms date="1700000000000" msg_box="1"><parts><part seq="0" ct="text/plain" text="MMS is skipped" /></parts></mms>\n')
    f.write('  <sms address="+15550100999" type="1" body="row without a date is skipped" />\n')
    f.write("</smses>\n")

inbox = sum(1 for r in rows if r["type"] == 1)
print(f"wrote {OUT}: {len(rows)} sms rows ({inbox} inbox, {len(rows) - inbox} other; 1 duplicate), 1 mms, 1 invalid row")
