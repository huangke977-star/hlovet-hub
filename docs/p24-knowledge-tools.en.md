# P24 Knowledge And Intelligent Tools

## Status

P24 code, the database migration, API tests, and the web build are complete locally. The production migration and release checks are performed during deployment. P24 does not require OSS/R2 and does not run a local model, Ollama, a vector database, or another always-on AI service on the server.

## User entry

Signed-in users open “AI assistant” from the top navigation. The page contains conversation history, the Q&A area, and controlled tools. Requests are sent only after a complete external-model configuration is enabled in the admin console.

The assistant searches articles visible to the current account and can answer questions using an article, topic, or collection context. Visibility is filtered on the server before context is sent to the model. Protected point-resource content continues to use the article response redaction rules and cannot be bypassed through AI.

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

Super administrators open “Admin -> AI settings” to configure the provider, base URL, model, and API key; set global/per-user concurrency, output limits, timeout, and daily request limits; view resource recommendations, connection tests, token/cost statistics, and redacted invocation logs; and view “Tool audit”. The audit contains only the tool, account, status, confirmation requirement, and timestamps, never inputs, outputs, or API keys.

Ordinary administrators cannot read or change AI settings and cannot view tool audit records.

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

## Not included yet

P24-04 vector retrieval/RAG remains an evaluation item. A lightweight option will be considered only after content volume, retrieval quality, and real usage justify it; Elasticsearch, a vector database, and a local model are not preinstalled.
