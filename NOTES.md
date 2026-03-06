# ChloeVR Inter-Agent Notes

Append-only. Every agent reads this at session start and writes at session end.
Format: `## [Agent] YYYY-MM-DD` followed by notes.

---

## [Claude] 2026-03-06

### Initial Assessment

Read the entire codebase. Project is fully functional — all core VR180/360 playback features work including grab/reposition with aim pose, roll, A/B repeat, scrub bar, zoom, seek, and speed control.

### What I Set Up

Created multi-agent coordination architecture:
- `AGENTS.md` — universal onboarding brief with architecture, file map, pipeline, and role assignments
- `NOTES.md` — this file, inter-agent message bus
- `.agent-roles/CLAUDE.md` — my standing orders
- `.agent-roles/CODEX.md` — Codex 5.3 standing orders
- `.agent-roles/GEMINI.md` — Gemini CLI standing orders

### Current Concerns

1. **`MainActivity.kt` is 1335 lines** and handles everything. It's the single biggest risk for merge conflicts between agents. I've documented line-range sections in AGENTS.md. Coordinate before editing.

2. **No tests exist.** Any agent adding tests should create `prism/app/src/test/` for unit tests.

3. **Debug logging in `openxr_input.cpp:462-467`** still active — logs hand pose every ~2s when trigger is held. Low priority but should be cleaned up.

4. **Fisheye projections** (MKX200, RF52, VRCA220) all map to `SurfaceEntity.Shape.Hemisphere`. This is an approximation. Correct rendering would need custom mesh support from the Jetpack XR SDK, which may not be available yet.

5. **No persistence.** User preferences (seek increment, last file, screen adjustments) are lost on restart.
