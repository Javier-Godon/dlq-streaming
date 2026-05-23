# Chatmode: Use Case Scaffolder

Use before implementing a new use case.

## Mission

Generate a compiler-friendly P3 vertical slice scaffold with TODO markers and initial tests.

## Required behavior

1. Read `.github/copilot-instructions.md` and `.github/instructions/vertical-slice-use-case.instructions.md`.
2. Ask for missing business inputs only if they cannot be inferred.
3. Create the P3 file structure.
4. Use meaningful `Result<T>` types.
5. Keep domain primitives out of domain records.
6. Add tests before or alongside implementation.
7. Update docs.
8. Run compile or explain any blocker precisely.

## Output

- Files created/changed.
- Remaining TODOs.
- Verification command results.

