import HypurrKit
import SwiftUI

/// "Continue in the cloud": choose a cloud, say what to do, get a link.
public struct CloudHandoffView: View {
    let client: HostClient
    let project: String
    let close: () -> Void
    @Environment(\.openURL) private var openURL
    @State private var options: CloudOptions?
    @State private var chosen: String?
    @State private var prompt: String
    @State private var env = ""
    @State private var key = ""
    @State private var busy = false
    @State private var link: URL?
    @State private var error: String?

    public init(client: HostClient, project: String, goal: String, close: @escaping () -> Void) {
        self.client = client
        self.project = project
        self.close = close
        _prompt = State(initialValue: "Continue this task: \(goal)")
    }

    private func usable(_ v: CloudVendor) -> Bool { v.ready || v.id == "cursor" }

    public var body: some View {
        CardForm {
            CardSection("Continue in the cloud",
                        footer: "Runs on the agent maker's cloud with your own account, so your PC can be off. The result comes back as a pull request or a link.") {
                if let link {
                    Text("Started. Follow it there:")
                    Button("Open") { openURL(link) }.buttonStyle(PrimaryButtonStyle())
                } else if let o = options {
                    if o.repo == nil {
                        Text("This folder isn't on GitHub, so a cloud agent can't open it.").foregroundStyle(Palette.needsText)
                    } else if o.unpushed == true {
                        Text("The cloud starts from \(o.branch ?? "your branch") on GitHub. Push your latest changes first.")
                            .font(.caption).foregroundStyle(Palette.needsText)
                    }
                    ForEach(o.vendors) { v in
                        Button {
                            if usable(v) { chosen = v.id; error = nil }
                        } label: {
                            HStack {
                                Text(v.name).foregroundStyle(usable(v) ? Palette.text : Palette.tertiary)
                                Spacer()
                                if chosen == v.id { Image(systemName: "checkmark") } else if !usable(v) { Text("Not set up").font(.caption) }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    if chosen == "codex" && o.codexEnv == nil {
                        TextField("Codex cloud environment name", text: $env).textFieldStyle(.plain).plainTextInput()
                    }
                    let cursorNeedsKey = chosen == "cursor" && o.vendors.first { $0.id == "cursor" }?.ready != true
                    if cursorNeedsKey {
                        Text("Paste a Cursor API key (Cursor dashboard → API Keys). It's saved only on your computer.")
                            .font(.caption).foregroundStyle(Palette.tertiary)
                        SecureField("Cursor API key", text: $key).textFieldStyle(.plain)
                    }
                    TextField("What should it do?", text: $prompt, axis: .vertical).textFieldStyle(.plain)
                    Button(busy ? "Starting…" : "Start in the cloud") { Task { await start(cursorNeedsKey) } }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(busy || chosen == nil || o.repo == nil || prompt.isEmpty || (cursorNeedsKey && key.isEmpty))
                } else {
                    Text("Checking your computer…").foregroundStyle(Palette.secondary)
                }
                if let error { Text(error).font(.caption).foregroundStyle(Palette.danger) }
            }
            Button("Close", action: close)
        }
        .task {
            do {
                let o = try await client.cloudOptions(project: project)
                options = o
                chosen = o.vendors.first(where: \.ready)?.id
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func start(_ saveKey: Bool) async {
        guard let chosen else { return }
        busy = true
        error = nil
        defer { busy = false }
        do {
            if saveKey { try await client.setCursorKey(key.trimmingCharacters(in: .whitespaces)) }
            let url = try await client.cloudHandoff(project: project, vendor: chosen, prompt: prompt,
                                                    codexEnv: env.isEmpty ? options?.codexEnv : env)
            link = URL(string: url)
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// The button above the composer for a task with a project folder.
struct CloudHandoffButton: View {
    let botId: String
    @Environment(BotStore.self) private var model
    @State private var open = false

    var body: some View {
        if let bot = model.bots[botId], let project = bot.task?.project, !project.isEmpty, model.client != nil {
            Button("Continue in the cloud") { open = true }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                .hypurrSheet(isPresented: $open) {
                    if let client = model.client {
                        CloudHandoffView(client: client, project: project, goal: bot.task?.goal ?? bot.name) { open = false }
                            #if os(macOS)
                                .frame(width: 480, height: 560)
                            #endif
                    }
                }
        }
    }
}
