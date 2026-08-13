---
name: retro
description: Extract recurring friction from loop/STATE.md since the current milestone's boundary marker into actionable constraints appended to loop/LESSONS.md. Use after a milestone's /orchestrate run completes, or when the user says "/retro".
---

Read `loop/STATE.md` from the most recent `## <date> — M<N> milestone boundary` marker onward (the entries `/plan-milestone` and `/orchestrate` wrote for the milestone just completed).

Identify recurring friction — the same category of problem showing up more than once, not one-off mistakes. Examples of the shape to look for: a convention `builder` repeatedly missed (e.g. omitting an explicit isolation level on a booking-mutation method), an environment issue that blocked progress (e.g. Docker not running before `test-runner` could run), a task-decomposition problem (a task that had to be split or re-scoped mid-implementation), or a review finding that recurred across multiple tasks.

For each pattern found, phrase it as an actionable constraint — something `builder`/`implementer` can act on next time it's prepended to their prompt — not a narrative retelling of what happened.

Append to `loop/LESSONS.md` under a new dated, milestone-tagged heading:

```
## M<N> retro (<date>)
- <actionable constraint>
- <actionable constraint>
```

`loop/LESSONS.md` is cumulative across milestones — only ever append a new heading, never edit or remove prior ones. `/orchestrate` reads the full file at the start of every `builder`/`implementer` spawn, so lessons from every past milestone stay in effect.

If nothing recurring is found (the milestone went cleanly, no repeated friction), say so and don't force an entry — a lesson invented to have something to write is worse than no lesson.
