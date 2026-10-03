#if os(iOS)
import AVFoundation
import HypurrKit
import SwiftUI

/// A voice call with a bot, as Grok Bot does it: a floating bar over the chat, which stays
/// readable underneath. What you say goes out as ordinary messages, its final replies are
/// read aloud, and the chat gets a "Voice chat · 00:16" line when the call ends.
struct CallView: View {
    let botId: String
    @Binding var isSpeaking: Bool
    @Binding var interrupt: (() -> Void)?
    let close: () -> Void
    @Environment(BotStore.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var session: CallSession?
    @State private var callID = UUID()
    @State private var startedAt = Date.now
    @State private var settings = false

    private var bot: Bot? { model.bots[botId] }
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
            session.onActivityChanged = { [weak session, model, botId, callID] active in
                if active {
                    model.beginVoiceCall(callID, botId: botId,
                                         speak: { [weak session] in session?.speak($0) },
                                         end: { [weak session] in session?.end() })
                } else {
                    model.endVoiceCall(callID)
                }
            }
            self.session = session
            startedAt = .now
            Task { await session.start() }
        }
        .onDisappear {
            session?.end()
            isSpeaking = false
            interrupt = nil
            model.endVoiceCall(callID)
            model.logCall(botId, seconds: Int(Date.now.timeIntervalSince(startedAt)))
        }
        .onChange(of: session?.phase) { _, phase in
            isSpeaking = phase == .speaking
            interrupt = phase == .speaking ? { session?.interrupt() } : nil
        }
        .hypurrSheet(isPresented: $settings) { CallSettingsView() }
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
                        0.5 + 0.5 * wave
                    } else if level > 0.05 {
                        max(0.2, Double(level)) * (0.45 + 0.55 * wave)
                    } else if working {
                        0.25 * ((sin(t * 2.4) + 1) / 2)
                    } else {
                        0.08
                    }
                    let height = 4 + CGFloat(amount) * (size.height - 4)
                    let rect = CGRect(x: x - 1.25, y: (size.height - height) / 2, width: 2.5, height: height)
                    let tint = speaking ? Palette.accent : color
                    canvas.fill(Path(roundedRect: rect, cornerRadius: 1.25), with: .color(tint.opacity(0.5 + 0.5 * amount)))
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
