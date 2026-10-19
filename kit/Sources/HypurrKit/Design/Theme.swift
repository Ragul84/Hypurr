import SwiftUI

#if canImport(UIKit)
import UIKit
private typealias PlatformColor = UIColor
#else
import AppKit
private typealias PlatformColor = NSColor
#endif

private extension PlatformColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: alpha
        )
    }
}

public extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    /// Light/dark pair resolved by the system appearance.
    init(light: UInt32, dark: UInt32) {
        #if canImport(UIKit)
        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
        #else
        self.init(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(hex: dark) : NSColor(hex: light)
        })
        #endif
    }
}

/// Hypurr Signal: black and white on a light grey ground, white rounded cards, black pills.
/// Red is the only colour and always means "needs you". Dark = true black, #111 cards.
/// Shared with GTK / Android / web (docs/design/hypurr-design-system.md).
public enum Palette {
    public static let background = Color(light: 0xEDEDEA, dark: 0x000000)
    public static let surface = Color(light: 0xFFFFFF, dark: 0x111111)
    public static let surface2 = Color(light: 0xF2F2EF, dark: 0x1C1C1E)
    public static let bubbleAgent = Color(light: 0xFFFFFF, dark: 0x111111)
    public static let bubbleUser = Color(light: 0x0E0E0E, dark: 0xF5F5F2)
    public static let onBubbleUser = Color(light: 0xFFFFFF, dark: 0x0E0E0E)
    public static let border = Color(light: 0xE0E0DC, dark: 0x242426)
    public static let borderStrong = Color(light: 0xCFCFCA, dark: 0x3A3A3D)
    public static let text = Color(light: 0x0E0E0E, dark: 0xF5F5F2)
    /// 5.65:1 on the ground, 6.63:1 on white; 11.45:1 on #111.
    public static let secondary = Color(light: 0x34343A, dark: 0xC9C9CE)
    public static let tertiary = Color(light: 0x5C5C63, dark: 0x9A9AA1)
    public static let accentFill = Color(light: 0x0E0E0E, dark: 0xF5F5F2)
    public static let accent = Color(light: 0x0E0E0E, dark: 0xF5F5F2)
    public static let onAccent = Color(light: 0xFFFFFF, dark: 0x0E0E0E)
    public static let accentDim = Color(light: 0xF2F2EF, dark: 0x1C1C1E)
    /// Fill red (badges, stop, today's bar). White on it is 5.3:1.
    public static let needs = Color(hex: 0xD21F26)
    /// Red text: 5.09:1 on the light ground, 5.78:1 on #111.
    public static let needsText = Color(light: 0xC41A20, dark: 0xFF4D4F)
    public static let dotOff = Color(light: 0xD6D6D1, dark: 0x2E2E31)
    public static let danger = Color(light: 0xC41A20, dark: 0xFF4D4F)
    public static let warning = Color(light: 0xC41A20, dark: 0xFF4D4F)
    public static let codeBackground = Color(light: 0xF2F2EF, dark: 0x1C1C1E)
    public static let added = Color(light: 0x0E0E0E, dark: 0xF5F5F2)
    public static let removed = Color(light: 0xC41A20, dark: 0xFF4D4F)
}

public enum AvatarPalette {
    public struct Swatch: Identifiable, Hashable, Sendable {
        public let id: String
        public let label: String
        public let hex: UInt32
        public var color: Color { Color(hex: hex) }
    }

    /// Signal avatars are monochrome tones; legacy ids map onto them.
    public static let colors: [Swatch] = [
        .init(id: "black", label: "Ink", hex: 0x0E0E0E),
        .init(id: "asphalt", label: "Graphite", hex: 0x34343A),
        .init(id: "teal", label: "Slate", hex: 0x5C5C63),
        .init(id: "cyan", label: "Steel", hex: 0x7A7A82),
        .init(id: "green", label: "Graphite", hex: 0x3A3A40),
        .init(id: "ink", label: "Ink", hex: 0x0E0E0E),
        .init(id: "gray", label: "Gray", hex: 0x8A8A8F),
        .init(id: "attention", label: "Signal red", hex: 0xD21F26),
        .init(id: "red", label: "Signal red", hex: 0xD21F26),
        .init(id: "brown", label: "Graphite", hex: 0x34343A),
        .init(id: "blue", label: "Graphite", hex: 0x3A3A40),
        .init(id: "violet", label: "Steel", hex: 0x7A7A82),
        .init(id: "magenta", label: "Steel", hex: 0x7A7A82),
        .init(id: "orange", label: "Slate", hex: 0x5C5C63),
        .init(id: "yellow", label: "Slate", hex: 0x5C5C63),
    ]

    public static let shapes = ["blob", "pebble", "squircle", "tablet", "wedge", "hex", "cloud", "teardrop"]

    public static func color(_ id: String) -> Color {
        (colors.first { $0.id == id } ?? colors.first { $0.id == "teal" } ?? colors[0]).color
    }
}

public enum RelativeTime {
    /// Grok-style compact age: now · 5m · 3h · 2d · Sep 3
    public static func short(_ date: Date, now: Date = .now) -> String {
        let s = max(0, now.timeIntervalSince(date))
        switch s {
        case ..<60: return "now"
        case ..<3600: return "\(Int(s / 60))m"
        case ..<86_400: return "\(Int(s / 3600))h"
        case ..<(86_400 * 7): return "\(Int(s / 86_400))d"
        default: return date.formatted(.dateTime.month(.abbreviated).day())
        }
    }

    /// Roster stamp: 3:45 AM · Yesterday · Wednesday · Sep 16
    public static func day(_ date: Date, now: Date = .now) -> String {
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) { return date.formatted(date: .omitted, time: .shortened) }
        if cal.isDateInYesterday(date) { return String(localized: "Yesterday") }
        if now.timeIntervalSince(date) < 6 * 86_400 { return date.formatted(.dateTime.weekday(.wide)) }
        return date.formatted(.dateTime.month(.abbreviated).day())
    }

    /// Chat separator: Today 3:27 AM · Yesterday 5:20 PM · Sep 16 9:02 AM
    public static func separator(_ date: Date) -> String {
        let cal = Calendar.current
        let day = cal.isDateInToday(date) ? String(localized: "Today")
            : cal.isDateInYesterday(date) ? String(localized: "Yesterday")
            : date.formatted(.dateTime.month(.abbreviated).day())
        return day + " " + date.formatted(date: .omitted, time: .shortened)
    }

    public static func until(_ date: Date, now: Date = .now) -> String {
        let s = max(0, date.timeIntervalSince(now))
        let h = Int(s / 3600), m = Int(s.truncatingRemainder(dividingBy: 3600) / 60)
        if h >= 48 { return "\(h / 24)d" }
        if h > 0 { return "\(h)h \(m)m" }
        return "\(m)m"
    }
}

public enum BackendInfo {
    public static func name(_ id: String) -> String {
        switch id {
        case "claude": "Claude Code"
        case "codex": "Codex"
        case "hypurr-agent", "opencode": "Hypurr Agent"
        case "grok": "Grok Build"
        case "gemini": "Gemini CLI"
        default: "Custom agent"
        }
    }
}

