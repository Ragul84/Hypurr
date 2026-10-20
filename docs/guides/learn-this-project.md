# Learn this project, and learning mode

## Learn this project (Tasks → Learn this project)

Pick a project folder, then:

- **Project guide**: what it does, how to run and test it, a map of the code, key files,
  where to start for common changes, gotchas, and people and owners from git history.
- **Handover**: recent work, open branches, pending tasks, risky areas (files changed most
  in 90 days), how to deploy, who to ask.
- **Ask**: a quick question; the answer cites `path:line` and lists the matching lines.

How it stays safe and grounded:

- **Read-only.** The host reads the folder (skipping `node_modules`, `target`, `build`, …)
  and runs read-only git commands (`log`, `shortlog`, `for-each-ref`). The agent gets those
  facts with no tools and runs in an empty folder, so it cannot change anything.
- **Grounded paths.** Every path in backticks or a link is checked against the folder.
  Made-up paths are dropped (a bullet that only names one is removed), and the app says how
  many were removed. Without an agent, the doc is built from the facts alone.
- **Adapted to the profile** (role, experience) the user set under You.
- **Editable**; shared only through the share sheet (Markdown or PDF) or Copy.
- **Save to project** starts a normal task on its own safe branch that writes only
  `docs/HYPURR_GUIDE.md`; approvals, protected branches and review work as for any task.

Host methods: `projectGuide {project, kind}`, `projectAsk {project, question}`,
`projectGuideSave {project, markdown}` (checked like `startTask`, audited).

## Learning mode (You → Learning)

On by default for experience **New** or role **Student**; anyone can switch it. A task
started with it on asks the agent to explain each step simply and to name ideas with
`Teach me:` lines. In the task's chat:

- **Teach me this** cards: an idea in simple words with a real line from the user's own
  changed code (`learnCards {taskId}`), plus ideas the agent named.
- **Check yourself** after the task: 3 multiple-choice questions (`learnQuiz {taskId}`),
  written by the agent from the task, or built from the changed files if it can't.

It never holds work up: nothing waits on the cards or quiz, and approvals and the safety
net are unchanged. "Hide" removes the panel for that task.
