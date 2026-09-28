import CodyncKit
import SwiftUI

/// Calendar controls compile to the same cron trigger the host executes.
struct RoutineCalendarFields: View {
    @Binding var schedule: RoutineScheduleDraft
    let preview: RoutineSchedulePreview?
    let previewError: String?
    let selectFrequency: (String) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showExpression = false

    private let frequencies = [("minutes", "Every few minutes"), ("hourly", "Hourly"),
        ("daily", "Daily"), ("weekdays", "Weekdays"), ("weekly", "Weekly"),
        ("days", "Selected days"), ("monthly", "Monthly"), ("custom", "Custom cron")]
    private let days = [(0, "Sun"), (1, "Mon"), (2, "Tue"), (3, "Wed"),
        (4, "Thu"), (5, "Fri"), (6, "Sat")]

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Field("Frequency") {
                ChoicePicker(selection: Binding(
                    get: { schedule.calendarStyle },
                    set: selectFrequency
                ), options: frequencies, fitsAvailableWidth: true)
            }
            if schedule.calendarStyle == "custom" {
                Field("Cron expression") {
                    TextField("0 9 * * 1-5", text: $schedule.expression)
                        .textFieldStyle(.plain).font(.body.monospaced())
                        .padding(12).background(Palette.bubbleUser, in: RoundedRectangle(cornerRadius: 12))
                }
                Text("Minute · Hour · Day of month · Month · Weekday")
                    .font(.caption).foregroundStyle(Palette.secondary)
                Text("Example: 0 9 * * 1-5 runs at 09:00, Monday through Friday.")
                    .font(.caption).foregroundStyle(Palette.secondary)
            } else if schedule.calendarStyle == "minutes" {
                Field("Repeat every") {
                    ChoicePicker(selection: $schedule.minuteStep,
                                 options: [1, 5, 10, 15, 20, 30].map { ($0, "\($0) min") })
                }
            } else {
                if schedule.calendarStyle == "weekly" || schedule.calendarStyle == "days" {
                    weekdayChoices
                }
                if schedule.calendarStyle == "monthly" {
                    Field("Day of month") {
                        ChoicePicker(selection: $schedule.monthDay, options: (1...31).map { ($0, "Day \($0)") })
                    }
                    if let warning = preview?.warning {
                        Text(warning)
                            .font(.caption).foregroundStyle(Palette.secondary)
                    }
                }
                clockChoices
            }
            Field("Time zone") {
                TextField("Asia/Taipei", text: $schedule.zone)
                    .textFieldStyle(.plain).padding(12)
                    .background(Palette.bubbleUser, in: RoundedRectangle(cornerRadius: 12))
                Button("Use this device’s time zone") { schedule.zone = TimeZone.current.identifier }
                    .buttonStyle(.plain).font(.caption).foregroundStyle(Palette.secondary)
            }
            summary
        }
        .animation(Motion.reduced(Motion.layout, reduceMotion), value: schedule.calendarStyle)
        .animation(Motion.reduced(Motion.layout, reduceMotion), value: schedule.monthDay)
    }

    private var weekdayChoices: some View {
        Field(schedule.calendarStyle == "weekly" ? "Run on" : "Choose days") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 56), spacing: 6)], spacing: 6) {
                ForEach(days, id: \.0) { day in
                    let selected = schedule.calendarStyle == "weekly" ? schedule.weekday == day.0 : schedule.selectedDays.contains(day.0)
                    Button {
                        withAnimation(Motion.reduced(Motion.morph, reduceMotion)) {
                            if schedule.calendarStyle == "weekly" { schedule.weekday = day.0 }
                            else if selected { schedule.selectedDays.remove(day.0) }
                            else { schedule.selectedDays.insert(day.0) }
                        }
                    } label: {
                        Text(day.1).font(.callout.weight(selected ? .semibold : .regular))
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(selected ? Palette.bubbleUser : Palette.bubbleAgent,
                                        in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                    .accessibilityLabel(Calendar.current.weekdaySymbols[day.0])
                }
            }
        }
    }

    private var clockChoices: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(schedule.calendarStyle == "hourly" ? "At minute" : "Time · 24-hour clock")
                .font(.caption.weight(.medium)).foregroundStyle(Palette.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                if schedule.calendarStyle != "hourly" {
                    ChoicePicker(selection: $schedule.hour, options: (0...23).map { ($0, String(format: "%02d", $0)) })
                        .accessibilityLabel("Hour")
                        .accessibilityValue(String(format: "%02d", schedule.hour))
                    Text(":").font(.title2).foregroundStyle(Palette.secondary)
                }
                ChoicePicker(selection: $schedule.minute, options: (0...59).map { ($0, String(format: "%02d", $0)) })
                    .accessibilityLabel("Minute")
                    .accessibilityValue(String(format: "%02d", schedule.minute))
                Spacer(minLength: 0)
                Image(systemName: "clock").foregroundStyle(Palette.secondary).accessibilityHidden(true)
            }
            .monospacedDigit()
            if schedule.calendarStyle != "hourly" {
                HStack(spacing: 8) {
                    ForEach([9, 12, 18], id: \.self) { hour in
                        Button(String(format: "%02d:00", hour)) {
                            schedule.hour = hour
                            schedule.minute = 0
                        }
                        .buttonStyle(.plain).font(.caption.monospacedDigit())
                        .padding(.horizontal, 12).frame(minHeight: 44)
                        .background(Palette.bubbleUser, in: Capsule())
                        .accessibilityLabel("Set time to \(hour):00")
                    }
                }
            }
        }
        .padding(16).background(Palette.bubbleAgent, in: RoundedRectangle(cornerRadius: 14))
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let error = previewError {
                Label(error, systemImage: "exclamationmark.circle")
                    .font(.caption).foregroundStyle(Palette.danger)
            } else {
                Label(preview?.summary ?? "Checking schedule…", systemImage: "calendar.badge.clock")
                    .font(.callout.weight(.medium))
                if let next = preview?.nextRunAt {
                    Text("Next: \(Date(timeIntervalSince1970: Double(next) / 1000).formatted(date: .abbreviated, time: .shortened)) · device time")
                        .font(.caption).foregroundStyle(Palette.secondary)
                }
                if schedule.calendarStyle != "custom" {
                    Button {
                        withAnimation(Motion.reduced(Motion.layout, reduceMotion)) { showExpression.toggle() }
                    } label: {
                        Label("Cron expression", systemImage: showExpression ? "chevron.up" : "chevron.down")
                            .font(.caption)
                    }
                    .buttonStyle(.plain).foregroundStyle(Palette.secondary)
                    .accessibilityValue(showExpression ? "Expanded" : "Collapsed")
                    if showExpression, let expression = preview?.triggers.first?.expression {
                        Text(expression).font(.callout.monospaced()).textSelection(.enabled)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

}
