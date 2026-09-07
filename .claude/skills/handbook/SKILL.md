---
name: handbook
description: The Kotlin engineering standard this repository holds to, in handbooks/. Read before writing or changing any Kotlin code here — modules, samples, tests alike — and before touching Exposed or PostgreSQL code in server-sdk's sync.server.postgres package, or multiplatform code in client-sdk and samples/ledger. Covers KDoc requirements, value classes, sealed hierarchies, kotlin.time and kotlin.uuid over Java types, visibility defaults, and the table, transaction and migration rules for Exposed.
---

# The handbook

`handbooks/` **is** the standard. There is no other copy to consult and nothing to reconcile it
against: what is in the directory is what applies, and a rule that is not there is not a rule here.

This skill is a router, not a summary. It says which document to open and in what order; the rules
themselves stay in the documents, so that there is exactly one place where they can be wrong.

## Read in this order

1. **`handbooks/kotlin.md` — always.** Every module, every source set, every change. Not optional
   and not skimmable: a convention cannot be inferred from a file name, so the document is read in
   full.

2. **The document covering the area being changed, if the directory has one.** The directory is the
   authority on its own contents — list it rather than trusting this list, which goes stale as
   documents are added. As things stand:

   | Changing | Also mandatory |
   |---|---|
   | `server-sdk/` package `dev.voir.reflector.sync.server.postgres` | `handbooks/exposed.md` |
   | Multiplatform questions in `client-sdk/` or `samples/ledger/` | `handbooks/optional-kmp.md` |

3. **Where no document covers the area,** `kotlin.md` and this repository's own rules in
   `AGENTS.md` are the whole of the standard. That is an answer, not a gap: it does not license
   guessing at a convention that was never written down.

## How they combine

A narrower document **refines** a broader one rather than replacing it. The requirements of a
specific task and this repository's rules in `AGENTS.md` take priority over the handbook. An
irreconcilable conflict between two documents is raised explicitly rather than resolved silently —
`docs/sync-server-design.md` records one such departure and its reasoning, which is the
shape that argument is expected to take.

## Also read the specification you are changing

The handbook governs how the code is written; the design documents govern what it does. Both bind:

- `docs/sync-client-design.md` — before any change in `client-sdk/` or `contracts/sync-protocol/`
- `docs/sync-server-design.md` — before any change in `server-sdk/`

When a change alters the mechanism, the specification is updated in the same change.
