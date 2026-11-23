feat(web-mode): desktop-ready UI - alignment, streaming, syntax highlighting, diffs

Fix web mode chat UI:
- Message alignment: user right, agent left (flex column layout)
- Streaming: server-side merge of streamingText into last message snapshot
- Syntax highlighting: Prism.js for code blocks in chat messages
- Diff highlighting: tool results with +/- lines highlighted
- Thinking blocks: styled thinking/reasoning display
- Approval cards: better styled tool approval UI
- Better borders: stronger contrast for inputs, messages, settings

Copied webaccess/ and assets/web-access/ carry YL-COS AGPL-3.0 provenance (private use only).