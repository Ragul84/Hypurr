# Fix from a log

Tasks → **Fix from a log**, or share text, a log file or a screenshot into Hypurr ("Fix with Hypurr").

1. **Input**: paste a log, pick an image (its text is read **on the phone** with ML Kit; the image never leaves the device), or pick a recent failing CI run / test output from the project (CI needs `gh` signed in on the computer).
2. **Redaction**: before any agent sees the text, the computer removes private keys, tokens (GitHub, GitLab, OpenAI, Slack, AWS, Google, Stripe, npm, JWT, Bearer), passwords and secrets in `key=value` form, logins inside URLs, emails and IP addresses. The screen shows what it removed.
3. **Explanation** in plain words, written for your role: what broke, the likely cause, where it happens (`file:line` inside the project folder, with a code snippet) and how sure it is. Paths the agent invents are dropped.
4. **Actions**:
   - **Fix it** starts a normal task on the safe branch, with approvals and undo.
   - **Write a ticket** builds a note you can edit, copy or share. Nothing is sent automatically.
   - **Explain more** gives a deeper explanation.

If the agent isn't reachable, Hypurr falls back to the first error line in the log.

Redaction is pattern-based. Check the preview before you share anything unusual.
