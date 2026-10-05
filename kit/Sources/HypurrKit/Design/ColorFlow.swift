import SwiftUI

/// Hypurr's colour flow: violet → magenta → cyan, looping. The same stops drive the
/// web site, the GTK app and the terminal UI (docs/design/hypurr-design-system.md).
public enum ColorFlow {
    public static let violet: UInt32 = 0xA78BFA
    public static let magenta: UInt32 = 0xF472B6
    public static let cyan: UInt32 = 0x22D3EE
    public static let stops: [UInt32] = [violet, magenta, cyan]
    public static let colors: [Color] = stops.map { Color(hex: $0) }
    /// Text and symbols drawn on top of the flow (readable on all three stops).
    public static let ink = Color(hex: 0x150A33)

    /// The resting gradient: top-leading violet to bottom-trailing cyan.
    public static var linear: LinearGradient {
        LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// A closed ring of the flow, for spinners and rings.
    public static var angular: AngularGradient {
        AngularGradient(colors: colors + [colors[0]], center: .center)
    }

    /// The flow colour `position` of the way around the loop (wraps; any real number).
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

/// An animated colour-flow fill: the gradient axis slowly turns. Static under Reduce Motion.
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
    /// Paints text or symbols with the colour flow (moving unless `animated` is false).
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
