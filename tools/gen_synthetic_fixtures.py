#!/usr/bin/env python3
"""Regenerates fixtures/sample/synthetic.jsonl. Every message is SYNTHETIC: fake merchants,
fake card digits, fake sender IDs. Nothing here was copied from a real bank message.

Usage: python3 tools/gen_synthetic_fixtures.py fixtures/sample/synthetic.jsonl
"""
import json, datetime, sys
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
def ms(d, h=12, mi=0, mo=11): return int(datetime.datetime(2026, mo, d, h, mi, tzinfo=IST).timestamp()*1000)
ISS = {  # issuer -> (sender, card name used in synthetic text, last4)
 "HDFC": ("TS-HDFCBK", "HDFC Bank", "1111"),
 "ICICI": ("TS-ICICIB", "ICICI Bank", "2222"),
 "SCAPIA": ("TS-SYNSCP", "Scapia", "3333"),
 "KIWI": ("TS-SYNKWI", "Kiwi", "4444"),
 "SBI": ("TS-SBICRD", "SBI", "5555"),
 "AXIS": ("TS-AXISBK", "Axis Bank", "6666"),
}
out = []
def add(issuer, body, expect, d=15, h=12):
    out.append({"sender": ISS[issuer][0], "date_ms": ms(d, h), "issuer": issuer, "body": body, "expect": expect})
def P(type_, paise, last4, **kw):
    e = {"result": "parsed", "type": type_, "amount_paise": paise, "last4": last4, "auth": False, "reversal": False}
    e.update(kw); return e
