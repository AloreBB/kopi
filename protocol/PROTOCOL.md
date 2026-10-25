# Kopi Protocol (v1)

Normative specification of the Kopi v1 wire protocol. `protocolVersion` = `1`.
Initial app version: `1.0.0`. Mac server listens on fixed port `47474` and
announces Bonjour service `_kopi._tcp`. Transport: WebSocket over mutual TLS 1.3
(P-256 keys, self-signed certificates, SHA-256 fingerprint pinning).

## 1. Pairing

1. **Mac QR:** tapping "Vincular teléfono" shows a QR with:
   `kopi://pair?v=1&host=<ip>&port=<p>&fp=<sha256 Mac cert>&code=<128-bit token>`.
   The code expires after 5 minutes (300_000 ms) and is single-use.
2. **Phone connection:** the phone scans the QR, connects over TLS verifying
   `fp`, presents its own certificate, and sends
   `{"type":"pair","code":"…","name":"Tecno Camon 40 Pro"}`.
3. **Confirmation:** the Mac validates the code, stores the phone certificate
   fingerprint, and replies `{"type":"paired","name":"MacBook Air"}`.
4. **Exclusivity:** from then on each side only accepts the other's
   fingerprint. Pairing a new phone replaces the previous one.

## 2. Session

- First message in both directions:
  `{"type":"hello","protocolVersion":1,"app":"1.0.0"}`. If the major versions
  differ, the connection closes with `{"type":"error","code":"version_mismatch"}`.
- Transport: WebSocket. Control messages go as JSON text frames; image data
  goes as binary frames.

### Session order

The client sends `hello`, then `pair` only when pairing. The server replies
`hello`, then `paired` or `error`.

### Error codes

`version_mismatch`, `not_paired`, `invalid_code`.

## 3. Clipboard messages

| Message | Fields | Use |
|---|---|---|
| `clip` | `id` (UUID), `ts` (sender ms epoch), `kind:"text"`, `text`, `sensitive` (bool) | Text: sent complete immediately |
| `clip_offer` | `id`, `ts`, `kind:"image"`, `mime` (`image/png`\|`image/jpeg`), `size` | Image availability notice |
| `clip_request` | `id` | Request for the data of an offer |
| `clip_data` | `id`, `size` + binary frames `[16 bytes id][fragment ≤ 256 KB]` | Image delivery |
| `clip_unavailable` | `id`, `reason` (`expired`\|`replaced`) | The offer can no longer be served |

- **Android → Mac image:** the Mac registers a promise in `NSPasteboard` and
  requests `clip_request` when the user pastes. If no data arrives within 5 s,
  it returns empty.
- **Mac → Android image:** Android requests `clip_request` as soon as it
  receives the offer, because it has no promises, and writes the image when the
  download completes.
- **Expiry:** an offer is valid for 2 minutes from `ts` (120_000 ms). After
  that, the origin replies `clip_unavailable`.
- **Limit:** images larger than 20 MB (`20 * 1024 * 1024` bytes) are not
  offered. HEIC and TIFF are converted to PNG before being offered.
- Text larger than `4 * 1024 * 1024` bytes in UTF-8 is not sent.
- Image fragments are `256 * 1024` bytes.

### Binary frame

`[16 bytes of the UUID in big-endian][fragment ≤ 262144 bytes]`. The transfer
ends when the sum of received bytes equals `size`.

### QR format

`kopi://pair?v=1&host=<ipv4>&port=47474&fp=<hex64>&code=<base64url without padding, 22 characters>`.

## 4. SyncEngine rules

1. **No loops:** an `id` received and written locally is never forwarded.
2. **Conflicts:** the larger `ts` wins. On equal `ts`, the lexicographically
   larger `id`.
3. **Reconnect:** only the latest local item is sent, and only if younger than
   2 minutes. No queue.
4. **Sensitive content:** content flagged as sensitive syncs like any other.
   The flag is only honored in previews and logs.
