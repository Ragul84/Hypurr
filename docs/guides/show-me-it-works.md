# Show me it works

When a task finishes, or any time you tap **Show me** in a task's chat, Hypurr gives you
visual proof that the change does what you asked. You don't have to read code.

## What you get

| Project | Proof |
| --- | --- |
| Web app (Next.js, Vite, CRA, Astro, Nuxt, SvelteKit, Angular, Remix, Gatsby) | Hypurr starts the dev server twice, once in the task's safe copy and once in a copy of the code before the task, then captures the pages the change touched. You get a before/after slider and a short clip. It uses Playwright if the project already has it, and headless Chrome otherwise. |
| Android with a phone or emulator attached (`adb devices`) | Hypurr installs the new build, opens the app, and captures a screenshot and a 6-second clip. |
| Android with Roborazzi or Paparazzi | Hypurr records the screenshot tests on both sides and pairs up the screens that changed. |
| Anything else | Hypurr runs the project's tests and gives you real pass/fail counts, plus the diff in plain words. |

Every proof comes with a plain-words summary of what changed.

## Looks right / Not right, fix it

- **Looks right**: Hypurr notes it on the task.
- **Not right, fix it**: you can say what's wrong (this is optional). That goes to the agent as a new
  turn, along with what the proof showed (the pages captured and any failing tests).

## Safety

- Starting servers and browsers runs your project's code, so the first proof for a project asks
  first and shows the command. Your answer is remembered for that project. If your team's rules
  say an admin approves medium-risk requests, only an admin can allow it.
- Everything runs inside the task's safe copy. Pictures and clips are saved in
  `.hypurr/proof/`, which ignores itself in git, so nothing gets committed. The "before" copy is a
  temporary git worktree in that same folder, and it's removed afterwards. Dependencies are shared
  by linking `node_modules`; nothing is installed.
- Every step has a time limit: about 90 s for a server to start, a few minutes per capture or
  test run, and 8 minutes in total. Servers are killed (the whole process group) as soon as
  capture ends.
- Logs are cleaned of secrets with the same redaction rules as "Fix from a log".
- The phone can fetch only plain file names from the proof folder.
