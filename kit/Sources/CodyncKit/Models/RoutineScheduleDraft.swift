import Foundation

/// Editable common schedules, with a lossless fallback for compound/event triggers.
public struct RoutineScheduleDraft: Sendable {
    public var kind = "interval"
    public var amount = "1"
    public var unit: Int64 = 3600
    public var calendarStyle = "daily"
    public var weekday = 1
    public var timeOfDay = Date(timeIntervalSince1970: 9 * 3600)
    public var date = Date().addingTimeInterval(3600)
    public var expression = "0 9 * * *"
    public var zone = TimeZone.current.identifier
    public var original: [RoutineTrigger] = []

    public init(triggers: [RoutineTrigger] = []) {
        original = triggers
        guard !triggers.isEmpty else { return }
        guard triggers.count == 1, let trigger = triggers.first else { kind = "keep"; return }
        kind = trigger.type
        switch trigger.type {
        case "interval":
            let seconds = trigger.seconds ?? 3600
            unit = [Int64(86400), 3600, 60, 1].first { seconds % $0 == 0 } ?? 1
            amount = String(seconds / unit)
        case "once": date = Date(timeIntervalSince1970: Double(trigger.at ?? 0) / 1000)
        case "cron":
            expression = trigger.expression ?? ""
            zone = trigger.timeZone ?? TimeZone.current.identifier
            calendarStyle = "custom"
            let fields = expression.split(separator: " ").map(String.init)
            if fields.count == 5, let minute = Int(fields[0]), (0...59).contains(minute),
               let hour = Int(fields[1]), (0...23).contains(hour), fields[2] == "*", fields[3] == "*" {
                timeOfDay = Date(timeIntervalSince1970: Double(hour * 3600 + minute * 60))
                if fields[4] == "*" { calendarStyle = "daily" }
                else if fields[4] == "1-5" { calendarStyle = "weekdays" }
                else if let day = Int(fields[4]), (0...6).contains(day) {
                    weekday = day
                    calendarStyle = "weekly"
                }
            }
        case "webhook": break
        default: kind = "keep"
        }
    }

    public func triggers() throws -> [RoutineTrigger] {
        switch kind {
        case "keep": return original
        case "interval":
            guard let count = Int64(amount), count > 0, [1, 60, 3600, 86400].contains(unit) else {
                throw ValidationError("Enter a positive whole number for the interval.")
            }
            let (seconds, overflow) = count.multipliedReportingOverflow(by: unit)
            guard !overflow, seconds <= 31_536_000 else { throw ValidationError("The interval must be no longer than 365 days.") }
            return [RoutineTrigger(type: "interval", seconds: seconds)]
        case "cron":
            guard TimeZone(identifier: zone) != nil else { throw ValidationError("Enter a valid time zone, such as Asia/Taipei.") }
            var cron = expression
            if calendarStyle != "custom" {
                let seconds = Int(timeOfDay.timeIntervalSince1970)
                let hour = ((seconds / 3600) % 24 + 24) % 24
                let minute = ((seconds / 60) % 60 + 60) % 60
                let days = calendarStyle == "weekly" ? String(weekday) : calendarStyle == "weekdays" ? "1-5" : "*"
                cron = "\(minute) \(hour) * * \(days)"
            }
            return [RoutineTrigger(type: "cron", expression: cron, timeZone: zone)]
        case "once":
            if original.count == 1, original[0].type == "once", let at = original[0].at,
               date == Date(timeIntervalSince1970: Double(at) / 1000) { return original }
            return [RoutineTrigger(type: "once", at: Int64((date.timeIntervalSince1970 * 1000).rounded()))]
        case "webhook": return [RoutineTrigger(type: "webhook")]
        default: throw ValidationError("Choose when this routine should run.")
        }
    }

    private struct ValidationError: LocalizedError {
        let errorDescription: String?
        init(_ message: String) { errorDescription = message }
    }
}
