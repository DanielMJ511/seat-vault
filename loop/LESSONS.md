# LESSONS — retro-earned constraints

Cumulative across milestones. Only `/retro` appends here; never reset.

## M6 retro (2026-08-13)
- When a milestone's task breakdown splits implementation from testing across separate tasks (e.g. T-004 owning tests for endpoints built in T-002/T-003), always tell `code-reviewer` this task-ownership split up front when spawning it for an implementation-only task. Without it, `code-reviewer` correctly notices a diff with new behavior and no tests, and — reasonably, given what it can see — flags it as a critical finding, which under `/orchestrate`'s rules escalates straight to `implementer` on a first pass. That happened on T-002: the finding was withdrawn on re-review once given the missing context, costing an extra review round for a non-defect. T-003 avoided it by including the context proactively. Fold this into every `code-reviewer` spawn for a milestone with a dedicated test task, not just remembered ad hoc per-task.
