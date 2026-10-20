# Write tests (QA test writer)

Tasks → **Write tests**.

1. **What to test.** Pick **Ticket** (a Jira or GitHub link or key, read the same way as Ticket to PR), **Requirements** (paste them), or **Screen** (a name like "Checkout"). Pick the project folder.
2. **Test cases.** The agent writes 6 to 12 manual cases in plain words: title, steps, expected result, priority (high, medium or low) and type (positive, negative or edge case). Tap a case to edit it. Tap its tags to change the type or priority. You can also delete cases or add your own. **Explain each test** adds a plain "why" under every case. It's on by default when your profile says you're new to QA or a student.
3. **Export.** CSV (opens in Excel or Google Sheets), Jira (wiki text for a description or comment), Xray (CSV, one row per step), TestRail (CSV) or Zephyr Scale (CSV). Copy or share. Hypurr never posts them anywhere.
4. **Automate (optional).** Hypurr detects the frameworks the project already uses: JUnit, Espresso, pytest, Jest, Playwright, Cypress, or Selenium/TestNG. Tick cases (or **Pick suggested**), choose a framework and tap **Automate**. A normal task on a safe branch, with approvals and undo, writes the tests with the case id in each test's name.
5. **Result.** **Run the tests** makes the computer run the framework itself (up to 15 minutes) and shows passed and failed counts in plain words, with the last lines of output.
6. **Coverage.** Each case shows **Automated** if its id appears in the task's changed test files, and **Manual** otherwise. The Automated column in the export follows this.

Espresso, Playwright, Cypress and Selenium need an emulator, phone or browser available on the computer.
