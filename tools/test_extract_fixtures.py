"""Tests use synthetic messages only. Run: python3 -I tools/test_extract_fixtures.py"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("extract_fixtures.py")

XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<smses count="6">
  <sms address="VM-TESTBK-S" date="1700000000000" type="1" body="Rs.1,250.00 spent on Card XX1234 at ACME STORE on 15-11-26. Call 9876543210 or mail help@testbank.example" />
  <sms address="VM-TESTBK-S" date="1700000001000" type="1" body="123456 is your OTP for card txn of Rs.500. Do not share." />
  <sms address="VM-TESTBK-S" date="1700000002000" type="1" body="Dear Ravi Kumar, payment of Rs.5,000 received on card XX1234. Visit https://testbank.example/x" />
  <sms address="AX-OTHER" date="1700000003000" type="1" body="Big sale on shoes" />
  <sms address="+919876543210" date="1700000004000" type="1" body="Lunch tomorrow?" />
  <sms address="VM-TESTBK-S" date="1700000005000" type="2" body="sent message, must be ignored" />
  <mms date="1700000006000"><parts><part seq="0" data="AAAA" /></parts></mms>
</smses>
"""


def run(*args):
    return subprocess.run([sys.executable, "-I", str(SCRIPT), *args],
                          capture_output=True, text=True, check=True).stdout


class ExtractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.xml = self.tmp / "sms.xml"
        self.xml.write_text(XML, encoding="utf-8")

    def test_list_senders_hides_personal_numbers_and_bodies(self):
        out = run("list-senders", str(self.xml))
        self.assertIn("VM-TESTBK-S", out)
        self.assertNotIn("9876543210", out)
        self.assertNotIn("Lunch", out)

    def test_extract_filters_drops_otp_and_redacts(self):
        run("extract", str(self.xml), "--senders", "TESTBK", "--out", str(self.tmp / "o"))
        raw = [json.loads(l) for l in (self.tmp / "o/filtered.jsonl").read_text().splitlines()]
        red = [json.loads(l) for l in (self.tmp / "o/redacted.jsonl").read_text().splitlines()]
        self.assertEqual(len(raw), 2)            # OTP, sent, other senders excluded
        self.assertEqual(len(red), 2)
        self.assertIn("9876543210", raw[0]["body"])
        text = " ".join(r["body"] for r in red)
        self.assertNotIn("9876543210", text)
        self.assertNotIn("help@testbank.example", text)
        self.assertNotIn("Ravi", text)
        self.assertNotIn("https://testbank.example", text)
        self.assertIn("Rs.1,250.00", text)       # amounts survive: the parser needs them
        self.assertIn("XX1234", text)            # card last four survives


if __name__ == "__main__":
    unittest.main()
