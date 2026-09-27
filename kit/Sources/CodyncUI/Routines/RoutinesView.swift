import CodyncKit
import SwiftUI

/// The same routine list and details in the Mac sidebar and the phone's modal.
struct RoutinesView: View {
    let botId: String
    var initialId: String?
    var close: (() -> Void)?
    @Environment(BotStore.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var routines: [Routine] = []
    @State private var runs: [RoutineRun] = []
    @State private var selected: String?
    @State private var loaded = false
    @State private var busy = false
    @State private var error: String?
    @State private var confirmDelete = false
    @State private var webhook: RoutineWebhook?
    @State private var showKey = false
    @State private var openRun: ThreadTarget?
    @State private var showSetup = false
    @State private var loadError: String?
    @State private var copied: String?

    private var routine: Routine? { routines.first { $0.id == selected } }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                if selected != nil {
                    IconButton("Back to Routines", systemImage: "chevron.left") { animate { selected = nil; webhook = nil; showKey = false; copied = nil } }
                }
                Text(routine?.name ?? "Routines").font(.system(size: 13, weight: .semibold))
                Spacer(minLength: 0)
                if selected == nil && !routines.isEmpty {
                    IconButton("Set up a routine", systemImage: "plus") { animate { showSetup = true } }
                        .disabled(model.isOffline)
                }
                if let close { IconButton("Close routines", systemImage: "xmark", action: close) }
            }
            if let loadError { Text(loadError).font(.caption).foregroundStyle(Palette.danger) }
            if let error { Text(error).font(.caption).foregroundStyle(.red).textSelection(.enabled) }
            if let routine {
                detail(routine)
            } else if selected != nil && loaded {
                Text("This routine was deleted.").foregroundStyle(Palette.secondary)
            } else {
                listing
            }
        }
        .foregroundStyle(Palette.text)
        .font(.system(size: 13))
        .onChange(of: initialId) { _, id in
            animate { selected = id; webhook = nil; showKey = false; copied = nil }
        }
        .task(id: botId) {
            selected = initialId
            repeat {
                await load()
                do { try await Task.sleep(for: .seconds(3)) } catch { break }
            } while !Task.isCancelled
        }
        .codyncSheet(isPresented: $showSetup) {
            RoutineEditorView(botId: botId, routine: routine) { saved in
                routines.removeAll { $0.id == saved.id }
                routines.append(saved)
                animate { selected = saved.id; showSetup = false; webhook = nil; showKey = false; copied = nil }
                Task { await load() }
            }
            #if os(macOS)
                .frame(width: 540, height: 700)
            #endif
        }
        .codyncDialog("Delete routine?", isPresented: $confirmDelete, message: "This deletes the routine and stops its future runs. This can't be undone.") {
            [DialogAction("Delete routine", destructive: true) {
                if let routine { action("deleteRoutine", routine) }
            }]
        }
        .codyncSheet(item: $openRun) { run in
            RepliesView(botId: botId, rootId: run.id) { animate { openRun = nil } }
                #if os(macOS)
                    .frame(width: 520, height: 580)
                #endif
        }
    }

    private var listing: some View {
        VStack(alignment: .leading, spacing: 10) {
            if routines.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 22, weight: .light))
                        .foregroundStyle(Palette.secondary)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(loaded ? "No routines yet" : "Loading routines…")
                            .font(.system(size: 13, weight: .medium))
                        Text("Schedule a task, repeat it, or run it on an event.")
                            .font(.system(size: 12))
                            .foregroundStyle(Palette.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .lineSpacing(2)
                    }
                    HStack(spacing: 8) {
                        Button("Set up a routine") { animate { showSetup = true } }
                            .buttonStyle(.primary)
                            .disabled(model.isOffline)
                        Spacer(minLength: 0)
                        askInChat
                    }
                    .padding(.top, 2)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
            }
            ForEach(routines) { routine in
                Button { animate { selected = routine.id } } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: routine.enabled ? "clock.arrow.circlepath" : "pause.circle")
                            .foregroundStyle(Palette.secondary)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(routine.name).foregroundStyle(Palette.text)
                            Text(routine.enabled ? routine.triggerDescriptions.joined(separator: " · ") : "Paused")
                                .font(.caption).foregroundStyle(Palette.secondary)
                            Text(stateDescription(routine)).font(.caption).foregroundStyle(Palette.secondary)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.tertiary)
                    }
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if !routines.isEmpty {
                HStack {
                    Spacer()
                    askInChat
                }
            }
        }
    }

    private var askInChat: some View {
        IconButton("Ask in chat", systemImage: "text.bubble") {
            edit("Help me set up a routine. Ask me what task to run and when, then create it with the routines tool. ")
        }
        .disabled(model.isOffline)
    }

    private func detail(_ routine: Routine) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Label(stateDescription(routine), systemImage: routine.enabled ? "clock" : "pause.circle")
                .font(.subheadline.weight(.medium))
            if let error = routine.lastError {
                Text(error).font(.caption).foregroundStyle(.red).textSelection(.enabled)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Instruction").fontWeight(.semibold)
                Text(routine.instruction).textSelection(.enabled)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("When to run").fontWeight(.semibold)
                ForEach(Array(routine.triggerDescriptions.enumerated()), id: \.offset) { _, text in
                    Label(text, systemImage: "clock").foregroundStyle(Palette.secondary)
                }
                if routine.enabled, let next = routine.nextRunAt {
                    Text("Next: \(Date(milliseconds: next).formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption).foregroundStyle(Palette.tertiary)
                }
            }
            HStack(spacing: 16) {
                IconButton(routine.enabled ? "Pause" : "Resume", systemImage: routine.enabled ? "pause" : "play") {
                    action("setRoutineEnabled", routine, enabled: !routine.enabled)
                }
                IconButton("Edit", systemImage: "pencil") { animate { showSetup = true } }
                IconButton("Test run", systemImage: "play.circle") { action("runRoutine", routine) }
                    .disabled(runs.contains { $0.routineId == routine.id && $0.isActive })
                Spacer()
                IconButton("Delete routine", systemImage: "trash") { animate { confirmDelete = true } }
            }
            .disabled(busy || model.isOffline)
            Button("Edit with bot", systemImage: "text.bubble") {
                edit("Help me edit routine \(routine.name) (ID: \(routine.id)). Read its current settings with list_routines and ask what I want to change. Update this routine instead of creating a duplicate. ")
            }
            .buttonStyle(.plain)
            .foregroundStyle(Palette.secondary)
            .disabled(model.isOffline)
            if routine.triggers.contains(where: { $0.type == "webhook" || $0.type == "event" }) {
                VStack(alignment: .leading, spacing: 8) {
                    Button("Webhook connection", systemImage: "link") {
                        Task {
                            do {
                                guard let client = model.client else { return }
                                let value = try await client.routineWebhook(botId: botId, id: routine.id)
                                animate { webhook = value }
                            } catch { self.error = error.localizedDescription }
                        }
                    }.buttonStyle(.plain)
                    if let webhook {
                        HStack {
                            Text("Local endpoint").fontWeight(.semibold)
                            Spacer()
                            IconButton(copied == "url" ? "URL copied" : "Copy URL", systemImage: copied == "url" ? "checkmark" : "doc.on.doc") {
                                Pasteboard.copy(webhook.url)
                                animate { copied = "url" }
                            }
                        }
                        Text(webhook.url).font(.caption.monospaced()).textSelection(.enabled)
                        Text("Send JSON with Authorization: Bearer <key>. This address is local to the host.").font(.caption).foregroundStyle(Palette.secondary)
                        Button(showKey ? "Hide key" : "Show key") { animate { showKey.toggle() } }.buttonStyle(.plain)
                        if showKey {
                            HStack(alignment: .top) {
                                Text(webhook.key).font(.caption.monospaced()).textSelection(.enabled)
                                IconButton(copied == "key" ? "Key copied" : "Copy key", systemImage: copied == "key" ? "checkmark" : "doc.on.doc") {
                                    Pasteboard.copy(webhook.key)
                                    animate { copied = "key" }
                                }
                            }
                        }
                        Text("For external senders, verify a real delivery. Test run only checks the task.")
                            .font(.caption).foregroundStyle(Palette.secondary)
                    }
                }
            }
            let history = runs.filter { $0.routineId == routine.id }
            if history.isEmpty {
                Text("No runs recorded yet. Use Test run to execute this task now, or wait for its trigger.")
                    .font(.caption).foregroundStyle(Palette.secondary)
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Runs").fontWeight(.semibold)
                    ForEach(history) { run in
                        VStack(alignment: .leading, spacing: 3) {
                            HStack {
                                Text(runLabel(run))
                                Spacer()
                                Text(Date(milliseconds: run.createdAt), style: .relative).foregroundStyle(Palette.tertiary)
                                if let root = run.rootId {
                                    IconButton("Open run conversation", systemImage: "text.bubble") { animate { openRun = ThreadTarget(id: root) } }
                                }
                            }
                            if let detail = run.detail { Text(detail).font(.caption).foregroundStyle(Palette.secondary) }
                        }
                    }
                }
            }
        }
    }

    private func runLabel(_ run: RoutineRun) -> String {
        switch run.status {
        case "pending": "Queued"
        case "starting": "Starting"
        case "running": "Running"
        case "recovering": "Resuming after restart"
        case "succeeded": "Completed"
        case "failed": "Failed"
        case "interrupted": "Interrupted"
        case "cancelled": "Cancelled"
        default: run.status.capitalized
        }
    }

    private func stateDescription(_ routine: Routine) -> String {
        let history = runs.filter { $0.routineId == routine.id }
        if let active = history.first(where: \.isActive) { return runLabel(active) }
        if routine.lastError != nil { return "Schedule needs attention" }
        if !routine.enabled { return "Paused" }
        if let last = history.first, ["failed", "interrupted"].contains(last.status) { return "Last run: \(runLabel(last).lowercased())" }
        if let next = routine.nextRunAt {
            return "Next: \(Date(milliseconds: next).formatted(date: .abbreviated, time: .shortened))"
        }
        if routine.triggers.contains(where: { $0.type == "webhook" || $0.type == "event" }) { return "Waiting for an event" }
        return "Schedule completed"
    }

    private func animate(_ change: () -> Void) { withAnimation(Motion.reduced(Motion.layout, reduceMotion), change) }
    private func edit(_ text: String) {
        animate {
            model.routineDrafts[botId] = text
            close?()
        }
    }
    private func action(_ method: String, _ routine: Routine, enabled: Bool? = nil) {
        guard !busy, let client = model.client else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                try await client.routineAction(method, botId: botId, id: routine.id, enabled: enabled)
                if method == "deleteRoutine" { animate { selected = nil; webhook = nil } }
                error = nil
                await load()
            } catch { self.error = error.localizedDescription }
        }
    }
    private func load() async {
        guard let client = model.client else { return }
        do {
            let result = try await client.routines(botId: botId)
            routines = result.routines
            runs = result.runs
            loaded = true
            loadError = nil
        } catch is CancellationError {} catch { self.loadError = error.localizedDescription }
    }
}
