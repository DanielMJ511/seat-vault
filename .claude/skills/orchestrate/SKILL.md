---
name: orchestrate
description: Drive one pass of SeatVault's task loop, spawning builder, test-runner, code-reviewer, and docs-writer for each task in loop/PLAN.md until the milestone's tasks are exhausted or a task gets stuck. Use when the user wants to implement the next planned task(s), or says "/orchestrate".
---

Run in the main session. Write no feature code directly — every code change goes through `builder` or `implementer`. Never scheduled or backgrounded: this is a single foreground invocation that processes tasks until done or blocked, then returns control to the user.

## 1. Resume or start

Read `loop/PLAN.md`, `loop/LESSONS.md` (in full — it's small and cumulative), and the tail of `loop/STATE.md` (last ~20 entries). If `loop/HANDOFF.md` has content newer than the last `STATE.md` entry, read it and resume from the task/stage it describes instead of restarting from `loop/PLAN.md`'s first unchecked task.

If `loop/PLAN.md` has no milestone loaded or no tasks, stop and tell the user to run `/plan-milestone` first.

## 2. Pick the next task

Take the next unchecked (`- [ ]`) task in `loop/PLAN.md` order. Read its full packet at `loop/tasks/T-00X.md`.

## 3. Build

Spawn the `builder` agent with: the task packet, the ADR numbers it references, and the full current contents of `loop/LESSONS.md`.

## 4. Test

Spawn `test-runner` on `builder`'s (or `implementer`'s) output.

- **Pass** → go to step 5.
- **Fail** → track a per-task failure counter for this task:
  - **1st failure**: respawn `builder` with the task packet plus `test-runner`'s failure digest appended. Return to step 4.
  - **2nd consecutive failure**: escalate to `implementer` (opus), passing the full history — both of `builder`'s diffs and both failure digests. Return to step 4 with `implementer`'s output.
  - **3rd failure (i.e. implementer also failed)**: stop working this task. Append a "task blocked" entry to `loop/STATE.md` (task id, failure history summary). Return control to the user — do not retry further.

## 5. Review

Spawn `code-reviewer` on the diff accumulated for this task so far (`git diff`), along with the current contents of `loop/LESSONS.md` and a one-line note on the milestone's task-ownership split (which task(s), if any, own test coverage for the endpoints/methods this task touches — read `loop/PLAN.md`'s task list to determine this). Without this context, `code-reviewer` will reasonably flag a diff with new behavior and no tests as a critical finding even when testing is a deliberate, separate, already-planned task — this happened once (M6/T-002) and cost an extra review round to correct; see `loop/LESSONS.md`.

- **Critical finding on the task's first review pass** (i.e. `builder`'s first attempt, not yet escalated): escalate straight to `implementer` with the review findings — do not spend a retry looping `builder` again on a critical finding.
- **Non-critical findings, or any finding on a later pass**: respawn whichever agent is currently active for this task (`builder` if not yet escalated, otherwise `implementer`) with the review feedback appended. Return to step 4 (re-test) then re-review. Same bounded-retry/escalation counter as step 4 — a review-triggered respin still counts toward the 2-failure escalation threshold.
- **Approved**: go to step 6.

## 6. Record

Spawn `docs-writer` to append the `loop/STATE.md` entry, check off the task in `loop/PLAN.md`, and draft a supplementary ADR only if one is warranted.

## 7. Commit

- `git add` only the specific files touched by this task (never `git add -A`), plus `loop/PLAN.md` and `loop/STATE.md`.
- Run the full `./mvnw test` once more (the task-level run in step 4 may have been scoped to specific classes; CLAUDE.md requires the full suite pass before any commit).
- Commit with message format: `<type>: <summary> (T-00X, #<milestone-issue>)`, where `<type>` is `feat`, `fix`, `test`, or `docs` matching this repo's existing convention. Example: `feat: add BookingService.cancel with FOR UPDATE lock (T-002, #11)`.
- Do not push. Pushing remains a separate, explicit user action.

## 8. Loop or stop

If `loop/PLAN.md` still has unchecked tasks, return to step 2.

If all tasks are checked off, stop and tell the user the milestone's implementation is complete. Suggest, in order: run the `security-review` skill (this repo's milestone-close security gate, replacing a bespoke security-auditor agent), then `/retro` to bank lessons from this milestone, then close the GitHub issue (`gh issue close <n> --comment "..."`) once the user confirms.
