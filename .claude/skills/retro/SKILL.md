---
name: retro
description: Extract recurring friction from loop/STATE.md and the milestone's own commits into actionable, audience-tagged constraints in loop/LESSONS.md, and retire lessons that have since become permanent skill text. Use after a milestone's /orchestrate run completes, or when the user says "/retro".
---

## 1. Read both records of the milestone

Read `loop/STATE.md` from the most recent `## <date> — M<N> milestone boundary` marker onward (the entries `/plan-milestone` and `/orchestrate` wrote for the milestone just completed). Note the milestone's issue number from that marker.

Then read what the milestone actually did, not only what its agents said they did: `git log --oneline --grep "#<n>"` for the issue number, and the diffs of anything whose journal entry is vague, contested, or describes a revert. `loop/STATE.md` is agents reporting on themselves — M8's own lesson is that a report of what an agent was about to do is not evidence it happened, and the dangerous half-applied tree is the one that still passes. The commits are the only record that cannot be self-serving.

Where the journal and the commits disagree, that gap is itself a candidate lesson — and say so plainly in the entry.

## 2. Identify recurring friction

Look for the same category of problem showing up more than once, not one-off mistakes. Examples of the shape: a convention `builder` repeatedly missed (e.g. omitting an explicit isolation level on a booking-mutation method), an environment issue that blocked progress (e.g. Docker not running before `test-runner` could run), a task-decomposition problem (a task that had to be split or re-scoped mid-implementation), or a review finding that recurred across multiple tasks.

For each pattern, phrase it as an actionable constraint — something the receiving agent can act on next time it's prepended to their prompt — not a narrative retelling of what happened.

**Prefer sharpening an existing lesson over adding a parallel one.** If a pattern is a more specific case of a lesson already in the file, say which one it refines and what it adds (M8's framework-probing lesson refining M7's packet-verification lesson, M9's "during planning" refining M8's "by observation", are the models to follow). A file of narrowing rules stays usable; a file of near-duplicates does not.

## 3. Tag each new lesson by audience

Every entry gets one or more tags, leading the line: `[planning]`, `[builder]`, `[reviewer]`, `[docs]` — see `loop/LESSONS.md`'s own "Audience tags" section for what each consumer does. `/orchestrate` and `/plan-milestone` deliver each agent only its own slice, so an untagged lesson reaches nobody and a wrongly-tagged one reaches an agent that cannot act on it.

Ask specifically whether the lesson is aimed at *planning*. A constraint about what a task packet must establish before implementation starts belongs to `/plan-milestone`, and tagging it `[builder]` out of habit hands it to the one stage that can no longer act on it.

## 4. Retire lessons that became instruction text

Before appending, check the existing entries against the current `.claude/skills/` and `.claude/agents/` files. If a lesson's content is now permanent instruction text there — applied automatically on every run rather than remembered — it is duplication, and a fact worth stating twice will drift. Replace that entry in place with a one-line pointer:

```
- **RETIRED → <file> <location>** (<date>). <one-line summary of the constraint.> Now permanent instruction text; the full rationale lives there.
```

Keep the pointer rather than deleting the entry: it's what stops a future `/retro` from re-deriving the same lesson and re-adding it in full.

This is the only edit permitted to a prior entry, alongside correcting or adding its audience tags. Never rewrite a prior lesson's substance, and never rewrite `loop/STATE.md` history.

## 5. Append

Append to `loop/LESSONS.md` under a new dated, milestone-tagged heading:

```
## M<N> retro (<date>)
- `[tag]` <actionable constraint>
- `[tag] [tag]` <actionable constraint>
```

If nothing recurring is found (the milestone went cleanly, no repeated friction), say so and don't force an entry — a lesson invented to have something to write is worse than no lesson. Step 4's retirement pass is still worth running on its own.

## 6. Report what moved

Tell the user: which lessons were added and to which audiences, which were retired and to where, and any journal/commit discrepancy from step 1 that didn't rise to a lesson but is worth knowing.
