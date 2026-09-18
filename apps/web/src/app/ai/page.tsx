"use client";

import Link from "next/link";
import { BrainCircuit, CheckCircle2, FilePlus2, LoaderCircle, MessageSquare, Plus, Search, ShieldCheck, Sparkles, Wrench } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { AppToast } from "@/components/app-toast";
import { useLanguage } from "@/components/language-provider";
import { type AuthUser, getMe, isAuthExpiredError } from "@/lib/auth-api";
import { clearAuthTokens, readAccessToken } from "@/lib/auth-storage";
import { confirmAiTool, executeAiTool, getAiConversation, listAiConversations, listAiTools, runAiChat, type AiConversationListItem, type AiSource, type AiToolDefinition } from "@/lib/ai-api";
import { localizedPath } from "@/lib/i18n";

type Message = { role: "user" | "assistant"; content: string; sources?: AiSource[] | null };

export default function AiPage() {
  const { locale, phrase } = useLanguage();
  const [user, setUser] = useState<AuthUser | null>(null);
  const [token, setToken] = useState("");
  const [conversations, setConversations] = useState<AiConversationListItem[]>([]);
  const [conversationId, setConversationId] = useState<number | undefined>();
  const [messages, setMessages] = useState<Message[]>([]);
  const [tools, setTools] = useState<AiToolDefinition[]>([]);
  const [draft, setDraft] = useState("");
  const [draftTitle, setDraftTitle] = useState("");
  const [draftContent, setDraftContent] = useState("");
  const [pendingDraft, setPendingDraft] = useState<{ invocationId: number; token: string; title: string; content: string } | null>(null);
  const [toolResult, setToolResult] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [toolRunning, setToolRunning] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function load(currentToken: string) {
    const [conversationPage, availableTools] = await Promise.all([listAiConversations(currentToken), listAiTools(currentToken)]);
    setConversations(conversationPage.items);
    setTools(availableTools);
  }

  useEffect(() => {
    const currentToken = readAccessToken();
    if (!currentToken) {
      window.location.replace(localizedPath("/login", locale));
      return;
    }
    let active = true;
    void getMe(currentToken).then(async (currentUser) => {
      if (!active) return;
      setToken(currentToken);
      setUser(currentUser);
      await load(currentToken);
    }).catch((loadError) => {
      if (isAuthExpiredError(loadError)) {
        clearAuthTokens();
        window.location.replace(localizedPath("/login", locale));
        return;
      }
      if (active) setError(loadError instanceof Error ? loadError.message : phrase("AI 助手加载失败。", "Could not load the AI assistant."));
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [locale, phrase]);

  async function openConversation(id: number) {
    if (!token) return;
    setError("");
    try {
      const conversation = await getAiConversation(token, id);
      setConversationId(conversation.id);
      setMessages(conversation.messages.map((message) => ({ role: message.role, content: message.content, sources: message.sources })));
      setPendingDraft(null);
      setToolResult(null);
    } catch (loadError) { setError(loadError instanceof Error ? loadError.message : phrase("对话读取失败。", "Could not load the conversation.")); }
  }

  function newConversation() {
    setConversationId(undefined);
    setMessages([]);
    setPendingDraft(null);
    setToolResult(null);
    setNotice(phrase("已开始新对话。", "Started a new conversation."));
  }

  async function sendMessage() {
    const message = draft.trim();
    if (!token || !message || sending) return;
    setDraft("");
    setSending(true);
    setError("");
    setMessages((current) => [...current, { role: "user", content: message }]);
    try {
      const result = await runAiChat(token, { conversationId, message, locale });
      setConversationId(result.conversationId);
      setMessages((current) => [...current, { role: "assistant", content: result.text, sources: result.sources }]);
      setNotice(phrase(`已生成回答，耗时 ${result.durationMs} ms。`, `Answer generated in ${result.durationMs} ms.`));
      await load(token);
    } catch (sendError) {
      setMessages((current) => current.slice(0, -1));
      setError(sendError instanceof Error ? sendError.message : phrase("AI 请求失败。", "The AI request failed."));
    } finally { setSending(false); }
  }

  async function runTool(tool: AiToolDefinition) {
    if (!token || toolRunning) return;
    if (tool.name === "create_article_draft") {
      setPendingDraft(null);
      setToolResult(null);
      return;
    }
    setToolRunning(tool.name);
    setError("");
    try {
      const result = await executeAiTool(token, tool.name, tool.name === "search_visible_articles" ? { query: draft || phrase("最新文章", "recent articles") } : undefined, conversationId);
      setToolResult(result.output ?? result);
      setNotice(phrase(`${tool.label}已完成。`, `${tool.label} completed.`));
    } catch (toolError) { setError(toolError instanceof Error ? toolError.message : phrase("工具执行失败。", "The tool failed.")); }
    finally { setToolRunning(""); }
  }

  async function prepareDraft() {
    if (!token || toolRunning || !draftTitle.trim() || !draftContent.trim()) return;
    setToolRunning("create_article_draft");
    setError("");
    try {
      const result = await executeAiTool(token, "create_article_draft", { title: draftTitle, content: draftContent }, conversationId);
      if (result.confirmationToken && result.preview) setPendingDraft({ invocationId: result.invocationId, token: result.confirmationToken, title: result.preview.title, content: result.preview.content });
      setNotice(phrase("草稿已准备，请确认后写入。", "The draft is ready. Confirm before writing it."));
    } catch (toolError) { setError(toolError instanceof Error ? toolError.message : phrase("草稿准备失败。", "Could not prepare the draft.")); }
    finally { setToolRunning(""); }
  }

  async function confirmDraft() {
    if (!token || !pendingDraft || toolRunning) return;
    setToolRunning("confirm");
    try {
      const result = await confirmAiTool(token, pendingDraft.invocationId, pendingDraft.token);
      setPendingDraft(null);
      setDraftTitle("");
      setDraftContent("");
      setNotice(phrase(`文章草稿“${result.article.title}”已创建。`, `Draft “${result.article.title}” was created.`));
    } catch (confirmError) { setError(confirmError instanceof Error ? confirmError.message : phrase("草稿确认失败。", "Could not confirm the draft.")); }
    finally { setToolRunning(""); }
  }

  const activeTitle = useMemo(() => conversations.find((item) => item.id === conversationId)?.title ?? phrase("新对话", "New conversation"), [conversationId, conversations, phrase]);
  if (loading) return <section className="page-shell ai-user-page"><div className="ai-user-loading"><LoaderCircle className="spin" size={20} />{phrase("正在加载 AI 助手", "Loading AI assistant")}</div></section>;
  if (!user) return <section className="page-shell ai-user-page"><div className="search-page-empty"><strong>{phrase("无法进入 AI 助手", "Cannot open AI assistant")}</strong><span>{error}</span></div></section>;

  return <section className="page-shell ai-user-page">
    <header className="ai-user-header"><div><span className="section-label">{locale === "zh-CN" ? "智能工作台" : "AI workspace"}</span><h1><BrainCircuit aria-hidden="true" size={23} />{phrase("AI 助手", "AI assistant")}</h1><p>{phrase("只使用当前账号有权限查看的内容；涉及写入的操作会先预览并确认。", "Only content visible to this account is used. Write actions are previewed and confirmed first.")}</p></div><button className="text-action" onClick={newConversation} type="button"><Plus size={15} />{phrase("新对话", "New chat")}</button></header>
    <div className="ai-user-layout">
      <aside className="ai-conversation-sidebar"><header><span><MessageSquare size={15} />{phrase("历史对话", "Conversations")}</span><small>{conversations.length}</small></header>{conversations.length ? conversations.map((item) => <button className={item.id === conversationId ? "active" : ""} key={item.id} onClick={() => void openConversation(item.id)} type="button"><strong>{item.title}</strong><small>{item._count.messages} {phrase("条消息", "messages")}</small></button>) : <p>{phrase("还没有历史对话。", "No conversations yet.")}</p>}</aside>
      <main className="ai-chat-panel"><header><span><Sparkles size={16} />{activeTitle}</span><small><ShieldCheck size={13} />{phrase("权限过滤已启用", "Permission filter on")}</small></header><div className="ai-chat-messages">{messages.length ? messages.map((message, index) => <article className={`ai-chat-message ${message.role}`} key={`${index}-${message.content.slice(0, 12)}`}><div className="ai-chat-message-label">{message.role === "user" ? phrase("你", "You") : phrase("AI", "AI")}</div><div className="ai-chat-message-body"><p>{message.content}</p>{message.sources?.length ? <div className="ai-source-list"><small>{phrase("引用来源", "Sources")}</small>{message.sources.map((source, sourceIndex) => <Link href={localizedPath(`/articles/${source.slug}`, locale)} key={source.id}>[{sourceIndex + 1}] {source.title}</Link>)}</div> : null}</div></article>) : <div className="ai-chat-empty"><BrainCircuit size={30} /><strong>{phrase("问问站内内容或你的个人数据", "Ask about site content or your own data")}</strong><span>{phrase("例如：总结当前文章、查询我的积分、列出我的订阅。", "For example: summarize this article, show my points, or list my subscriptions.")}</span></div>}</div><form className="ai-chat-composer" onSubmit={(event) => { event.preventDefault(); void sendMessage(); }}><textarea aria-label={phrase("向 AI 提问", "Ask AI")} disabled={sending} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void sendMessage(); } }} placeholder={phrase("输入问题，Enter 发送，Shift+Enter 换行", "Ask a question. Enter sends; Shift+Enter adds a line break.")} rows={3} value={draft} /><button disabled={sending || !draft.trim()} type="submit">{sending ? <LoaderCircle className="spin" size={16} /> : <Sparkles size={16} />}{sending ? phrase("生成中", "Generating") : phrase("发送", "Send")}</button></form></main>
      <aside className="ai-tools-sidebar"><header><span><Wrench size={15} />{phrase("受控工具", "Controlled tools")}</span><small>{phrase("默认只读", "Read-only by default")}</small></header>{tools.map((tool) => <button className="ai-tool-button" disabled={Boolean(toolRunning)} key={tool.name} onClick={() => void runTool(tool)} type="button"><span><strong>{tool.label}</strong><small>{tool.description}</small></span>{tool.readOnly ? <Search size={14} /> : <FilePlus2 size={14} />}</button>)}<div className="ai-draft-tool"><strong><FilePlus2 size={15} />{phrase("准备文章草稿", "Prepare article draft")}</strong><input maxLength={120} onChange={(event) => setDraftTitle(event.target.value)} placeholder={phrase("草稿标题", "Draft title")} value={draftTitle} /><textarea maxLength={60000} onChange={(event) => setDraftContent(event.target.value)} placeholder={phrase("草稿正文", "Draft body")} rows={5} value={draftContent} /><button disabled={Boolean(toolRunning) || !draftTitle.trim() || !draftContent.trim()} onClick={() => void prepareDraft()} type="button">{toolRunning === "create_article_draft" ? phrase("准备中", "Preparing") : phrase("生成预览", "Prepare preview")}</button>{pendingDraft ? <div className="ai-confirm-box"><span><CheckCircle2 size={14} />{phrase("确认后才会创建草稿", "Confirm to create the draft")}</span><strong>{pendingDraft.title}</strong><small>{pendingDraft.content.slice(0, 180)}{pendingDraft.content.length > 180 ? "…" : ""}</small><button disabled={toolRunning === "confirm"} onClick={() => void confirmDraft()} type="button">{toolRunning === "confirm" ? phrase("写入中", "Writing") : phrase("确认创建", "Confirm create")}</button></div> : null}</div>{toolResult ? <pre className="ai-tool-result">{JSON.stringify(toolResult, null, 2)}</pre> : null}</aside>
    </div>
    <AppToast message={error} onDismiss={() => setError("")} tone="error" /><AppToast message={notice} onDismiss={() => setNotice("")} tone="success" />
  </section>;
}
