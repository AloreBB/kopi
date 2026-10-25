# Kopi Protocol (v1)

Normative specification of the Kopi v1 wire protocol. `protocolVersion` = `1`.
Initial app version: `1.0.0`. Mac server listens on fixed port `47474` and
announces Bonjour service `_kopi._tcp`. Transport: WebSocket over mutual TLS 1.3
(P-256 keys, self-signed certificates, SHA-256 fingerprint pinning).

## 1. Pairing (trust on first use)

There is no QR code or pairing code. Pairing is automatic, with undo:

1. **Discovery:** the phone finds the Mac through Bonjour `_kopi._tcp` (or its
   last known host:port) and connects over TLS presenting its own certificate.
2. **TLS verify on the Mac (server):** if **no phone is paired**, any client
   certificate is accepted. If a phone is paired, only that phone's certificate
   fingerprint is accepted.
3. **TLS verify on the phone (client):** while it has no Mac stored, the phone
   accepts the Mac's certificate and stores its fingerprint once it receives
   `paired`. From then on it accepts only that fingerprint.
4. **Automatic pairing:** when an unpaired Mac receives a valid `hello`, it
   stores the client certificate fingerprint and `hello.name`, and replies
   `hello` followed by `{"type":"paired","name":"MacBook Air"}`.
5. **Exclusivity:** from then on each side only accepts the other's
   fingerprint. **Desvincular** on the Mac forgets the phone and drops the
   session; the next phone that connects pairs automatically.

## 2. Session

- First message in both directions:
  `{"type":"hello","protocolVersion":1,"app":"1.0.0","name":"Tecno Camon 40 Pro"}`.
  `name` is **required**: the sender's display name (the phone's device name,
  the Mac's computer name). If the major versions differ, the connection closes
  with `{"type":"error","code":"version_mismatch"}` and nothing is paired.
- Transport: WebSocket. Control messages go as JSON text frames; image data
  goes as binary frames.

### Session order

The client sends `hello`. The server checks `protocolVersion`, then:

| Mac state | Server reply |
|---|---|
| No phone paired | Stores the client fingerprint and `hello.name`; replies `hello`, then `paired` |
| Paired with this client's fingerprint | Replies `hello` (and updates the stored name if it changed) |
| Anything else (e.g. another phone paired while this one was connecting) | `error not_paired`, then closes |

A client whose certificate is not the paired one is rejected during the TLS
handshake and never gets a message.

### Error codes

`version_mismatch`, `not_paired`.

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

## 4. SyncEngine rules

1. **No loops:** an `id` received and written locally is never forwarded.
2. **Conflicts:** the larger `ts` wins. On equal `ts`, the lexicographically
   larger `id`.
3. **Reconnect:** only the latest local item is sent, and only if younger than
   2 minutes. No queue.
4. **Sensitive content:** content flagged as sensitive syncs like any other.
   The flag is only honored in previews and logs.
