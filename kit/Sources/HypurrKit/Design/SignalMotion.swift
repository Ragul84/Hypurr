import SwiftUI

/// Wet-asphalt neon motion moments — see docs/design/hypurr-motion.md.
/// Core tokens stay in `Motion`; this file adds signal-language views for CI mac builds.
public enum SignalMotion {
    public static let strikeMs: Double = 0.12
    public static let snapMs: Double = 0.18
    public static let settleMs: Double = 0.28
    public static let drawMs: Double = 0.42
    public static let loopMs: Double = 1.6
    public static let reducedMs: Double = 0.15
}

/// Thinking: thin cyan scan band sweeping a rule + optional phase label.
public struct ScanLineView: View {
    public var phase: String = ""
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var x: CGFloat = 0

    public init(phase: String = "") { self.phase = phase }

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle().fill(Palette.border).frame(height: 1)
                    // Flat colour-step trail (no glow blobs)
                    ForEach([0.18, 0.40, 0.95], id: \.self) { a in
                        Rectangle()
                            .fill(Palette.accentFill.opacity(reduceMotion ? a * 0.35 : a))
                            .frame(width: geo.size.width * (0.28 - Double(a) * 0.08), height: 3)
                            .offset(x: reduceMotion
                                ? geo.size.width * 0.35
                                : x * (geo.size.width + geo.size.width * 0.28) - geo.size.width * 0.28
                                    - geo.size.width * (0.12 * (1 - a)))
                    }
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
            .frame(height: 4)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.linear(duration: SignalMotion.loopMs).repeatForever(autoreverses: false)) {
                    x = 1
                }
            }
            if !phase.isEmpty {
                Text(phase)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(Palette.accent)
            }
        }
    }
}

/// 4px cyan signal band.
public struct SignalBandView: View {
    public var opacity: Double = 1
    public init(opacity: Double = 1) { self.opacity = opacity }
    public var body: some View {
        Rectangle().fill(Palette.accentFill.opacity(opacity)).frame(height: 4)
    }
}

/// Rain-streak shimmer overlay for skeletons.
public struct RainShimmerModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drift: CGFloat = 0

    public func body(content: Content) -> some View {
        content.overlay {
            GeometryReader { geo in
                Canvas { ctx, size in
                    let step: CGFloat = 18
                    var x: CGFloat = -size.height
                    let off = reduceMotion ? 0 : drift
                    while x < size.width + size.height {
                        var path = Path()
                        path.move(to: CGPoint(x: x + off, y: 0))
                        path.addLine(to: CGPoint(x: x + off + size.height * 0.35, y: size.height))
                        ctx.stroke(path, with: .color(Palette.accentFill.opacity(0.14)), lineWidth: 1)
                        x += step
                    }
                }
                .onAppear {
                    guard !reduceMotion else { return }
                    withAnimation(.linear(duration: 2.4).repeatForever(autoreverses: false)) {
                        drift = 18
                    }
                }
            }
            .allowsHitTesting(false)
        }
    }
}

public extension View {
    func rainShimmer() -> some View { modifier(RainShimmerModifier()) }
}
