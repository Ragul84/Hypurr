import SwiftUI

/// Hypurr Sunfield signal language (replaces teal ColourFlow).
/// Same symbol name so call sites keep compiling; fills are forest / sunflower only.
public enum ColorFlow {
    public static let signal: UInt32 = 0x0E4A38
    public static let signalDeep: UInt32 = 0x0A3428
    public static let sunflower: UInt32 = 0xF2B90D
    public static let cyan: UInt32 = sunflower
    /// Legacy aliases — resolve to forest / sunflower so no purple remains on screen.
    public static let violet: UInt32 = signal
    public static let magenta: UInt32 = sunflower
    public static let stops: [UInt32] = [sunflower, signal, signalDeep]
    public static let colors: [Color] = stops.map { Color(hex: $0) }
    /// Text and symbols drawn on top of the signal fill.
    public static let ink = Color(hex: 0xFFF8E8)

    /// Resting fill: sunflower → forest (kept as LinearGradient for API compatibility).
    public static var linear: LinearGradient {
        LinearGradient(colors: [Color(hex: sunflower), Color(hex: signal)], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// Closed ring for spinners.
    public static var angular: AngularGradient {
        AngularGradient(colors: [Color(hex: sunflower), Color(hex: signal), Color(hex: sunflower)], center: .center)
    }

    public static func color(at position: Double) -> Color {
        let wrapped = position - position.rounded(.down)
        let p = wrapped * 3
        let i = min(2, max(0, Int(p)))
        let f = p - Double(i)
        let a = stops[i], b = stops[(i + 1) % 3]
        func channel(_ shift: UInt32) -> Double {
            let ca = Double((a >> shift) & 0xFF) / 255, cb = Double((b >> shift) & 0xFF) / 255
            return ca + (cb - ca) * f
        }
        return Color(red: channel(16), green: channel(8), blue: channel(0))
    }
}

/// An animated Sunfield fill. Static under Reduce Motion.
public struct ColorFlowBackground: View {
    let period: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(period: Double = 8) { self.period = period }

    public var body: some View {
        if reduceMotion {
            ColorFlow.linear
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { timeline in
                let turn = timeline.date.timeIntervalSinceReferenceDate / period
                let a = turn * 2 * .pi
                LinearGradient(
                    colors: ColorFlow.colors,
                    startPoint: UnitPoint(x: 0.5 + 0.5 * cos(a), y: 0.5 + 0.5 * sin(a)),
                    endPoint: UnitPoint(x: 0.5 - 0.5 * cos(a), y: 0.5 - 0.5 * sin(a))
                )
            }
        }
    }
}

public extension View {
    func colorFlowForeground(animated: Bool = true) -> some View {
        modifier(ColorFlowForeground(animated: animated))
    }
}

private struct ColorFlowForeground: ViewModifier {
    let animated: Bool

    func body(content: Content) -> some View {
        content
            .overlay {
                Group {
                    if animated { ColorFlowBackground() } else { ColorFlow.linear }
                }
                .mask { content }
                .allowsHitTesting(false)
            }
    }
}
