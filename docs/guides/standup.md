# Daily standup

Tasks → **Daily standup**. One tap; nothing is ever posted for you.

1. The phone calls `standup {projects, useAgent}` on the computer (the chosen project folder, or every
   known project).
2. The host (`host/src/standup.rs`) gathers everything since the start of the **previous working day**
   (Friday on a Monday) in the computer's local time:
   - your git commits on every branch (`git log --all --author=<git user.email>`), Hypurr's safe
     branches included, de-duplicated by subject;
   - tasks Hypurr ran there, with status, linked GitHub/Jira ticket and pull request;
   - blockers: tasks waiting on your approval.
3. One of your bots writes **Yesterday / Today / Blockers** from those facts only, adapted to your
   role profile (a tester's standup talks about test cases and bugs). If no agent answers in 2 min
   the host returns a plain draft built from the same facts.
4. You edit it on the phone, then **Copy** or **Share** (Android share sheet: Teams, Slack,
   WhatsApp…).

**Reminder:** the toggle creates a weekday routine "Daily standup" (09:30 Asia/Kolkata by default)
via `setStandupReminder`. Its instruction carries `#standup`, so each run gets the gathered facts; the
bot posts the standup in its own chat and the phone gets the usual "done" notification.

**Team view (admins):** `teamStandup` groups everyone's tasks since the last working day by who
started them. It only shows when teammates' phones are paired to this computer (team admin data);
otherwise the host answers `available: false` and the phone hides the Mine/Team switch. Git commits
are not shown per teammate (the host can't tell whose phone made which commit).
