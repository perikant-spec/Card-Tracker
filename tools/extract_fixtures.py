#!/usr/bin/env python3
"""
Turn an SMS Backup & Restore (SyncTech) XML export into parser fixtures.
Runs entirely locally; standard library only; no network.

  list-senders  Show which sender IDs look like bank/card alerts (counts only,
                no message text is printed).
  extract       Keep inbox messages from chosen senders, drop OTPs, and write:
                  <out>/filtered.jsonl   raw bodies (private, git-ignored)
                  <out>/redacted.jsonl   best-effort redacted copy

Redaction is best effort. Always read redacted.jsonl yourself before sharing
any line of it.

Usage:
  python3 tools/extract_fixtures.py list-senders sms-2026xxxx.xml
  python3 tools/extract_fixtures.py extract sms-2026xxxx.xml \
      --senders 'HDFC|ICICI|SBI|AXIS' --out fixtures/real
"""
import argparse
import collections
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

INBOX = "1"  # SyncTech: type 1 = received, 2 = sent

TXN_LIKE = re.compile(r"\b(card|spent|debited|credited|payment|statement|due|refund|reversal)\b", re.I)
OTP_LIKE = re.compile(r"\b(otp|one[- ]time password|verification code)\b", re.I)

EMAIL = re.compile(r"[\w.+\-]+@[\w\-]+(?:\.[\w\-]+)*")
URL = re.compile(r"https?://\S+|\b(?:www\.)\S+")
PHONE = re.compile(r"(?<!\d)(?:\+?91[\s\-]?)?[6-9]\d{9}(?!\d)")
GREETING = re.compile(r"\b(Dear|Hi|Hello)\s+((?:Mr|Ms|Mrs|Dr)\.?\s+)?([A-Z][A-Za-z]+(?:\s[A-Z][A-Za-z]+){0,2})")
GENERIC_NAMES = {"customer", "cardmember", "card", "sir", "madam", "user", "valued"}


def iter_inbox(path):
    """Stream the file; backups can be hundreds of MB."""
    for _, el in ET.iterparse(path, events=("end",)):
        if el.tag == "sms":
            attrs = dict(el.attrib)  # copy: clear() empties el.attrib
            el.clear()
            if attrs.get("type") == INBOX:
                yield attrs
        elif el.tag == "mms":
            el.clear()


def redact(body: str) -> str:
    body = EMAIL.sub("redacted@example", body)
    body = URL.sub("https://example.invalid", body)
    body = PHONE.sub("9000000000", body)

    def name(m):
        first = m.group(3).split()[0].lower()
        if first in GENERIC_NAMES:
            return m.group(0)
        return f"{m.group(1)} {m.group(2) or ''}NAME"

    return GREETING.sub(name, body)


def cmd_list(args):
    total = collections.Counter()
    txn = collections.Counter()
    for m in iter_inbox(args.xml):
        addr = m.get("address", "")
        # Personal numbers are digits/+; bank IDs are alphanumeric like VM-HDFCBK-S.
        if re.fullmatch(r"\+?\d[\d\s\-]*", addr):
            continue
        total[addr] += 1
        if TXN_LIKE.search(m.get("body", "")):
            txn[addr] += 1
    print(f"{'sender':<24}{'messages':>9}{'txn-like':>10}")
    for addr, _ in txn.most_common(args.top):
        print(f"{addr:<24}{total[addr]:>9}{txn[addr]:>10}")
    print("\nPick the IDs that belong to your six issuers, then run `extract`.")


def cmd_extract(args):
    senders = re.compile(args.senders, re.I)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    kept = dropped_otp = 0
    per_sender = collections.Counter()
    with open(out / "filtered.jsonl", "w", encoding="utf-8") as raw, \
         open(out / "redacted.jsonl", "w", encoding="utf-8") as red:
        for m in iter_inbox(args.xml):
            addr, body = m.get("address", ""), m.get("body", "")
            if not senders.search(addr):
                continue
            if OTP_LIKE.search(body):
                dropped_otp += 1
                continue
            rec = {"sender": addr, "date_ms": int(m.get("date", "0"))}
            raw.write(json.dumps({**rec, "body": body}, ensure_ascii=False) + "\n")
            red.write(json.dumps({**rec, "body": redact(body)}, ensure_ascii=False) + "\n")
            kept += 1
            per_sender[addr] += 1
    print(f"kept {kept}, dropped {dropped_otp} OTP messages")
    for addr, n in per_sender.most_common():
        print(f"  {addr}: {n}")
    print(f"wrote {out}/filtered.jsonl (private) and {out}/redacted.jsonl (review before sharing)")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("list-senders")
    a.add_argument("xml")
    a.add_argument("--top", type=int, default=40)
    a.set_defaults(fn=cmd_list)
    b = sub.add_parser("extract")
    b.add_argument("xml")
    b.add_argument("--senders", required=True, help="case-insensitive regex matched against sender ID")
    b.add_argument("--out", default="fixtures/real")
    b.set_defaults(fn=cmd_extract)
    args = p.parse_args(argv)
    args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
