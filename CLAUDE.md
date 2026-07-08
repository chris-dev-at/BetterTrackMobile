# BetterTrack App — project instructions

This is the **official BetterTrack Android app** (Kotlin/Compose, package `at.bettertrack.app`), built milestone-by-milestone from the contract in `HandOffPlan.md`.

**If you are coordinating this build (default mode): read `OPUS_COORDINATOR_HANDOFF.md` in this directory FIRST and follow it.** It contains the full operating playbook, current state, credentials-handling rules, device rules, design system, API truths, and the remaining milestone plan. Live progress lives in `docs/TODO.md`; live project facts live in the auto-memory directory.

Non-negotiables (duplicated from the handoff for safety):
- Coordinator mode: plan/dispatch/verify/report — implementation goes to builder subagents (`model: "opus"`).
- All reports to the owner (Christian) go in the user-facing chat text, always.
- Never write the test-account credentials into any file, log, or commit.
- No `git init`/commits unless Christian asks; no AI-attribution text anywhere.
- Every milestone leaves the installed app fully working, logged out at the login screen, phone stay-awake restored, test data cleaned from the production account.
- The server is the only calculator; never invent API endpoints — stub behind a flag, note in `docs/TODO.md`, and tell Christian.
