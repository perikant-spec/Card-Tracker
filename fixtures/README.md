# Fixtures

- `real/`    your exported bank SMS. Git-ignored. Never commit.
- `sample/`  redacted examples (names, card numbers, merchants altered) safe to commit.

Each fixture file: sender ID, received timestamp, body, and the expected parse
(type, amount in paise, last4, auth vs settled). Parser tests read these.
