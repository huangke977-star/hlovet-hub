# P24 Knowledge And Intelligent Tools

## Status

P24 is implemented with the database migration, AI-focused tests, and the web build. It does not require OSS/R2 and does not run a local model, Ollama, a vector database, or another always-on AI service on the server; unconfigured capabilities remain disabled.

## User entry

Signed-in users open “AI assistant” from the top navigation. The page contains conversation history, the Q&A area, and controlled tools. Requests are sent only after a complete external-model configuration is enabled in the admin console.

The “Media tools” section below the controlled tools provides user-facing entry points for image OCR, transcription, and image generation. Each capability is independently controlled by the admin configuration. An unconfigured capability is labelled “Not configured” and remains disabled. Completed tasks are scoped to the current account; OCR/transcription text is loaded from the owner-only task detail endpoint, and generated images are returned through an authenticated image endpoint instead of placing Base64 data in the task list.

The assistant searches articles visible to the current account and can answer questions using an article, topic, or collection context. Visibility is filtered on the server before context is sent to the model. When Embeddings are configured, retrieval combines vector similarity with keyword matching; without Embeddings, article chunks are still built and keyword retrieval is used. Protected point-resource content continues to use the article response redaction rules and cannot be bypassed through AI.

Conversation context uses recent messages plus automatic summaries. When the configured threshold is reached, older messages are compressed while goals, facts, sources, and unfinished items are retained; a failed summary never blocks the current chat request.

## Controlled tools

| Tool | Purpose | Write access |
| --- | --- | --- |
| My points and growth | Read the current user’s points, experience, level, and recent ledger | Read-only |
| My tasks | Summarize the current user’s unread notifications and pending entries | Read-only; does not mark items |
| My subscriptions | Read the user’s author, topic, and collection subscriptions | Read-only |
| My earnings | Read pending, settled, and refunded resource earnings | Read-only |
| My article status | Read the user’s draft, publication, and moderation states | Read-only |
| Search visible articles | Search only articles visible to the current account | Read-only |
| Read article context | Read a visible article as Q&A context | Read-only |
| Summarize topic/collection | Summarize accessible topic or collection articles | Read-only |
| Explain admin overview | Read permitted operational aggregates for an administrator | Read-only |
| Create article draft | Prepare a draft preview from a title and body | Human confirmation required |

Draft creation never writes immediately. The service first stores a pending preview and creates a short-lived one-time confirmation token. Only “Confirm create” calls the existing article creation flow. The token is stored only as a SHA-256 digest and expires after ten minutes.

## Admin entry

Super administrators open “Admin -> AI settings” to configure the provider, base URL, model, and API key; set global/per-user concurrency, output limits, timeout, and daily request limits; configure independent Embedding, OCR, transcription, and image-generation switches, budgets, and prices; view resource recommendations, connection tests, token/cost statistics, and redacted invocation logs; and view “Tool audit”. The audit contains only the tool, account, status, confirmation requirement, and timestamps, never inputs, outputs, or API keys. The knowledge-base section lists document indexing status and supports bulk or single-document rebuilds.

When the main model name exactly matches a small DeepSeek or common OpenAI-compatible preset, the pricing area offers a manual “apply reference” action. Prices can change, so provider billing remains authoritative. Ordinary administrators cannot read or change AI settings and cannot view tool audit records.

## Security boundaries

- API keys are encrypted on the server and never returned to the browser;
- conversations and tool calls are bound to the current account;
- the API uses a fixed allowlist and rejects unknown tools;
- deletion, publication, point changes, and configuration changes are not exposed as AI actions;
- logs keep status, duration, token usage, model, and a bounded safe error summary, not prompts or generated bodies;
- provider pricing, terms, and data-processing conditions remain the administrator’s responsibility.

## Acceptance steps

1. As a super administrator, open “Admin -> AI settings”, save a disabled configuration, and confirm that no AI request is sent.
2. Enter the provider URL, model, and API key, save, and click “Test connection”; verify a success or redacted failure record in “Invocation log”.
3. Enable AI, open “AI assistant”, and ask about a public article; verify that source links appear.
4. Ask about a private article that the current account cannot read; verify that hidden content is not returned.
5. Run a personal points, subscription, or article-status tool; verify that it returns only the current user’s data and creates a “Tool audit” record.
6. Prepare an article draft and verify that a preview appears first; without confirmation no article is created, and confirmation creates a draft.
7. Open `/admin/ai` as an ordinary administrator and verify “Access denied”; verify that a super administrator can see settings and audit metadata.
8. Click “Build / refresh index”, confirm chunks and document status appear, then use a document refresh icon to rebuild one article. Deleted or unpublished articles should be removed from the index.
9. Click helpful or unhelpful under an AI answer, reload the conversation, and confirm the selected rating remains; verify the aggregate appears in the admin usage and quality area.
10. Disable OCR, transcription, or image generation and call its endpoint to confirm a clear not-configured response. After enabling and configuring a real provider, use a real sample to verify the media task and usage record.
11. Select `deepseek-chat`, `deepseek-reasoner`, `gpt-4o-mini`, `gpt-4o`, or `o3-mini` as the main model and verify that the pricing area offers the wand action. Apply it, check the currency and token prices, then compare them with the current provider invoice before saving.
12. In “Media tools” on the “AI assistant” page, verify that unconfigured OCR, transcription, and image-generation actions are disabled. After configuring a real provider, upload an image or audio file or submit an image prompt, then verify the task status, result, and recent-task list for the current account. Generated-image history should load through task detail rather than exposing Base64 data in the list.

## Resource boundary

Vectors are stored as JSON in MySQL. Retrieval reads a bounded candidate set and calculates similarity inside the API process, which fits the current small server. If article volume grows materially, consider a dedicated vector database; do not deploy a local large model or resident vector service on the roughly 2-vCPU, 1.6-GiB server. OCR, transcription, and image generation currently use synchronous provider calls protected by timeout, concurrency, file-size, and budget limits.
