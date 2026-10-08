# Chencang Design Tokens — Single Source of Truth

Edit token values in `tokens/moyu/*.json` (`primitive.json` → `semantic.json` →
`scale.json` for the app-wide L1/L2 tokens). Never edit generated files by hand.

## Regenerate after any token change
    cd design && npm run tokens
Commit the regenerated `Moyu.swift`/`Moyu.kt` alongside the token change.

## Generated outputs
- iOS:     ios/ChencangShared/Sources/ChencangShared/Generated/Moyu.swift
- Android: android/design/src/main/kotlin/app/chencang/design/Moyu.kt

## Adding a token
Add to the right `tokens/moyu/*.json` ($type: color | dimension | fontSize | duration), run `npm run tokens`, commit.

CI (`design-tokens` workflow) regenerates and fails on any drift.

