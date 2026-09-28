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
    @State private var loaded = false
    @State private var preview: RoutineSchedulePreview?
    @State private var previewError: String?
    @State private var checkedSchedule: RoutineScheduleDraft?

    private var options: [(String, String)] {
        var values = [("cron", "Recurring schedule"),
                      ("once", "Run once"), ("webhook", "Webhook")]
        if routine != nil { values.insert(("keep", "Keep existing triggers"), at: 0) }
        if schedule.original.contains(where: { $0.type == "interval" }) {
            values.append(("interval", "Existing interval"))
        }
        return values
    }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !model.isOffline && !busy &&
        loaded && checkedSchedule == schedule && previewError == nil
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
                        if loaded { triggerFields }
                        else { Label("Loading schedule…", systemImage: "clock").foregroundStyle(Palette.secondary) }
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
            .disabled(busy || !loaded)
            footer
        }
        .font(.system(size: InterfaceMetrics.value(mac: 13, mobile: 16)))
        .foregroundStyle(Palette.text)
        .background(Palette.background)
        .task {
            if let routine {
                name = routine.name
                instruction = routine.instruction
                timeout = String(routine.timeoutSeconds ?? 3600)
            }
            await loadSchedule()
        }
        .task(id: schedule) {
            guard loaded else { return }
            do { try await Task.sleep(for: .milliseconds(300)) }
            catch { return }
            await checkSchedule()
        }
    }

    private func checkSchedule() async {
        let requested = schedule
        preview = nil
        previewError = nil
        checkedSchedule = nil
        do {
            guard let client = model.client else { throw URLError(.notConnectedToInternet) }
            let result = try await client.routineSchedule(draft: requested)
            try Task.checkCancellation()
            guard schedule == requested else { return }
            preview = result
            checkedSchedule = requested
        } catch {
            guard !Task.isCancelled, schedule == requested else { return }
            previewError = error.localizedDescription
        }
    }

    private func loadSchedule() async {
        guard let client = model.client else { error = "Connect to this computer to load the schedule."; return }
        do {
            let result = try await client.routineSchedule(triggers: routine?.triggers ?? [], timeZone: TimeZone.current.identifier)
            schedule = result.draft
            preview = result
            checkedSchedule = result.draft
            loaded = true
            error = nil
        } catch { self.error = error.localizedDescription }
    }

    private func selectFrequency(_ style: String) {
        guard style == "custom" else { schedule.calendarStyle = style; return }
        let requested = schedule
        Task {
            do {
                guard let client = model.client else { return }
                let result = try await client.routineSchedule(draft: requested)
                guard requested == schedule else { return }
                schedule.expression = result.triggers.first?.expression ?? ""
                schedule.calendarStyle = style
            } catch { previewError = error.localizedDescription }
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
            Text("This saved interval keeps its original cadence. Choose Recurring schedule to replace it with a clock-based schedule.")
                .font(.caption).foregroundStyle(Palette.secondary)
            Field("Repeat every") {
                TextField("Interval", text: $schedule.amount).routineInput()
                SegmentedChoice(selection: $schedule.unit, options: [(1, "Seconds"), (60, "Minutes"), (3600, "Hours"), (86400, "Days")])
            }
        case "cron":
            RoutineCalendarFields(schedule: $schedule, preview: checkedSchedule == schedule ? preview : nil, previewError: previewError, selectFrequency: selectFrequency)
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
            if previewError != nil {
                Button("Check schedule again") { Task { await checkSchedule() } }
                    .buttonStyle(.plain).disabled(model.isOffline)
            }
            if !loaded, error != nil {
                Button("Retry loading schedule") { Task { await loadSchedule() } }.buttonStyle(.plain)
            }
            if schedule.kind != "cron", let previewError {
                Text(previewError).font(.caption).foregroundStyle(Palette.danger)
            }
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
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let result = try await client.saveRoutine(botId: botId, id: routine?.id, name: name,
                    instruction: instruction, schedule: schedule, timeoutSeconds: timeout)
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
