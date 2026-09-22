# HLOVET Configuration Migration

The **System configuration transfer** panel moves site configuration to another server without moving users or article content.

It includes site settings, security integrations, AI and capability settings (including optional fallback models), backup/storage policies, taxonomies, moderation policy, and audit retention. It excludes users, credentials, passkeys, TOTP, sessions, Redis state, articles, comments, chat, AI conversations, knowledge documents, and user uploads.

## Procedure

1. As a super administrator, open **Admin -> System overview** on the old server.
2. In **System configuration transfer**, click **Export configuration**.
3. Keep the JSON bundle private; never commit it to GitHub or send it through a public channel.
4. Deploy a clean instance on the new server and let migrations and seed data finish first.
5. Set a valid `BACKUP_ENCRYPTION_KEY`. To keep encrypted SMTP, Google, AI, or OSS/R2 credentials, preserve the same key used by the source server.
6. Sign in as the new server's super administrator and import the JSON bundle from the same panel.
7. Review domain-specific values: `SITE_DOMAIN`, `WEB_ORIGIN`, Passkey Origin, Google redirect URI, Turnstile hostnames, and TURN settings.
8. Verify the public site, admin settings, AI settings, and security settings.

When Embeddings are not configured, importing the bundle does not call an AI provider. Knowledge chunks can still be built and keyword retrieval remains available. Configure Embeddings later and rebuild the index to create vectors.
