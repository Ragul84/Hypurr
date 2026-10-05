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

/// Hypurr Sunfield Panel: sunflower ground, cream plates, forest primary.
/// Dark = deep forest + sunflower accents (never black+teal).
/// Shared with GTK / Android / web (docs/design/hypurr-design-system.md).
public enum Palette {
    public static let background = Color(light: 0xF2B90D, dark: 0x0E4A38)
    public static let surface = Color(light: 0xFFF8E8, dark: 0x143D30)
    public static let bubbleAgent = Color(light: 0xFFF8E8, dark: 0x1A4A3A)
    public static let bubbleUser = Color(light: 0x17140A, dark: 0xF2B90D)
    public static let border = Color(light: 0x1F17140A, dark: 0x1FFFF8E8)
    public static let text = Color(light: 0x17140A, dark: 0xFFF8E8)
    public static let secondary = Color(light: 0x5C5640, dark: 0xC8E0D4)
    public static let tertiary = Color(light: 0x8A8168, dark: 0x7A9E8E)
    public static let accentFill = Color(light: 0x0E4A38, dark: 0xF2B90D)
    public static let accent = Color(light: 0x0E4A38, dark: 0xF2B90D)
    public static let onAccent = Color(light: 0xFFF8E8, dark: 0x0E4A38)
    public static let accentDim = Color(light: 0xFFE7A8, dark: 0x1A4A3A)
    public static let danger = Color(light: 0xA1281C, dark: 0xF5A090)
    public static let warning = Color(light: 0x0E4A38, dark: 0xF2B90D)
    public static let codeBackground = Color(light: 0xFFE7A8, dark: 0x0A3428)
    public static let added = Color(light: 0x0E4A38, dark: 0x5DDB9A)
    public static let removed = Color(light: 0xA1281C, dark: 0xF5A090)
}

public enum AvatarPalette {
    public struct Swatch: Identifiable, Hashable, Sendable {
        public let id: String
        public let label: String
        public let hex: UInt32
        public var color: Color { Color(hex: hex) }
    }

    public static let colors: [Swatch] = [
        .init(id: "black", label: "Ink", hex: 0x17140A),
        .init(id: "asphalt", label: "Forest", hex: 0x0E4A38),
        .init(id: "teal", label: "Sunflower", hex: 0xF2B90D),
        .init(id: "cyan", label: "Cream", hex: 0xFFE7A8),
        .init(id: "green", label: "Forest", hex: 0x0E4A38),
        .init(id: "ink", label: "Ink", hex: 0x17140A),
        .init(id: "gray", label: "Warm gray", hex: 0x5C5640),
        .init(id: "attention", label: "Attention", hex: 0xFFB020),
        .init(id: "red", label: "Red", hex: 0xFF6B5A),
        .init(id: "brown", label: "Brown", hex: 0x5A4A3A),
        .init(id: "blue", label: "Blue", hex: 0x2A6B66),
        // Legacy ids remap to asphalt/teal neutrals (no confetti violet/magenta brand).
        .init(id: "violet", label: "Ink", hex: 0x3D5552),
        .init(id: "magenta", label: "Mist", hex: 0x7FA8A3),
        .init(id: "orange", label: "Attention", hex: 0xFFB020),
        .init(id: "yellow", label: "Gold", hex: 0xC9A227),
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
        case "opencode": "OpenCode"
        case "grok": "Grok Build"
        case "gemini": "Gemini CLI"
        default: "Custom agent"
        }
    }
}
