# Connect from anywhere

The phone reaches the computer from office Wi-Fi or mobile data, not only the home network.

- **How:** the phone tries the computer's direct addresses first (LAN / Tailscale, 1.5 s budget). If none
  answers it goes through the relay at `https://relay.mindgains.ai` (the `hypurr-cloud` Worker, same
  code as `hypurr.mindgains.ai`). The status reads **Connected directly** or **Connected from anywhere**.
- **Private:** the channel is end-to-end encrypted with keys from the QR pairing (host signing key and
  box key in the QR, X25519 handshake per connection, see [remote relay](../reference/remote-relay.md)).
  The relay forwards ciphertext only and never parses frames. `npm run e2e:live` in `cloud/` checks this
  against the live relay: the message text never appears on the wire.
- **Defaults:** a host build uses `relay.mindgains.ai` unless `HYPURR_DEFAULT_CLOUD_URL` is set at build
  time; `HYPURR_CLOUD_URL` overrides at run time.
- **Turning it off:** phone → You → Connection → *Connect from anywhere* (direct only). On the computer,
  `setCloud {enabled:false}` (or `HYPURR_CLOUD=off`, or IT local-only mode) takes it off the relay.
- **Abuse limits:** relay socket opens are limited to 60/min per computer (host) and per IP+computer
  (device) via `RELAY_LIMITER`; new computers 10/min per IP; the Durable Object caps 200 messages /
  8 MiB per 10 s per socket, 16 device sockets per computer, and the offline mailbox size.

Deploy: `cd cloud && npx wrangler deploy` (both custom domains are in `wrangler.toml`).
