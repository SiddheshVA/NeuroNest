# NeuroNest RAG Knowledge Base (v1)

37 Markdown documents in 10 topic folders for the NeuroNest wellness chatbot.
Last reviewed: 2026-10-06.

## Rules this knowledge base follows
- Educational and supportive only. It does not diagnose, prescribe or promise outcomes.
- Every document has: title, topic, content, practical strategies, safety/limitations,
  when professional support may help, source organization, source URL, last reviewed date.
- Content is paraphrased and synthesized, not copied.
- Crisis and suicide handling is NOT in this knowledge base. It stays application-controlled
  (crisis flag -> fixed support message). Do not route crisis messages through retrieval.

## Folders
| Folder | Docs |
|---|---|
| stress/ | 4 |
| anxiety/ | 4 |
| sleep/ | 4 |
| mindfulness/ | 4 |
| breathing/ | 3 |
| grounding/ | 3 |
| relaxation/ | 4 |
| emotional-wellbeing/ | 4 |
| self-care/ | 3 |
| professional-support/ | 4 |

## Front-matter fields
id, title, topic, tags, audience, doc_type (explainer | technique | when-to-seek-help),
safety_level (low | medium | crisis-adjacent), source_name, source_url, secondary_source_url (optional),
licence, reuse (adapted | summarized | original), language, author, last_reviewed, version.

## Licences and attribution
- public-domain: NIMH, NCCIH, NHLBI (US government works). Images from these sites are NOT reusable.
- CC-BY-NC-SA-3.0-IGO: documents adapted from WHO "Doing What Matters in Times of Stress" (2020).
  Non-commercial use only; adaptations keep the same licence; WHO did not create or endorse them.
  Recheck before any commercial launch.
- own: team-written techniques (box breathing, 5-4-3-2-1) with no single official source.
- facts-only: Indian helpline facts (Tele MANAS). Verify numbers before every release.

Show the attribution line from each document's "Source" section when the chatbot cites it.

## Before release
- [ ] A second person reviews every document
- [ ] Click every source_url and confirm it still works
- [ ] Re-verify Tele MANAS numbers on the official site
- [ ] Bump `version` and `last_reviewed` when a document changes, then re-embed it
