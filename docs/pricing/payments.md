# Plans and payments

Prices live in one place: [`pricing/plans.json`](../../pricing/plans.json). The host,
the Android app (through the host's `pricing` method) and the website read it. Do not
quote prices anywhere else.

## Payments: not live

There are no real keys in the repo. `payments.provider` is `razorpay`, with empty ids;
`ready` is `false` until keys are configured, and the app shows “Billing isn't set up yet”.

**Razorpay** is the usual choice in India: UPI, RuPay, netbanking, UPI AutoPay for
subscriptions, GST invoices. Needs: a Razorpay business account (KYC, PAN, bank),
`key_id` (public, can go in `plans.json`), `key_secret` and webhook secret (server
only, never in the repo or the app), and a Plan id per paid plan.

**Stripe** works for cards from abroad. Needs: publishable key, secret key (server only),
webhook secret, a Price id per plan. Indian accounts need export or RBI e-mandate setup.

A small billing server (or the Hypurr cloud) must create subscriptions and verify
webhooks; the host never holds a secret key.

## GST

Prices in `plans.json` are shown with the `gstNote`. Before charging: decide whether
prices include 18% GST, add the GSTIN to invoices, and replace the note.
