---
name: loop-handoff
description: Overwrite loop/HANDOFF.md with a checkpoint of the current milestone loop's in-flight task, stage, and next action, so /orchestrate can resume instead of restarting. Use when ending a session mid-milestone, or when the user says "/loop-handoff". Distinct from the mattpocock-skills "handoff" skill, which produces a general-purpose conversation summary rather than a loop/-specific checkpoint.
---

Gather the current loop state:

- Which task (`T-00X`) is in flight, and which stage it's at (`builder`, `test-runner`, `code-reviewer`, `docs-writer`, or blocked/escalated).
- The last test result (pass/fail, which classes).
- `git status --short` output.
- One-line description of the next action to take when work resumes.

Overwrite `loop/HANDOFF.md` (this is a single checkpoint, not a log — unlike `loop/STATE.md`, replace its contents entirely rather than appending):

```
# HANDOFF — session checkpoint
Written: <timestamp>

## Milestone
<M<N>, issue #<n>>

## Current task
<T-00X — title>

## Stage
<builder | test-runner | code-reviewer | docs-writer | blocked>

## Last test result
<pass/fail summary>

## Uncommitted changes
<git status --short output, or "none">

## Next action
<one line>
```

Append a single line to `loop/STATE.md`: `## <date> — Handoff checkpoint written`.

`/orchestrate` reads `loop/HANDOFF.md` first on its next invocation and resumes from the described task/stage if it's newer than the last `loop/STATE.md` entry, instead of restarting from `loop/PLAN.md`'s first unchecked task.
