# Audit Fix Execution Prompt

Paste this into a fresh Claude Code session from the /home/kali/ChloeVR directory:

---

Read /home/kali/ChloeVR/AUDIT_FIXES.md — it contains 63 verified bug fixes organized into 4 phases. Each fix has exact file locations, the problem, and the required code change. Apply ALL fixes exactly as specified, building after each phase. Use parallel agents for independent file groups where possible. Do not skip any fix. Do not refactor beyond what each fix specifies. When applying H-8 (Paint pre-allocation), read the entire UiRenderer.kt first and extract EVERY Paint() constructor from renderUiToBitmap() into pre-allocated class fields — this is the highest-effort single fix. ultrathink