for iss, (sender, name, l4) in ISS.items():
    c = f"{name} Credit Card"
    add(iss, f"Rs.1,250.00 spent on {name} Card x{l4} at FAKEMART STORE on 15-11-26", P("PURCHASE", 125000, l4, merchant="FAKEMART STORE", date="2026-11-15", template=f"{iss.lower()}.spend.amt_spent_at"))
    add(iss, f"Alert: INR 99 spent on your {c} ending {l4} at TESTCAFE.IN. Avl Lmt Rs.50,000.00", P("PURCHASE", 9900, l4, merchant="TESTCAFE.IN", template=f"{iss.lower()}.spend.amt_spent_at"))
    add(iss, f"Spent Rs.1250 On {name} Card {l4} At SAMPLE FUELS On 2026-11-15:14:22:10.Not You? Call 1800000000", P("PURCHASE", 125000, l4, merchant="SAMPLE FUELS", date="2026-11-15", template=f"{iss.lower()}.spend.spent_amt_at"))
    add(iss, f"INR 1,25,000.50 spent using {name} Card XX{l4} on 15-Nov-26 on DEMO JEWELS. Avl Limit: INR 2,00,000.00", P("PURCHASE", 12500050, l4, merchant="DEMO JEWELS", date="2026-11-15", template=f"{iss.lower()}.spend.date_then_merchant"))
    add(iss, f"Thank you for using your {c} XX{l4} for INR 1,250.00 at EXAMPLE BOOKS", P("PURCHASE", 125000, l4, merchant="EXAMPLE BOOKS", template=f"{iss.lower()}.spend.thank_you"))
    add(iss, f"Dear Customer, transaction of ₹450.75 made on your {c} ending {l4} at MOCK PHARMACY on 14/11/2026 Ref no AB12CD34", P("PURCHASE", 45075, l4, merchant="MOCK PHARMACY", date="2026-11-14", ref="AB12CD34", template=f"{iss.lower()}.spend.txn_of"))
    add(iss, f"Rs.250.00 debited from your {c} XX{l4} via UPI to SYNTH TEA STALL on 15-11-2026", P("PURCHASE", 25000, l4, merchant="SYNTH TEA STALL", date="2026-11-15", template=f"{iss.lower()}.spend.debited"))
    add(iss, f"Payment of Rs 5,000 received towards your card ending {l4}", P("PAYMENT", 500000, l4, template=f"{iss.lower()}.payment.received"))
    add(iss, f"Dear Cardmember, we have received payment of Rs.7,500.00 towards your {c} XX{l4} on 14-11-2026. Thank you", P("PAYMENT", 750000, l4, date="2026-11-14", template=f"{iss.lower()}.payment.we_received"))
    add(iss, f"Refund of Rs.500.00 from FAKEMART STORE has been credited to your {c} x{l4} on 15-11-26", P("REFUND", 50000, l4, merchant="FAKEMART STORE", date="2026-11-15", template=f"{iss.lower()}.refund.from_merchant"))
    add(iss, f"Refund of INR 300 has been credited to your {c} XX{l4}.", P("REFUND", 30000, l4, template=f"{iss.lower()}.refund.plain"))
    add(iss, f"Your transaction of Rs.500.00 at FAKEMART STORE on {c} x{l4} has been reversed on 15-11-26", P("REFUND", 50000, l4, reversal=True, merchant="FAKEMART STORE", date="2026-11-15", template=f"{iss.lower()}.reversal.txn_reversed"))
    add(iss, f"Transaction reversal of Rs.640.00 has been credited to your {c} ending {l4}", P("REFUND", 64000, l4, reversal=True, template=f"{iss.lower()}.reversal.credited"))
    add(iss, f"Late payment fee of Rs.500.00 has been charged to your {c} XX{l4}", P("FEE", 50000, l4, merchant="Late payment fee", template=f"{iss.lower()}.fee.charged"))
    add(iss, f"GST of Rs.90.00 levied on your {c} XX{l4} on 15-11-26", P("FEE", 9000, l4, merchant="GST", template=f"{iss.lower()}.fee.charged"))
    add(iss, f"Finance charges of Rs.312.40 have been levied on your {c} XX{l4}", P("FEE", 31240, l4, template=f"{iss.lower()}.fee.charged"))
    add(iss, f"EMI of Rs.2,000.00 has been debited to your {c} XX{l4}", P("PURCHASE", 200000, l4, merchant="EMI", template=f"{iss.lower()}.emi.debited"))
    add(iss, f"Statement for your {c} ending {l4} is generated. Total Amt Due Rs.10,000.00, Min Amt Due Rs.500.00, due date 05-12-2026",
        {"result": "parsed", "last4": l4, "template": f"{iss.lower()}.statement.for_card", "statement": {"total_due_paise": 1000000, "min_due_paise": 50000, "due_date": "2026-12-05"}}, d=16)
    add(iss, f"{c} x{l4} Statement: Total Amount Due: INR 1,23,456.78; Minimum Amount Due: INR 6,172.00; Payment due by 05-Dec-26. Pay on time",
        {"result": "parsed", "last4": l4, "template": f"{iss.lower()}.statement.card_first", "statement": {"total_due_paise": 12345678, "min_due_paise": 617200, "due_date": "2026-12-05"}}, d=16)
    # things that must NOT become ledger rows
    add(iss, f"123456 is your OTP for txn of Rs.1,250.00 at FAKEMART STORE on {c} ending {l4}. Do not share it.", {"result": "ignored"})
    add(iss, f"OTP for your {c} XX{l4} transaction of INR 999.00 is 654321. Valid for 5 minutes.", {"result": "ignored"})
    add(iss, f"Rs.5,000.00 debited from A/c XX9090 on 15-11-26 towards {c} payment. Avl Bal Rs.12,345.67", {"result": "ignored"})
    add(iss, f"Get up to 10% cashback on your {c}. Offer valid till 30 Nov. T&C apply.", {"result": "ignored"})
    add(iss, f"Reminder: payment for your {c} XX{l4} is due on 05-12-2026.", {"result": "ignored"})
    add(iss, f"Transaction of Rs.1,250.00 made on your {c} ending {l4} at FAKEMART STORE on 15-11-26 was declined.", {"result": "unparsed"})
    add(iss, f"Rs.1,250.00 spent on {name} Card x{l4} at FAKEMART STORE on 31-02-26", {"result": "unparsed"})
    add(iss, f"Your purchase of Rs.30,000.00 on {c} XX{l4} has been converted to EMI of 6 months.", {"result": "unparsed"})
    add(iss, f"Rs.12,50.00 spent on {name} Card x{l4} at FAKEMART STORE on 15-11-26", {"result": "unparsed"})
# Axis-only block layouts
l4 = ISS["AXIS"][2]
add("AXIS", f"Spent INR 499 Axis Bank Card no. XX{l4} 15-11-26 14:22:10 IST SAMPLE GROCER Avl Lmt INR 50000 Not you? SMS BLOCK {l4} to 0000000000", P("PURCHASE", 49900, l4, merchant="SAMPLE GROCER", date="2026-11-15", template="axis.spend.block_amount_first"))
add("AXIS", f"Spent\nCard no. XX{l4}\nINR 499.50\n15-11-26 14:22:10\nSAMPLE GROCER\nAvl Lmt INR 50000.00", P("PURCHASE", 49950, l4, merchant="SAMPLE GROCER", date="2026-11-15", template="axis.spend.block_card_first"))
with open(sys.argv[1], "w", encoding="utf-8") as f:
    for o in out: f.write(json.dumps(o, ensure_ascii=False) + "\n")
print(len(out))
