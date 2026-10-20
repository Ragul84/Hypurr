# Trace a click and Page map

For testers and developers who've been given a project they didn't write (often an AI-built one)
and need to answer two questions fast:

- **"What happens when I click this button?"** Which handler runs, which API calls go out, what they
  send and get back, and which frontend line and backend route are involved.
- **"Where is everything?"** Every page, its buttons, the APIs behind them, and the files that hold them.

Open it from **Tasks → Trace and map** on the phone.

## How it fits a normal workday

You have a local checkout of the company's private repo, you work in Cursor or VS Code, and your
tasks come from Jira or Azure Boards. The app runs on localhost or a staging site with test logins.

- **Nothing to install.** Hypurr uses the Chrome or Edge already on the laptop, so you don't need admin rights.
  It never downloads a browser or Playwright.
- **Nothing leaves the laptop.** The browser profile is a throw-away folder inside the project's
  `.hypurr/trace/` (git-ignored). No code or traffic is uploaded, and no model is called. Trace
  and map are plain code reading plus the browser.
- **Never production.** You can trace localhost, private network addresses and hosts that look like
  staging (`staging`, `uat`, `qa`, `dev`, `test`, `preprod`, `sandbox`, `sit` in the sub-domain).
  Hosts that look like production (`prod`, `production`, `live`) are refused, even if someone approves them.
  Any other host needs its own approval, per project.
- **Asked once per project.** The first trace in a project shows a "Needs you" card. Approving it is
  remembered for that project only.
- **Requests are checked too.** While recording, every request the page makes is checked against the same
  rule. Calls to hosts that aren't allowed (analytics, a production API hard-coded somewhere) are
  blocked in the browser and listed as "Blocked" in the trace.
- **Everything is a draft.** The trace, the page map, the Playwright test and the restructure branch
  all stay on the laptop until you choose to use them. Nothing is posted or opened on its own.

## Trace a click

1. Enter the page to open, e.g. `http://localhost:5173/orders`.
2. Choose how to click:
   - **Leave "Buttons to click" empty.** A browser window opens on the laptop. Click what you want, then tap **Stop** on the phone or close the window.
   - **Type button words, one per line** (e.g. `Place order`). Hypurr clicks them in a hidden browser, which is handy from the phone.
3. Hypurr records each click:
   - **Target:** the element you clicked, with its words, tag and a selector.
   - **Handler:** the React `onClick`/`onSubmit` handler, or the DOM listener, with its source position.
   - **API calls:** every fetch/XHR call within a few seconds of the click, with method, URL, status, time, request and response bodies.
   - **Page text:** text that appeared on the page afterwards.
4. Each position is mapped back to the repo:
   - **Source map:** used when the dev server serves one (webpack, Vite and most others do in dev mode).
   - **Browser path:** used when the script is served as-is. Vite serves `/src/api.js` like that.
   - **Code search:** the fallback. It finds the `fetch`/`axios` call whose URL matches, or the handler's declaration by name.
5. Each call is matched to the **backend route** when the backend is in the repo:
   - Express (`app.get`, `router.post`, prefixes from `app.use('/api', router)`)
   - Next.js API routes (`pages/api/**` and `app/**/route.ts`)
   - FastAPI (including `APIRouter(prefix=...)`) and Flask
   - Spring (`@RequestMapping` on the class plus `@GetMapping`/`@PostMapping`...)
   - Django (`urls.py`, including `include()` prefixes)

The result is a plain-words chain, for example:

> You clicked "Place order" on /orders.
> The click runs handlePlaceOrder in client/src/pages/Orders.jsx:9.
> It calls POST /api/orders → 201 Created in 84 ms. Sent by createOrder in client/src/api.js:9.
> Handled by Express POST /api/orders in server/routes/orders.js:7 (placeOrder).
> Then the page shows "Order 41 placed".

Tap a call to see what was sent and received.

### Redaction

Before anything is saved:

- **Headers:** `Authorization`, cookies, `Set-Cookie`, API keys and session headers are replaced with `[redacted]`.
- **Body fields:** fields named like passwords, tokens, OTPs, PINs, card numbers, Aadhaar, PAN, emails, phone numbers, addresses or names are masked, and so are matching query parameters.
- **Free text:** emails, Indian mobile numbers, Aadhaar and PAN numbers, JWTs, and the usual cloud keys are masked.
- **Size:** bodies are cut at 4,000 characters.

### Files

- `.hypurr/trace/<id>/trace.json`: everything, redacted
- `.hypurr/trace/<id>/trace.md`: the chain plus each call's details
- `.hypurr/trace/<id>/flow.spec.js`: written only when you tap **Playwright test**

### Playwright test export

**Playwright test** turns the flow into a test that checks the API calls as well as the page:

```js
const call1_0 = page.waitForResponse((r) => r.url().includes("/api/orders") && r.request().method() === "POST");
await page.getByRole("button", { name: "Place order" }).click();
const res1_0 = await call1_0;
expect(res1_0.status()).toBe(201);
expect(res1_0.request().postDataJSON()).toEqual({ "sku": "SOCK-RED", "qty": 2 });
await expect(page.getByText("Order 41 placed")).toBeVisible();
```

It reads `BASE_URL` from the environment, so the same test can run against staging. Bodies that contained redacted values aren't asserted on. Review the test before you add it to your suite; copy or share it from the phone.

## Page map

**Make the page map** reads the project; nothing runs. It writes:

- `.hypurr/map/map.json` and `.hypurr/map/map.md`: every page with its route path and files,
  - its buttons, links, forms and inputs, with their handlers and lines
  - its API calls, including the ones made through helpers such as `createOrder` in `api.js`, each linked to its backend route
- **Backend routes no page calls:** often dead code, or used by another app.

Pages come from React Router (`<Route path element>`, `createBrowserRouter`, or route objects), Next.js file routing, or folders named `pages/`, `views/` or `screens/` as a fallback.

## Restructure plan

The page map also suggests a standard layout for the frontend: pages in `pages/`, shared components in `components/`, API helpers in `api/` and hooks in `hooks/`. It's saved as `.hypurr/map/restructure-plan.json`.

- **A plan only.** Nothing moves until you tap **Apply on a new branch** and confirm on the "Needs you" card.
- **How applying works:**
  1. Hypurr makes `hypurr/restructure-<time>` in its own copy (a git worktree). Your checkout and uncommitted work aren't touched.
  2. It moves the files with `git mv` and fixes every relative import.
  3. It commits the change.
  4. It runs the project's tests, using your installed `node_modules` linked into the copy, so nothing is downloaded. The run is capped at 8 minutes.
- **If the tests pass:** you're offered **Open pull request**. Tapping it uploads the branch to your remote and opens the PR (GitHub when set up in Integrations). If you never tap it, nothing leaves the laptop.
- **If the tests fail:** you see the output and can **Discard** the branch.
- **What it won't do:** Next.js pages aren't moved, because their folders are their routes. Pages that call APIs directly are flagged under "Worth doing by hand" rather than rewritten.

## Limits

- **Handlers:** found by React props or DOM listeners. Vue, Angular and Svelte handlers fall back to the code search by name, and anonymous inline handlers may show as "couldn't see the click handler".
- **Code search:** reads URL literals. Calls built entirely at runtime (e.g. `api[method](url)`) can't be matched without a source map.
- **Backend matching:** reads route declarations. Routes built in loops or registered from config aren't found.
- **Imports:** the restructure fixes relative imports only. Path aliases (`@/api`) are left as they are.
