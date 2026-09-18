"use client";

import Link from "next/link";
import {
  BrainCircuit,
  CheckCircle2,
  FilePlus2,
  LoaderCircle,
  MessageSquare,
  Plus,
  Search,
  ShieldCheck,
  Sparkles,
  UserRound,
  Wrench,
  X,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { createPortal } from "react-dom";
import { AppToast } from "@/components/app-toast";
import { useLanguage } from "@/components/language-provider";
import { type AuthUser, getMe, isAuthExpiredError } from "@/lib/auth-api";
import { clearAuthTokens, readAccessToken } from "@/lib/auth-storage";
import {
  confirmAiTool,
  executeAiTool,
  getAiConversation,
  listAiConversations,
  listAiTools,
  runAiChat,
  type AiConversationListItem,
  type AiSource,
  type AiToolDefinition,
} from "@/lib/ai-api";
import { localizedPath } from "@/lib/i18n";

type Message = { role: "user" | "assistant"; content: string; sources?: AiSource[] | null };
type ToolModalState = { tool: AiToolDefinition; output: unknown | null };

const TOOL_FIELD_LABELS: Record<string, string> = {
  account: "账号信息", recentLedger: "近期记录", count: "数量", items: "列表", authors: "作者订阅", topics: "专题订阅", collections: "合集订阅", tags: "标签订阅", summary: "汇总", gross: "总收益", pending: "待结算", settled: "已结算", refunded: "已退款", query: "搜索内容", group: "分组", source: "来源", article: "文章", activeUsers: "活跃用户", publishedArticles: "已发布文章", openAlerts: "未处理告警", todayAuditLogs: "今日审计记录", generatedAt: "生成时间", title: "标题", status: "状态", visibility: "可见范围", updatedAt: "更新时间", publishedAt: "发布时间", viewCount: "阅读量", commentCount: "评论数",
};

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
  const [toolModal, setToolModal] = useState<ToolModalState | null>(null);
  const [draftModalOpen, setDraftModalOpen] = useState(false);
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
      setToolModal(null);
      setDraftModalOpen(false);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : phrase("对话读取失败。", "Could not load the conversation."));
    }
  }

  function newConversation() {
    setConversationId(undefined);
    setMessages([]);
    setPendingDraft(null);
    setToolModal(null);
    setDraftModalOpen(false);
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
    } finally {
      setSending(false);
    }
  }

  async function runTool(tool: AiToolDefinition) {
    if (!token || toolRunning) return;
    if (tool.name === "create_article_draft") {
      setPendingDraft(null);
      setDraftModalOpen(true);
      return;
    }
    setToolModal({ tool, output: null });
    setToolRunning(tool.name);
    setError("");
    try {
      const result = await executeAiTool(token, tool.name, tool.name === "search_visible_articles" ? { query: draft || phrase("最新文章", "recent articles") } : undefined, conversationId);
      setToolModal((current) => current?.tool.name === tool.name ? { ...current, output: result.output ?? result } : current);
      setNotice(phrase(`${tool.label}已完成。`, `${tool.label} completed.`));
    } catch (toolError) {
      setError(toolError instanceof Error ? toolError.message : phrase("工具执行失败。", "The tool failed."));
    } finally {
      setToolRunning("");
    }
  }

  async function prepareDraft() {
    if (!token || toolRunning || !draftTitle.trim() || !draftContent.trim()) return;
    setToolRunning("create_article_draft");
    setError("");
    try {
      const result = await executeAiTool(token, "create_article_draft", { title: draftTitle, content: draftContent }, conversationId);
      if (result.confirmationToken && result.preview) setPendingDraft({ invocationId: result.invocationId, token: result.confirmationToken, title: result.preview.title, content: result.preview.content });
      setNotice(phrase("草稿已准备，请确认后写入。", "The draft is ready. Confirm before writing it."));
    } catch (toolError) {
      setError(toolError instanceof Error ? toolError.message : phrase("草稿准备失败。", "Could not prepare the draft."));
    } finally {
      setToolRunning("");
    }
  }

  async function confirmDraft() {
    if (!token || !pendingDraft || toolRunning) return;
    setToolRunning("confirm");
    try {
      const result = await confirmAiTool(token, pendingDraft.invocationId, pendingDraft.token);
      setPendingDraft(null);
      setDraftTitle("");
      setDraftContent("");
      setDraftModalOpen(false);
      setNotice(phrase(`文章草稿“${result.article.title}”已创建。`, `Draft “${result.article.title}” was created.`));
    } catch (confirmError) {
      setError(confirmError instanceof Error ? confirmError.message : phrase("草稿确认失败。", "Could not confirm the draft."));
    } finally {
      setToolRunning("");
    }
  }

  function renderToolValue(value: unknown, depth = 0): ReactNode {
    if (value === null || value === undefined || value === "") return <span className="ai-result-empty">-</span>;
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return <span className="ai-result-value">{String(value)}</span>;
    if (Array.isArray(value)) return <div className="ai-result-list">{value.length ? value.map((item, index) => <article key={index}>{renderToolValue(item, depth + 1)}</article>) : <span className="ai-result-empty">{phrase("暂无记录", "No records")}</span>}</div>;
    if (typeof value === "object") return <div className={`ai-result-object depth-${Math.min(depth, 2)}`}>{Object.entries(value as Record<string, unknown>).map(([key, item]) => <div className="ai-result-field" key={key}><span>{TOOL_FIELD_LABELS[key] || key}</span><div>{renderToolValue(item, depth + 1)}</div></div>)}</div>;
    return <span className="ai-result-value">{String(value)}</span>;
  }

  const activeTitle = useMemo(() => conversations.find((item) => item.id === conversationId)?.title ?? phrase("新对话", "New conversation"), [conversationId, conversations, phrase]);
  if (loading) return <section className="page-shell ai-user-page"><div className="ai-user-loading"><LoaderCircle className="spin" size={20} />{phrase("正在加载 AI 助手", "Loading AI assistant")}</div></section>;
  if (!user) return <section className="page-shell ai-user-page"><div className="search-page-empty"><strong>{phrase("无法进入 AI 助手", "Cannot open AI assistant")}</strong><span>{error}</span></div></section>;

  return <section className="page-shell ai-user-page">
    <header className="ai-user-header"><div><span className="section-label">{locale === "zh-CN" ? "智能工作台" : "AI workspace"}</span><h1><BrainCircuit aria-hidden="true" size={23} />{phrase("AI 助手", "AI assistant")}</h1><p>{phrase("只使用当前账号有权限查看的内容；涉及写入的操作会先预览并确认。", "Only content visible to this account is used. Write actions are previewed and confirmed first.")}</p></div></header>
    <div className="ai-user-layout">
      <aside className="ai-conversation-sidebar"><button aria-label={phrase("新对话", "New chat")} className="ai-new-conversation" onClick={newConversation} type="button"><span><MessageSquare aria-hidden="true" size={15} />{phrase("新对话", "New chat")}</span><Plus aria-hidden="true" size={15} /></button>{conversations.length ? conversations.map((item) => <button className={item.id === conversationId ? "active" : ""} key={item.id} onClick={() => void openConversation(item.id)} type="button"><strong>{item.title}</strong><small>{item._count.messages} {phrase("条消息", "messages")}</small></button>) : <p>{phrase("还没有历史对话。", "No conversations yet.")}</p>}</aside>
      <main className="ai-chat-panel"><header><span><Sparkles size={16} />{activeTitle}</span><small><ShieldCheck size={13} />{phrase("权限过滤已启用", "Permission filter on")}</small></header><div className="ai-chat-messages">{messages.length ? messages.map((message, index) => <article className={`ai-chat-message ${message.role}`} key={`${index}-${message.content.slice(0, 12)}`}><div aria-label={message.role === "user" ? phrase("你的消息", "Your message") : phrase("AI 消息", "AI message")} className="ai-chat-message-label" title={message.role === "user" ? phrase("你的消息", "Your message") : phrase("AI 消息", "AI message")}>{message.role === "user" ? <UserRound aria-hidden="true" size={15} /> : <Sparkles aria-hidden="true" size={15} />}</div><div className="ai-chat-message-body"><p>{message.content}</p>{message.sources?.length ? <div className="ai-source-list"><small>{phrase("引用来源", "Sources")}</small>{message.sources.map((source, sourceIndex) => <Link href={localizedPath(`/articles/${source.slug}`, locale)} key={source.id}>[{sourceIndex + 1}] {source.title}</Link>)}</div> : null}</div></article>) : <div className="ai-chat-empty"><BrainCircuit size={30} /><strong>{phrase("问问站内内容或你的个人数据", "Ask about site content or your own data")}</strong><span>{phrase("例如：总结当前文章、查询我的积分、列出我的订阅。", "For example: summarize this article, show my points, or list your subscriptions.")}</span></div>}</div><form className="ai-chat-composer" onSubmit={(event) => { event.preventDefault(); void sendMessage(); }}><textarea aria-label={phrase("向 AI 提问", "Ask AI")} disabled={sending} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void sendMessage(); } }} placeholder={phrase("输入问题，Enter 发送，Shift+Enter 换行", "Ask a question. Enter sends; Shift+Enter adds a line break.")} rows={3} value={draft} /><button aria-label={phrase("发送", "Send")} disabled={sending || !draft.trim()} title={phrase("发送", "Send")} type="submit">{sending ? <LoaderCircle className="spin" size={16} /> : <Sparkles size={17} />}</button></form></main>
      <aside className="ai-tools-sidebar"><header><span><Wrench size={15} />{phrase("受控工具", "Controlled tools")}</span><small>{phrase("默认只读", "Read-only by default")}</small></header>{tools.map((tool) => <button className="ai-tool-button" disabled={Boolean(toolRunning)} key={tool.name} onClick={() => void runTool(tool)} type="button"><span><strong>{tool.label}</strong><small>{tool.description}</small></span>{tool.readOnly ? <Search size={14} /> : <FilePlus2 size={14} />}</button>)}</aside>
    </div>
    {toolModal && typeof document !== "undefined" ? createPortal(<div className="modal-backdrop ai-tool-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setToolModal(null); }} role="presentation"><section aria-modal="true" className="ai-tool-modal" onMouseDown={(event) => event.stopPropagation()} role="dialog"><header><span><Wrench aria-hidden="true" size={17} /><strong>{toolModal.tool.label}</strong></span><button aria-label={phrase("关闭", "Close")} onClick={() => setToolModal(null)} title={phrase("关闭", "Close")} type="button"><X aria-hidden="true" size={17} /></button></header><p className="ai-tool-modal-description">{toolModal.tool.description}</p><div className="ai-tool-modal-content">{toolModal.output === null ? <div className="ai-tool-modal-loading"><LoaderCircle className="spin" size={19} />{phrase("正在读取结果", "Loading result")}</div> : renderToolValue(toolModal.output)}</div><footer><button className="button secondary" onClick={() => setToolModal(null)} type="button">{phrase("关闭", "Close")}</button></footer></section></div>, document.body) : null}
    {draftModalOpen && typeof document !== "undefined" ? createPortal(<div className="modal-backdrop ai-draft-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget && toolRunning !== "confirm") setDraftModalOpen(false); }} role="presentation"><section aria-label={phrase("准备文章草稿", "Prepare article draft")} aria-modal="true" className="ai-draft-modal" onMouseDown={(event) => event.stopPropagation()} role="dialog"><header><span><FilePlus2 aria-hidden="true" size={17} /><strong>{phrase("准备文章草稿", "Prepare article draft")}</strong></span><button aria-label={phrase("关闭", "Close")} disabled={toolRunning === "confirm"} onClick={() => setDraftModalOpen(false)} title={phrase("关闭", "Close")} type="button"><X aria-hidden="true" size={17} /></button></header><div className="ai-draft-modal-fields"><label><span>{phrase("标题", "Title")}</span><input maxLength={120} onChange={(event) => setDraftTitle(event.target.value)} placeholder={phrase("填写草稿标题", "Enter a draft title")} value={draftTitle} /></label><label><span>{phrase("正文", "Body")}</span><textarea maxLength={60000} onChange={(event) => setDraftContent(event.target.value)} placeholder={phrase("填写草稿正文", "Enter the draft body")} rows={9} value={draftContent} /></label></div>{pendingDraft ? <div className="ai-draft-preview"><span><CheckCircle2 aria-hidden="true" size={14} />{phrase("草稿预览，确认后才会写入文章", "Draft preview; nothing is written until you confirm")}</span><strong>{pendingDraft.title}</strong><p>{pendingDraft.content}</p></div> : null}<footer><button className="button secondary" disabled={toolRunning === "confirm"} onClick={() => setDraftModalOpen(false)} type="button">{phrase("关闭", "Close")}</button>{pendingDraft ? <button className="button" disabled={toolRunning === "confirm"} onClick={() => void confirmDraft()} type="button">{toolRunning === "confirm" ? phrase("创建中", "Creating") : phrase("确认创建", "Confirm create")}</button> : <button className="button" disabled={Boolean(toolRunning) || !draftTitle.trim() || !draftContent.trim()} onClick={() => void prepareDraft()} type="button">{toolRunning === "create_article_draft" ? phrase("生成中", "Preparing") : phrase("生成预览", "Prepare preview")}</button>}</footer></section></div>, document.body) : null}
    <AppToast message={error} onDismiss={() => setError("")} tone="error" />
    <AppToast message={notice} onDismiss={() => setNotice("")} tone="success" />
  </section>;
}
