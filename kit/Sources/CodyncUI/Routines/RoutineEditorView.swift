import CodyncKit
import SwiftUI

struct RoutineEditorView: View {
    let botId: String
    let routine: Routine?
    let saved: (Routine) -> Void
    @Environment(BotStore.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var name = ""
    @State private var instruction = ""
    @State private var schedule = RoutineScheduleDraft()
    @State private var timeout = "3600"
    @State private var busy = false
    @State private var error: String?
    @State private var advanced = false

    private var options: [(String, String)] {
        var values = [("interval", "Repeat at an interval"), ("cron", "Calendar schedule"),
                      ("once", "Run once"), ("webhook", "Webhook")]
        if routine != nil { values.insert(("keep", "Keep existing triggers"), at: 0) }
        return values
    }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !model.isOffline && !busy
    }

    var body: some View {
        VStack(spacing: 0) {
            ModalHeader(routine == nil ? "Set up a routine" : "Edit routine")
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 14) {
                        Field("Name") {
                            TextField("Name", text: $name, prompt: Text("e.g. Morning summary").foregroundStyle(Palette.text.opacity(0.7))).routineInput()
                        }
                        Field("Instruction") {
                            TextField("Instruction", text: $instruction, prompt: Text("Describe what this bot should do each time it runs.").foregroundStyle(Palette.text.opacity(0.7)), axis: .vertical)
                                .lineLimit(3...8).routineInput()
                        }
                    }
                    VStack(alignment: .leading, spacing: 14) {
                        Text("When to run").font(.subheadline.weight(.semibold))
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 180), spacing: 8)], spacing: 8) {
                            ForEach(options, id: \.0) { option in triggerChoice(option) }
                        }
                        triggerFields
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Button {
                            withAnimation(Motion.reduced(Motion.layout, reduceMotion)) { advanced.toggle() }
                        } label: {
                            HStack {
                                Text("Run settings").font(.subheadline.weight(.medium))
                                Spacer()
                                Text("Timeout: \(timeout)s").font(.caption).foregroundStyle(Palette.secondary)
                                Image(systemName: advanced ? "chevron.up" : "chevron.down").font(.caption)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityValue(advanced ? "Expanded" : "Collapsed")
                        if advanced {
                            Field("Run timeout (seconds)") { TextField("3600", text: $timeout).routineInput() }
                            Text("Stop a run if it exceeds this limit. Between 1 second and 24 hours.")
                                .font(.caption).foregroundStyle(Palette.secondary)
                        }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .disabled(busy)
            footer
        }
        .font(.system(size: InterfaceMetrics.value(mac: 13, mobile: 16)))
        .foregroundStyle(Palette.text)
        .background(Palette.background)
        .onAppear {
            if let routine {
                name = routine.name
                instruction = routine.instruction
                timeout = String(routine.timeoutSeconds ?? 3600)
                schedule = RoutineScheduleDraft(triggers: routine.triggers)
            }
        }
    }

    private func triggerChoice(_ option: (String, String)) -> some View {
        let selected = schedule.kind == option.0
        let symbols = ["interval": "arrow.clockwise", "cron": "calendar",
                       "once": "clock", "webhook": "link", "keep": "checklist"]
        let details = ["interval": "Repeat at a fixed interval", "cron": "Choose days and times",
                       "once": "At a specific date and time", "webhook": "When an event arrives",
                       "keep": "Preserve the current schedule"]
        return Button {
            withAnimation(Motion.reduced(Motion.layout, reduceMotion)) { schedule.kind = option.0 }
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: symbols[option.0] ?? "clock")
                    .font(.system(size: 15)).frame(width: 20).padding(.top, 2)
                VStack(alignment: .leading, spacing: 4) {
                    Text(option.1).font(.subheadline.weight(.medium))
                    Text(details[option.0] ?? "").font(.caption).foregroundStyle(selected ? Palette.text.opacity(0.75) : Palette.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "checkmark").font(.caption.weight(.semibold)).opacity(selected ? 1 : 0)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 70, alignment: .topLeading)
            .background(selected ? Palette.bubbleUser : Palette.bubbleAgent,
                        in: RoundedRectangle(cornerRadius: 12))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private var triggerFields: some View {
        switch schedule.kind {
        case "interval":
            Field("Repeat every") {
                TextField("Interval", text: $schedule.amount).routineInput()
                SegmentedChoice(selection: $schedule.unit, options: [(1, "Seconds"), (60, "Minutes"), (3600, "Hours"), (86400, "Days")])
            }
        case "cron":
            SegmentedChoice(selection: $schedule.calendarStyle, options: [("daily", "Daily"), ("weekdays", "Weekdays"), ("weekly", "Weekly"), ("custom", "Custom")])
            if schedule.calendarStyle == "custom" {
                Field("Cron expression") {
                    TextField("0 9 * * *", text: $schedule.expression).routineInput().font(.callout.monospaced())
                }
                Text("For example, 0 9 * * * runs every day at 9 AM.")
                    .font(.caption).foregroundStyle(Palette.secondary)
            } else {
                if schedule.calendarStyle == "weekly" {
                    SegmentedChoice(selection: $schedule.weekday, options: [(0, "Sun"), (1, "Mon"), (2, "Tue"), (3, "Wed"), (4, "Thu"), (5, "Fri"), (6, "Sat")])
                }
                DatePicker("At", selection: $schedule.timeOfDay, displayedComponents: .hourAndMinute)
                    .environment(\.timeZone, TimeZone(identifier: "GMT") ?? .current)
                    .padding(12).background(Palette.bubbleAgent, in: RoundedRectangle(cornerRadius: 12))
            }
            Field("Time zone") { TextField("Asia/Taipei", text: $schedule.zone).routineInput() }
        case "once":
            DatePicker("Run at", selection: $schedule.date)
                .padding(12).background(Palette.bubbleAgent, in: RoundedRectangle(cornerRadius: 12))
            Text("Time zone: \(TimeZone.current.identifier)").font(.caption).foregroundStyle(Palette.secondary)
        case "webhook":
            VStack(alignment: .leading, spacing: 6) {
                Label("Local connection", systemImage: "desktopcomputer").font(.subheadline.weight(.medium))
                Text("Save to get a URL and secret key. External services need a public forwarding connection, configured separately.")
                    .font(.caption).foregroundStyle(Palette.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14).background(Palette.bubbleAgent, in: RoundedRectangle(cornerRadius: 12))
        default:
            ForEach(routine?.triggerDescriptions ?? [], id: \.self) {
                Label($0, systemImage: "clock").font(.callout).foregroundStyle(Palette.secondary)
            }
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let error {
                Label(error, systemImage: "exclamationmark.circle")
                    .font(.callout).foregroundStyle(Palette.danger).textSelection(.enabled)
            }
            if model.isOffline {
                Label("Connect to this computer to save your routine.", systemImage: "wifi.slash")
                    .font(.caption).foregroundStyle(Palette.danger)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 16) { executionNote; Spacer(minLength: 0); saveButton }
                VStack(alignment: .leading, spacing: 12) { executionNote; saveButton }
            }
        }
        .padding(20)
        .background(Palette.bubbleAgent)
    }

    private var executionNote: some View {
        VStack(alignment: .leading, spacing: 3) {
            Label("Runs on the bot’s computer", systemImage: "desktopcomputer").font(.caption.weight(.medium))
            Text("Keep the host running and computer awake.")
                .font(.caption).foregroundStyle(Palette.secondary)
        }
    }

    private var saveButton: some View {
        Button(action: save) {
            HStack(spacing: 8) {
                if busy { Spinner() }
                Text(busy ? "Saving…" : routine == nil ? "Create routine" : "Save changes")
                    .fixedSize(horizontal: true, vertical: false)
            }
        }
        .buttonStyle(.primary)
        .disabled(!canSave)
        .keyboardShortcut("s", modifiers: .command)
    }

    private func save() {
        guard let client = model.client, canSave else { return }
        guard let timeoutValue = Int(timeout), (1...86400).contains(timeoutValue) else {
            error = "Timeout must be 1 to 86400 seconds."
            return
        }
        let triggers: [RoutineTrigger]
        do { triggers = try schedule.triggers() }
        catch { self.error = error.localizedDescription; return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let result = try await client.saveRoutine(botId: botId, id: routine?.id, name: name,
                    instruction: instruction, triggers: triggers, timeoutSeconds: timeoutValue)
                saved(result)
            } catch { self.error = error.localizedDescription }
        }
    }
}

private extension View {
    func routineInput() -> some View {
        textFieldStyle(.plain)
            .padding(12)
            .background(Palette.bubbleUser, in: RoundedRectangle(cornerRadius: 12))
    }
}
