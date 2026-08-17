---
name: orchestrate
description: Drive one pass of SeatVault's task loop, spawning builder, test-runner, code-reviewer, and docs-writer for each task in loop/PLAN.md until the milestone's tasks are exhausted or a task gets stuck. Use when the user wants to implement the next planned task(s), or says "/orchestrate".
---

Run in the main session. Write no feature code directly — every code change goes through `builder` or `implementer`. Never scheduled or backgrounded: this is a single foreground invocation that processes tasks until done or blocked, then returns control to the user.

## 1. Resume or start

Read `loop/PLAN.md`, `loop/LESSONS.md` (in full — you are the one who slices it for each agent below), and the tail of `loop/STATE.md` (last ~20 entries).

Every lesson in `loop/LESSONS.md` carries audience tags (`[planning]`, `[builder]`, `[reviewer]`, `[docs]`). Each spawn below gets only the entries tagged for it, verbatim, and never the whole file — a builder reading review-only or planning-only lessons is paying attention tax on constraints it cannot act on. Skip entries marked `RETIRED` entirely: their content is already permanent instruction text somewhere in `.claude/`, and passing them along re-introduces the duplication retirement removed.

If `loop/HANDOFF.md` has content newer than the last `STATE.md` entry, read it and resume from the task/stage it describes instead of restarting from `loop/PLAN.md`'s first unchecked task.

If `loop/PLAN.md` has no milestone loaded or no tasks, stop and tell the user to run `/plan-milestone` first.

## 2. Pick the next task

Take the next unchecked (`- [ ]`) task in `loop/PLAN.md` order. Read its full packet at `loop/tasks/T-00X.md`.

## 3. Build

Spawn the `builder` agent with: the task packet, the ADR numbers it references, and the `[builder]`-tagged entries from `loop/LESSONS.md`, quoted verbatim.

## 4. Test

Spawn `test-runner` on `builder`'s (or `implementer`'s) output.

- **Pass** → go to step 5.
- **Fail** → track a per-task failure counter for this task:
  - **1st failure**: respawn `builder` with the task packet plus `test-runner`'s failure digest appended. Return to step 4.
  - **2nd consecutive failure**: escalate to `implementer` (opus), passing the full history — both of `builder`'s diffs and both failure digests — plus the same `[builder]`-tagged lesson slice. Return to step 4 with `implementer`'s output.
  - **3rd failure (i.e. implementer also failed)**: stop working this task. Append a "task blocked" entry to `loop/STATE.md` (task id, failure history summary). Return control to the user — do not retry further.

## 5. Review

Spawn `code-reviewer` on the diff accumulated for this task so far (`git diff`), along with the `[reviewer]`-tagged entries from `loop/LESSONS.md` and a one-line note on the milestone's task-ownership split (which task(s), if any, own test coverage for the endpoints/methods this task touches — read `loop/PLAN.md`'s task list to determine this). Without this context, `code-reviewer` will reasonably flag a diff with new behavior and no tests as a critical finding even when testing is a deliberate, separate, already-planned task — this happened once (M6/T-002) and cost an extra review round to correct; see `loop/LESSONS.md`.

- **Critical finding on the task's first review pass** (i.e. `builder`'s first attempt, not yet escalated): escalate straight to `implementer` with the review findings — do not spend a retry looping `builder` again on a critical finding.
- **Non-critical findings, or any finding on a later pass**: respawn whichever agent is currently active for this task (`builder` if not yet escalated, otherwise `implementer`) with the review feedback appended. Return to step 4 (re-test) then re-review. Same bounded-retry/escalation counter as step 4 — a review-triggered respin still counts toward the 2-failure escalation threshold.
- **Approved**: go to step 6.

## 6. Record

Spawn `docs-writer` to append the `loop/STATE.md` entry, check off the task in `loop/PLAN.md`, and draft a supplementary ADR only if one is warranted. Pass the `[docs]`-tagged entries from `loop/LESSONS.md` — these are the constraints on what may be stated as fact in a document that outlives this session.

If the task surfaced work you deliberately did not do — a reviewer finding ruled out of scope, an operational problem found while testing — do not let it live only in a `loop/STATE.md` paragraph. Follow "Filing follow-up issues" below.

## 7. Commit

- `git add` only the specific files touched by this task (never `git add -A`), plus `loop/PLAN.md` and `loop/STATE.md`.
- Run the full `./mvnw test` once more (the task-level run in step 4 may have been scoped to specific classes; CLAUDE.md requires the full suite pass before any commit).
- Commit with message format: `<type>: <summary> (T-00X, #<milestone-issue>)`, where `<type>` is `feat`, `fix`, `test`, or `docs` matching this repo's existing convention. Example: `feat: add BookingService.cancel with FOR UPDATE lock (T-002, #11)`.
- Do not push. Pushing remains a separate, explicit user action.

## 8. Loop or stop

If `loop/PLAN.md` still has unchecked tasks, return to step 2.

If all tasks are checked off, stop and tell the user the milestone's implementation is complete. Suggest, in order: run the `security-review` skill (this repo's milestone-close security gate, replacing a bespoke security-auditor agent), then `/retro` to bank lessons from this milestone, then close the GitHub issue (`gh issue close <n> --comment "..."`) once the user confirms.

Once the user reports the `security-review` outcome, append a milestone-close entry to `loop/STATE.md` recording it (milestone, issue number, findings or "no findings"). Do this even though the loop has ended: no `docs-writer` runs after the last task, so without this step the journal's final milestone entry reads "remaining steps are the user's" forever and the gate looks skipped — the only record otherwise lives in the GitHub issue's close comment. This happened for M6 through M9.

Any deferred work from this milestone — security findings the user chose not to fix now, reviewer findings ruled out of scope, operational problems observed while testing — becomes a follow-up issue here, under the rules below.

## Filing follow-up issues

Applies whenever any step above defers work to a GitHub issue. **An issue this loop files becomes the input `/plan-milestone` plans from later, so an error written here is an error the loop hands itself.** That is not hypothetical: `#20` and `#21` were both filed by this loop during M8 and both were materially wrong — `#21` undercounted its own deliverable, and `#20`'s named fix would have degraded real request handling well outside its stated target. M9 caught both only because planning re-read the code. Filing carefully is far cheaper than re-deriving every issue at every future read.

- **Never file unilaterally.** Propose the issue to the user — title, body, label — and file it only once they agree. The existing practice (M8/T-005) was to flag rather than file; keep it.
- **Mark every claim observed or assumed.** Say which you actually ran and what it printed, versus which you reasoned to. A number reached by inference reads identically to a measured one unless you say so — the same rule the `[docs]` lessons apply to ADRs.
- **Derive every count, don't recall it.** If the body says "two test Javadocs" or "22 error codes", produce that number from a `grep`/`rg` you actually ran and name the command in the body so a future reader can re-run it. This is the specific way `#21` went wrong.
- **State the blast radius.** For the fix you're naming, say what else it touches beyond the stated target — which other callers, requests, or code paths see the same setting. A global configuration change almost never has a local effect. This is the specific way `#20` went wrong.
- **Label the deliverables as a hypothesis in the body itself.** One line, near the top: that the scope below is this loop's best reading at filing time and should be re-derived from the code before any packet inherits it.
