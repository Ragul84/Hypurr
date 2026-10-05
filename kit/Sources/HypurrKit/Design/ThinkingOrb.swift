import SwiftUI

/// Native thinking-orbs states, using its tuned dot geometry and depth shading.
/// With `flow` the dots take the Hypurr colour flow instead of one tint.
/// Supply an adjacent status label; this decorative view is hidden from VoiceOver.
public struct ThinkingOrb: View {
    public enum State: String, CaseIterable, Sendable {
        case working, searching, listening, connecting
    }

    let state: State
    let size: CGFloat
    let color: Color
    let animated: Bool
    let flow: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @SwiftUI.State private var visible = false
    @SwiftUI.State private var epoch = Date.now

    public init(state: State = .working, size: CGFloat = 16, color: Color = Palette.text, animated: Bool = true, flow: Bool = false) {
        self.state = state
        self.size = size
        self.color = color
        self.animated = animated
        self.flow = flow
    }

    public var body: some View {
        let running = animated && !reduceMotion && visible && scenePhase == .active
        Group {
            if running {
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { timeline in
                    drawing(time: 0.6 + timeline.date.timeIntervalSince(epoch)
                        * ThinkingOrbGeometry.speed(state: state, size: size))
                }
            } else {
                drawing(time: 0.6)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
        .onAppear { epoch = .now; visible = true }
        .onDisappear { visible = false }
        .onChange(of: running) { _, isRunning in if isRunning { epoch = .now } }
    }

    private func drawing(time: Double) -> some View {
        let frame = ThinkingOrbGeometry.frame(state: state, size: size, time: time)
        return Canvas { context, _ in
            // Tintable ink: preserves depth and blends with cards, wallpaper and
            // accessory rendering instead of baking a paper/background color in.
            for line in frame.lines {
                var path = Path()
                path.move(to: CGPoint(x: line.x1, y: line.y1))
                path.addLine(to: CGPoint(x: line.x2, y: line.y2))
                context.stroke(path, with: .color(tint(line.x1, time).opacity(ink(line.white, line.alpha))), lineWidth: line.width)
            }
            for dot in frame.dots {
                let rect = CGRect(x: dot.x - dot.radius, y: dot.y - dot.radius, width: dot.radius * 2, height: dot.radius * 2)
                context.fill(Path(ellipseIn: rect), with: .color(tint(dot.x, time).opacity(ink(dot.white, dot.alpha))))
            }
        }
    }

    /// The dot's colour: the single tint, or the flow sampled across the orb and drifting with time.
    private func tint(_ x: Double, _ time: Double) -> Color {
        flow ? ColorFlow.color(at: x / max(1, Double(size)) * 0.6 + time * 0.04) : color
    }

    private func ink(_ white: Double, _ alpha: Double) -> Double {
        (1 - min(1, max(0, white))) * alpha
    }
}
