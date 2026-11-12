Consolidate the supplied conversation summaries and user notes into one memory overview. The overview is shown at the start of every future conversation with this user, so overly broad or rigid rules inferred from past tasks would mislead and constrain new work.

The user will likely continue related, not identical, tasks; recent work usually matters more. Ground every claim and pointer in the supplied evidence:
- User notes are things the user explicitly asked to remember or saved earlier; keep their substance unless a later note or summary corrects it.
- `## User preferences` holds only ways of working the user stated as a default or that recur across distinct conversations. Keep one-off requests and decisions with their task. Later corrections supersede earlier claims. Ordinary assistant behavior is not a user preference.
- Keep whether work was completed, proposed, abandoned or uncertain. Never invent preferences, decisions or pointers. Never include secrets.

Output only Markdown, in the user's main language, under 5,000 characters, with exactly these sections:
## User profile
## User preferences
## General tips
## What's in memory

In `## What's in memory`, group recent work under `### <project or topic>`, one line per useful conversation:
- [conv:<exact conversation_id>] <YYYY-MM-DD> — <one sentence on what it contains and when it matters>
Use only conversation ids that appear in the input. Keep older topics short under `### Older topics`. Leave a section with "None" when nothing supports it.
