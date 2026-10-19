# Ticket to PR

Tasks → **Ticket to PR**.

1. **The ticket.** Paste a Jira link or key (`SHOP-142`), a GitHub issue link (or `#12` for the chosen project), or the ticket's text from any other tracker. For Azure DevOps or ServiceNow, keys like `AB#4521` or `INC0012345` are picked up from the text. Pick the project folder.
2. **The plan.** The agent reads the ticket and the project's file list, then writes a plain-words plan: what it understood, the files it'll touch (invented paths are dropped, new files are marked), the "done when" checks, and the tests to write. Edit anything, remove files, or ask for a new plan.
3. **The work.** Approving starts a normal task on a safe branch in that folder, with checkpoints, approvals for risky steps, and undo. The agent writes or updates tests and runs them (`cargo test`, `npm test`, `./gradlew test`, … detected from the project).
4. **The review.** A plain summary of the diff, the files with lines added and removed, and an editable pull request title and description. The ticket key goes in the title, and GitHub issues get `Closes #N`.
5. **Uploading is a Needs-you step.** Nothing leaves the computer until you tap **Upload**. Hypurr then pushes the branch (never a protected one) and opens the PR with the GitHub token, `gh`, `glab` (GitLab) or `az` (Azure DevOps), whichever is signed in on the computer. If none is, it uploads the branch and you copy the PR text.
6. **Ticket comment.** A draft you can copy or share. Hypurr never posts it, and Ticket-to-PR tasks don't auto-comment on Jira or auto-open PRs when finished.

## Setup (on the computer)

Open the settings icon on the Ticket to PR screen. Tokens are sent once to your computer and stored there, never on the phone. Only admins can change them.

- **GitHub:** run `gh auth login` on the computer, or paste a fine-grained token with Issues and Pull requests access.
- **Jira:** your site address, login email and an API token from id.atlassian.com → Security → API tokens.
- **GitLab / Azure DevOps:** run `glab auth login` or `az login` on the computer.
- **Other trackers:** paste the ticket text. No setup needed.
