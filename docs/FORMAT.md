# dose.json and the sync format

One JSON document, the same on the PC (`~/.local/share/dose/dose.json`) and in the iPhone app,
and the same over the sync:

```json
{
  "medicines": [ { ...medicine } ],
  "doses":     [ { ...dose } ],
  "deleted":   [ { "id": "…", "stamp": "2026-11-25T10:00:00.000Z" } ]
}
```

**Medicine**

| field | |
|---|---|
| `id` | 16 hex characters |
| `name`, `strength`, `form`, `ingredient`, `note` | text (`strength` like "50 mg") |
| `amount`, `unit` | each time: 0.25–1000 of `pill`, `capsule`, `drop`, `ml`, `puff`, `sachet`, `patch`, `injection`, `application` |
| `schedule` | `kind` (`daily`, `weekdays`, `every`, `needed`), `times` (["08:00", …]), `weekdays` (1 = Monday … 7), `every` (days), `start`, `end` ("" or the last day) |
| `stock` | `null`, or `{"count", "set"}`: what was left at the UTC time `set`; doses taken after it are subtracted |
| `refillDays` | warn when the stock lasts fewer days than this |
| `archived` | stopped taking it; kept with its history |
| `history` | `[{"date", "kind": started/changed/stopped/restarted, "detail": {amount, unit, strength, schedule}}]` |
| `updated` | UTC stamp of the last change |

**Dose** — a scheduled dose has the id `medicine@YYYY-MM-DD@HH:MM`; one taken when needed has
its own id and an empty `time`.

| field | |
|---|---|
| `med`, `day`, `time` | which medicine, day, and scheduled time ("" when needed) |
| `status` | `taken` or `skipped` |
| `at` | when it was actually taken ("HH:MM") |
| `amount` | how much |
| `stamp` | UTC, when it was marked (counts against the stock) |
| `updated` | UTC stamp of the last change |

**Merging**: for each id the newer `updated` wins; a tombstone in `deleted` wins over anything
not newer than it.

**Sync**: the phone POSTs to `http://<pc>:8766/api/e/sync` its whole document (plus `"device"`)
encrypted with AES-256-GCM: key = HKDF-SHA256(token, salt "dose-sync", info "dose sync v1"),
body `{"v": 1, "n": base64 nonce, "c": base64 ciphertext+tag}`, additional data
`dose/v1 request /api/e/sync` (and `reply` for the answer). The PC merges, saves and answers
with the merged document and `"changed"`. `/api/e/ping` answers `{"app": "Dose", "version"}`.
