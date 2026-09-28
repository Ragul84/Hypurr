# Voice call

A hands-free call with one bot from the iPhone chat (the waveform button in an empty composer).
The bot is the same agent on the computer; the call only changes how you talk to it.

## Implemented: on the phone

- `kit/Sources/CodyncUI/Call/`: `CallSession` (audio loop) + `CallView` (the call bar).
- UI follows Grok Bot: a floating capsule over the top of the chat (`ThreadView` overlay), and the
  chat stays readable and usable underneath. Left to right: the bot's avatar (pulses while
  speaking; tap it to interrupt), a dotted level line (your voice while listening, a ripple while
  the bot speaks, a slow breath while it works), gear (call settings), mic (mute), red ✕ (end).
- Speech → text with `SFSpeechRecognizer` (on-device when the language supports it). A pause ends an
  utterance and sends it as an ordinary message (`BotStore.send`), so it takes the normal path:
  queued while the bot works, folded into its next turn. What you said appears in the chat as your
  message; the bot's reply appears as its message.
- Each new final reply (`data.final`) is read aloud with `AVSpeechSynthesizer` in the reply's own
  language; markdown and code blocks are dropped (`SpokenText`). The mic pauses while it speaks.
  An approval request is announced, answered in the chat.
- Gear: pause length before sending (1 / 1.5 / 2.5 s) and reading speed; kept on the phone.
- Ending the call calls `logCall {botId, seconds}`; the host adds a notice (`callSeconds`) that
  every client shows as "Voice chat · 00:16".
- The host receives recognized text; the Cloudflare transport sees encrypted channel frames.
  Codync does not forward microphone audio to the host. Speech recognition may use Apple services
  when on-device recognition is unavailable. `UIBackgroundModes: audio` keeps the call alive with
  the screen locked.

## Design: realtime voice with your own key (not implemented)

Better recognition (mixed Chinese/English, code terms) and a real conversation (barge-in, "what are
you doing?" answered from the transcript) need a realtime speech model. Users bring their own
provider key; Codync never resells minutes for it.

### Engines

The gear's first section becomes **Voice engine**:

| Engine | Needs | Transport |
| --- | --- | --- |
| On device (default) | nothing | what ships today |
| OpenAI Realtime | OpenAI API key | WebRTC, phone ↔ OpenAI |
| Gemini Live | Google AI Studio key | WebSocket, phone ↔ Google |

Picking a cloud engine without a key opens its key form: a secure field, a link to the provider's
key page, **Test** (one cheap authenticated call; shows "Key works" or the provider's error), and a
voice picker. The call bar shows the provider's small wordmark-free label ("OpenAI") next to the
dots whenever audio leaves the phone.

### Where the key lives

- On the iPhone only, in the Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not
  synced). It is never sent to the host, the relay or our cloud, and never written to logs.
- The long-lived key is used for one thing: minting a short-lived client credential at call start
  (OpenAI `POST /v1/realtime/client_secrets`, Gemini ephemeral auth tokens). The audio session only
  ever sees that credential.
- Rejected: key on the host with audio proxied through it. Audio would cross the encrypted channel
  and the relay (latency, relay bandwidth), and a Linux server host has no microphone reason to
  hold it.

### How the call works

The realtime model is an operator in front of the bot, not a replacement for it:

- Session instructions: who the bot is (name, description), that coding is done by the bot, reply
  in the user's language, keep spoken answers short.
- Tools the phone implements locally against `BotStore`: `send_to_bot(text)` (an ordinary message,
  same path as today), `bot_status()` (idle/working, activity line, pending approval),
  `recent_messages(count)` (chat-visible entries only), `answer_approval(option)` (only after the
  user said which).
- Each new final reply is pushed into the realtime conversation as a context item; the model speaks
  it (shortened for speech). Barge-in is the provider's own voice activity detection.
- The chat stays the record: only `send_to_bot` messages and the bot's replies are entries. The
  operator's small talk isn't saved; the call still ends with the "Voice chat" notice.

### Failure and cost

- Invalid key, quota or network failure at start → the call falls back to On device with a one-line
  notice under the bar; mid-call failure does the same without dropping the call.
- Usage is billed by the provider to the user. The gear shows minutes used this month (counted on
  the phone) next to the engine, as an estimate, not a bill.

### Code layout

- `kit/Sources/CodyncUI/Call/VoiceEngine.swift`: `protocol VoiceEngine` (start, end, mute,
  interrupt, `phase`, `level`, `onUserMessage`, `speak(reply:)`); `OnDeviceEngine` is today's
  `CallSession`; `OpenAIRealtimeEngine` (WebRTC, the app already links WebRTC for Remote screen)
  and `GeminiLiveEngine` (URLSessionWebSocketTask).
- `kit/Sources/CodyncKit/Client/VoiceKeys.swift`: Keychain storage + credential minting.
- `CallView` only talks to `VoiceEngine`; nothing on the host changes.
- A later Codync Pro plan can add a second credential source (our server mints the client token after
  checking the entitlement) behind the same engines.
