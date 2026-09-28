#if os(iOS)
import AVFoundation
import CodyncKit
import SwiftUI

/// A voice call with a bot, as Grok Bot does it: a floating bar over the chat, which stays
/// readable underneath. What you say goes out as ordinary messages, its final replies are
/// read aloud, and the chat gets a "Voice chat · 00:16" line when the call ends.
struct CallView: View {
    let botId: String
    let close: () -> Void
    @Environment(BotStore.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var session: CallSession?
    @State private var startedAt = Date.now
    @State private var settings = false

    private var bot: Bot? { model.bots[botId] }
    /// The newest final reply; each new one is read aloud.
    private var lastReply: Entry? { model.chat(botId).last { $0.kind == "agent" && $0.data.final == true } }
    private var color: Color { bot.map { AvatarPalette.color($0.avatarColor) } ?? Palette.accent }

    var body: some View {
        VStack(spacing: 6) {
            bar
            if case .failed(let message) = session?.phase {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(Palette.danger)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .glass(in: Capsule())
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 12)
        .animation(Motion.reduced(Motion.fade, reduceMotion), value: session?.phase)
        .onAppear {
            let session = CallSession { [model, botId] in model.send($0, to: botId) }
            self.session = session
            startedAt = .now
            Task { await session.start() }
        }
        .onDisappear {
            session?.end()
            model.logCall(botId, seconds: Int(Date.now.timeIntervalSince(startedAt)))
        }
        .onChange(of: lastReply?.id) { _, _ in
            if let text = lastReply?.data.text { session?.speak(text) }
        }
        .onChange(of: bot?.needsInput) { _, needs in
            if needs == true { session?.speak("\(bot?.name ?? "The bot") needs your approval in the chat.") }
        }
        .codyncSheet(isPresented: $settings) { CallSettingsView() }
    }

    private var bar: some View {
        HStack(spacing: 10) {
            avatar
            CallLevelDots(level: session?.level ?? 0, speaking: session?.phase == .speaking,
                          working: bot?.isWorking == true, color: color, reduceMotion: reduceMotion)
                .frame(maxWidth: .infinity, minHeight: 20, maxHeight: 20)
                .accessibilityLabel(statusText)
            round("Call settings", "gearshape") { settings = true }
            let muted = session?.muted == true
            round(muted ? "Unmute" : "Mute", muted ? "mic.slash" : "mic", on: muted) { session?.muted.toggle() }
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 46, height: 46)
                    .background(Palette.danger, in: Circle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel("End call")
        }
        .padding(.leading, 14)
        .padding(.trailing, 7)
        .padding(.vertical, 7)
        .glass(in: Capsule())
        .shadow(color: .black.opacity(0.08), radius: 16, y: 6)
    }

    /// The bot; tapping it while it talks cuts the reply short.
    private var avatar: some View {
        Button { session?.interrupt() } label: {
            Group {
                if let bot { CharacterAvatar(bot: bot, size: 34) }
            }
            .scaleEffect(session?.phase == .speaking && !reduceMotion ? 1.1 : 1)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.6).repeatForever(autoreverses: true), value: session?.phase == .speaking)
        }
        .buttonStyle(.plain)
        .disabled(session?.phase != .speaking)
        .accessibilityLabel(session?.phase == .speaking ? "Interrupt" : bot?.name ?? "")
    }

    private func round(_ label: String, _ symbol: String, on: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(on ? Palette.onAccent : Palette.text)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 46, height: 46)
                .background(on ? Palette.accentFill : Palette.background, in: Circle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(label)
    }

    private var statusText: String {
        switch session?.phase {
        case .starting, nil: "Connecting"
        case .speaking: "Speaking"
        case .failed: "Can't listen"
        case .listening where session?.muted == true: "Muted"
        case .listening: bot?.isWorking == true ? "Working, listening" : "Listening"
        }
    }
}

/// The dotted line in the call bar: it follows your voice while listening, ripples while
/// the bot talks, breathes while it works, and rests as faint dots otherwise.
private struct CallLevelDots: View {
    let level: Float
    let speaking: Bool
    let working: Bool
    let color: Color
    let reduceMotion: Bool

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            Canvas { canvas, size in
                let t = context.date.timeIntervalSinceReferenceDate
                let spacing: CGFloat = 8
                let count = max(1, Int(size.width / spacing))
                for i in 0..<count {
                    let x = CGFloat(i) * spacing + spacing / 2
                    let wave = (sin(Double(i) * 0.55 - t * 6) + 1) / 2
                    let amount: Double = if speaking {
                        0.35 + 0.65 * wave
                    } else if level > 0.05 {
                        Double(level) * (0.4 + 0.6 * wave)
                    } else if working {
                        0.25 * ((sin(t * 2.4) + 1) / 2)
                    } else {
                        0
                    }
                    let height = 3 + CGFloat(amount) * (size.height - 3)
                    let rect = CGRect(x: x - 1.5, y: (size.height - height) / 2, width: 3, height: height)
                    canvas.fill(Path(roundedRect: rect, cornerRadius: 1.5), with: .color(color.opacity(0.3 + 0.7 * amount)))
                }
            }
        }
    }
}

/// The call bar's gear: how long a pause sends what you said, and how fast replies are read.
private struct CallSettingsView: View {
    @AppStorage(CallSession.pauseKey) private var pause = 1.5
    @AppStorage(CallSession.rateKey) private var rate = Double(AVSpeechUtteranceDefaultSpeechRate)

    var body: some View {
        VStack(spacing: 0) {
            ModalHeader("Voice chat")
            CardForm {
                CardSection("Send after a pause of", footer: "Longer gives you time to think mid-sentence.") {
                    SegmentedChoice(selection: $pause, options: [(1.0, "1 s"), (1.5, "1.5 s"), (2.5, "2.5 s")])
                }
                CardSection("Reading speed") {
                    SegmentedChoice(selection: $rate, options: [
                        (0.42, "Slower"), (Double(AVSpeechUtteranceDefaultSpeechRate), "Normal"), (0.56, "Faster"),
                    ])
                }
            }
        }
        .background(Palette.background)
    }
}
#endif
