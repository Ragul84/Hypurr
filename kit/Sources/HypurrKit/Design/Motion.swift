import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// Hypurr motion, after Material 3 Expressive's motion physics: *spatial* springs
/// (position, size, shape) overshoot a little; *effects* (colour, opacity) never do.
/// The web site and GTK app use the same values. With Reduce Motion on every
/// duration drops to 0; use `Motion.reduced(_:)` for that.
public enum Motion {
    // MARK: Expressive spring tokens (stiffness / damping ratio, mass 1)

    /// Small things that move a short way (switch knob, chips, press): 1400 / 0.6.
    public static let spatialFast = Animation.interpolatingSpring(mass: 1, stiffness: 1400, damping: 44.9)
    /// Default for movement and resizing: 380 / 0.8.
    public static let spatialDefault = Animation.interpolatingSpring(mass: 1, stiffness: 380, damping: 31.2)
    /// Big surfaces (sheets, panels): 200 / 0.8.
    public static let spatialSlow = Animation.interpolatingSpring(mass: 1, stiffness: 200, damping: 22.6)
    /// Colour and opacity: critically damped, 1600 / 1.0.
    public static let effects = Animation.interpolatingSpring(mass: 1, stiffness: 1600, damping: 80)
    /// A playful pop for confirmations and new badges.
    public static let bouncy = Animation.spring(duration: 0.45, bounce: 0.38)

    // MARK: Roles used across the apps

    /// Hover and selection fills, tabs, chips: an effects spring.
    public static let hover = effects
    /// A view switched in (the transcript on a new selection).
    public static let fade = Animation.timingCurve(0.2, 0, 0, 1, duration: 0.2)
    /// Press feedback: a quick spatial spring that settles with a tiny bounce.
    public static let press = spatialFast
    /// Icon and glyph swaps, tab thumbs, segmented choices: springy morph.
    public static let morph = Animation.spring(duration: 0.38, bounce: 0.28)
    public static let morphCurve = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0.2, y: 0), endControlPoint: UnitPoint(x: 0, y: 1))
    /// Size and layout changes (panels growing, cards resizing).
    public static let layout = Animation.spring(duration: 0.36, bounce: 0.12)
    /// A new chat message and its scroll.
    public static let conversation = Animation.spring(duration: 0.45, bounce: 0.18)
    /// Tiles moving into place.
    public static let tile = spatialDefault

    /// How far a pressed control shrinks.
    public static let pressScale: CGFloat = 0.96

    public static func reduced(_ animation: Animation, _ reduce: Bool) -> Animation? {
        reduce ? nil : animation
    }

    /// Runs a model change outside any view (connection state, account lists) with `layout`,
    /// so every screen showing it cross-fades instead of jumping. Honors Reduce Motion.
    @MainActor public static func animate(_ change: () -> Void) {
        #if os(iOS)
        let reduce = UIAccessibility.isReduceMotionEnabled
        #elseif os(macOS)
        let reduce = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        #else
        let reduce = false
        #endif
        withAnimation(reduced(layout, reduce), change)
    }
}

/// Press: shrink to 96% on a fast spatial spring, spring back.
public struct PressScale: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? Motion.pressScale : 1)
            .animation(Motion.press, value: configuration.isPressed)
    }
}
