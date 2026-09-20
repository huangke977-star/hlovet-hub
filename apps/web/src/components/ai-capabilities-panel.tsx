"use client";

import { Activity, Database, FileScan, Image, LoaderCircle, Mic, RefreshCw, Save, Sparkles, TestTube2, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { AppToast } from "@/components/app-toast";
import { GlassSelect } from "@/components/glass-select";
import { PasswordInput } from "@/components/password-input";
import { useLanguage } from "@/components/language-provider";
import {
  type AiCapability,
  type AiCapabilityConfiguration,
  type AiCapabilityConfigurationUpdate,
  type AiKnowledgeDocument,
  getAiAdminCapabilities,
  getAiAdminKnowledge,
  getAiAdminKnowledgeDocuments,
  getAiAdminUsage,
  reindexAiKnowledge,
  reindexAiKnowledgeDocument,
  testAiAdminCapability,
  updateAiAdminCapability,
} from "@/lib/ai-api";

const icons = { embedding: Database, ocr: FileScan, transcription: Mic, image_generation: Image } satisfies Record<AiCapability, typeof Database>;

function initialDraft(item: AiCapabilityConfiguration): AiCapabilityConfigurationUpdate {
  return {
    enabled: item.enabled,
    provider: item.provider,
    baseUrl: item.baseUrl,
    model: item.model,
    globalConcurrency: item.globalConcurrency,
    userConcurrency: item.userConcurrency,
    requestTimeoutSeconds: item.requestTimeoutSeconds,
    dailyRequestLimit: item.dailyRequestLimit,
    monthlyBudgetMicros: item.monthlyBudgetMicros,
    billingCurrency: item.billingCurrency,
    inputCostPerMillionMicros: item.inputCostPerMillionMicros,
    outputCostPerMillionMicros: item.outputCostPerMillionMicros,
    unitCostMicros: item.unitCostMicros,
    unitName: item.unitName,
    maxInputBytes: item.maxInputBytes,
  };
}

export function AiCapabilitiesPanel({ token }: { token: string }) {
  const { phrase } = useLanguage();
  const [items, setItems] = useState<AiCapabilityConfiguration[]>([]);
  const [drafts, setDrafts] = useState<Record<string, AiCapabilityConfigurationUpdate>>({});
  const [keys, setKeys] = useState<Record<string, string>>({});
  const [clearKeys, setClearKeys] = useState<Record<string, boolean>>({});
  const [usage, setUsage] = useState<{ requests: number; tokens: number; estimatedCostMicros: number } | null>(null);
  const [quality, setQuality] = useState<{ feedbackTotal: number; helpful: number; unhelpful: number; averageRating: number | null } | null>(null);
  const [knowledge, setKnowledge] = useState<{ documents: number; chunks: number; ready: number; vectors: number; semanticSearchAvailable: boolean } | null>(null);
  const [documents, setDocuments] = useState<AiKnowledgeDocument[]>([]);
  const [saving, setSaving] = useState("");
  const [testing, setTesting] = useState("");
  const [reindexing, setReindexing] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function load() {
    const [capabilities, usageOverview, knowledgeOverview, knowledgeDocuments] = await Promise.all([
      getAiAdminCapabilities(token),
      getAiAdminUsage(token),
      getAiAdminKnowledge(token),
      getAiAdminKnowledgeDocuments(token),
    ]);
    setItems(capabilities.items);
    setDrafts(Object.fromEntries(capabilities.items.map((item) => [item.capability, initialDraft(item)])));
    setUsage(usageOverview.total);
    setQuality(usageOverview.quality);
    setKnowledge(knowledgeOverview);
    setDocuments(knowledgeDocuments.items);
  }

  useEffect(() => {
    let active = true;
    const refresh = async () => {
      try {
        await load();
      } catch (loadError) {
        if (active) setError(loadError instanceof Error ? loadError.message : phrase("AI 能力配置读取失败。", "Could not load AI capability settings."));
      }
    };
    void refresh();
    return () => { active = false; };
  }, [token, phrase]);

  function update(capability: string, key: keyof AiCapabilityConfigurationUpdate, value: unknown) {
    setDrafts((current) => ({ ...current, [capability]: { ...current[capability], [key]: value } }));
  }

  async function save(item: AiCapabilityConfiguration) {
    const draft = drafts[item.capability];
    if (!draft || saving) return;
    setSaving(item.capability);
    setError("");
    try {
      await updateAiAdminCapability(token, item.capability, { ...draft, apiKey: keys[item.capability]?.trim() || undefined, clearApiKey: clearKeys[item.capability] });
      setNotice(phrase(`${item.label.zh}配置已保存。`, `${item.label.en} settings saved.`));
      await load();
      setKeys((current) => ({ ...current, [item.capability]: "" }));
      setClearKeys((current) => ({ ...current, [item.capability]: false }));
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : phrase("AI 能力配置保存失败。", "Could not save AI capability settings."));
    } finally {
      setSaving("");
    }
  }

  async function test(item: AiCapabilityConfiguration) {
    if (testing) return;
    setTesting(item.capability);
    setError("");
    try {
      const result = await testAiAdminCapability(token, item.capability);
      setNotice(result.message || phrase(`${item.label.zh}配置测试成功。`, `${item.label.en} configuration test succeeded.`));
    } catch (testError) {
      setError(testError instanceof Error ? testError.message : phrase("AI 能力测试失败。", "AI capability test failed."));
    } finally {
      setTesting("");
    }
  }

  async function rebuild() {
    if (reindexing) return;
    setReindexing(true);
    setError("");
    try {
      const result = await reindexAiKnowledge(token, 50);
      await load();
      setNotice(phrase(`已扫描 ${result.scanned} 篇文章，向量完成 ${result.indexed} 篇，待配置 ${result.pending} 篇。`, `Scanned ${result.scanned} articles; ${result.indexed} indexed and ${result.pending} waiting for embeddings.`));
    } catch (reindexError) {
      setError(reindexError instanceof Error ? reindexError.message : phrase("知识库索引失败。", "Knowledge indexing failed."));
    } finally {
      setReindexing(false);
    }
  }

  async function rebuildDocument(document: AiKnowledgeDocument) {
    if (reindexing) return;
    setReindexing(true);
    setError("");
    try {
      const result = await reindexAiKnowledgeDocument(token, document.id);
      await load();
      setNotice(result.removed ? phrase(`已移除“${document.title}”的失效索引。`, `Removed the stale index for “${document.title}”.`) : phrase(`“${document.title}”索引已更新。`, `The index for “${document.title}” was updated.`));
    } catch (reindexError) {
      setError(reindexError instanceof Error ? reindexError.message : phrase("单篇索引失败。", "Document reindexing failed."));
    } finally {
      setReindexing(false);
    }
  }

  return <section className="ai-capabilities-panel">
    <header className="ai-capabilities-header">
      <div><span><Sparkles size={17} />{phrase("AI 能力与知识库", "AI capabilities and knowledge base")}</span><small>{phrase("每项能力独立开关、计价、并发和 API Key。未配置时不会调用外部服务。", "Each capability has its own switch, pricing, concurrency and API key. Unconfigured capabilities never call external services.")}</small></div>
      <button className="admin-header-icon-action" onClick={() => void load()} title={phrase("刷新", "Refresh")} type="button"><RefreshCw size={16} /></button>
    </header>
    <div className="ai-capability-grid">
      {items.map((item) => {
        const Icon = icons[item.capability];
        const draft = drafts[item.capability];
        if (!draft) return null;
        const pricingNote = item.officialPricingNote.split("\n");
        return <article className="ai-capability-card" key={item.capability}>
          <header><span><Icon size={16} /><strong>{phrase(item.label.zh, item.label.en)}</strong></span><label className="ai-capability-toggle"><input checked={draft.enabled} onChange={(event) => update(item.capability, "enabled", event.target.checked)} type="checkbox" /><span>{draft.enabled ? phrase("已启用", "Enabled") : phrase("未启用", "Disabled")}</span></label></header>
          <p className="ai-capability-note">{phrase(pricingNote[0], pricingNote[1] ?? pricingNote[0])}</p>
          <div className="ai-capability-fields">
            <label><span>{phrase("供应商", "Provider")}</span><GlassSelect ariaLabel={phrase("供应商", "Provider")} onChange={(value) => update(item.capability, "provider", value)} options={[{ value: "custom", label: phrase("通用第三方（OpenAI 兼容）", "Generic third party (OpenAI-compatible)") }, { value: "deepseek", label: "DeepSeek" }, { value: "google", label: "Google" }, { value: "anthropic", label: "Anthropic" }]} value={draft.provider} /></label>
            <label><span>{phrase("接口地址", "Base URL")}</span><input onChange={(event) => update(item.capability, "baseUrl", event.target.value)} placeholder="https://api.example.com/v1" value={draft.baseUrl} /></label>
            <label><span>{phrase("模型", "Model")}</span><input onChange={(event) => update(item.capability, "model", event.target.value)} placeholder={phrase("填写对应能力模型", "Enter the capability model")} value={draft.model} /></label>
            <label><span>API Key {item.apiKeyConfigured ? phrase("（已配置）", "(configured)") : ""}</span><div className="ai-capability-key"><PasswordInput autoComplete="new-password" onChange={(event) => { setKeys((current) => ({ ...current, [item.capability]: event.target.value })); if (event.target.value) setClearKeys((current) => ({ ...current, [item.capability]: false })); }} placeholder={clearKeys[item.capability] ? phrase("保存时清除", "Clear on save") : item.apiKeyConfigured ? phrase("留空保持不变", "Leave blank to keep") : phrase("输入 API Key", "Enter API key")} value={keys[item.capability] || ""} />{item.apiKeyConfigured ? <button aria-pressed={clearKeys[item.capability]} className={`ai-clear-key-button${clearKeys[item.capability] ? " active" : ""}`} onClick={() => setClearKeys((current) => ({ ...current, [item.capability]: !current[item.capability] }))} title={phrase("清除已保存的 API Key", "Clear saved API key")} type="button"><Trash2 size={14} /></button> : null}</div></label>
          </div>
          <div className="ai-capability-numbers">
            <label><span>{phrase("全站并发", "Global")}</span><input min={1} max={8} onChange={(event) => update(item.capability, "globalConcurrency", Number(event.target.value))} type="number" value={draft.globalConcurrency} /></label>
            <label><span>{phrase("单用户", "Per user")}</span><input min={1} max={8} onChange={(event) => update(item.capability, "userConcurrency", Number(event.target.value))} type="number" value={draft.userConcurrency} /></label>
            <label><span>{phrase("每日上限", "Daily limit")}</span><input min={0} onChange={(event) => update(item.capability, "dailyRequestLimit", Number(event.target.value))} type="number" value={draft.dailyRequestLimit} /></label>
            <label><span>{phrase("单次价格", "Unit price")}</span><input min={0} onChange={(event) => update(item.capability, "unitCostMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={draft.unitCostMicros / 1000000} /></label>
          </div>
          <footer><small>{item.apiKeyConfigured ? phrase("服务端已保存密钥", "Key stored server-side") : phrase("尚未配置密钥", "No key configured")}</small><div><button className="button secondary" disabled={testing === item.capability || !item.apiKeyConfigured} onClick={() => void test(item)} type="button">{testing === item.capability ? <LoaderCircle className="spin" size={14} /> : <TestTube2 size={14} />}{phrase("测试", "Test")}</button><button className="button" disabled={saving === item.capability} onClick={() => void save(item)} type="button">{saving === item.capability ? <LoaderCircle className="spin" size={14} /> : <Save size={14} />}{phrase("保存", "Save")}</button></div></footer>
        </article>;
      })}
    </div>
    <div className="ai-knowledge-summary">
      <header><span><Database size={16} />{phrase("RAG 知识库", "RAG knowledge base")}</span><button className="button secondary" disabled={reindexing} onClick={() => void rebuild()} type="button">{reindexing ? <LoaderCircle className="spin" size={14} /> : <RefreshCw size={14} />}{phrase("建立/刷新索引", "Build / refresh index")}</button></header>
      <div className="ai-knowledge-stats"><span><strong>{knowledge?.documents ?? 0}</strong>{phrase("篇文章", "documents")}</span><span><strong>{knowledge?.chunks ?? 0}</strong>{phrase("个片段", "chunks")}</span><span><strong>{knowledge?.vectors ?? 0}</strong>{phrase("个向量", "vectors")}</span><span className={knowledge?.semanticSearchAvailable ? "ready" : "pending"}>{knowledge?.semanticSearchAvailable ? phrase("语义检索可用", "Semantic search ready") : phrase("等待 Embedding API", "Waiting for embeddings")}</span></div>
      <p>{phrase("没有 Embedding API 时仍会建立知识片段，并自动使用权限过滤的关键词检索；配置后再次刷新索引即可生成向量。", "Without an Embedding API, knowledge chunks are still built and permission-aware keyword search is used. Configure embeddings and refresh the index to create vectors.")}</p>
      {documents.length ? <div className="ai-knowledge-documents">{documents.map((document) => <div className="ai-knowledge-document" key={document.id}><span><strong>{document.title}</strong><small>{document.status} · {document.chunkCount} {phrase("片段", "chunks")} · {document.vectorCount} {phrase("向量", "vectors")}</small></span><button aria-label={phrase(`重建 ${document.title}`, `Reindex ${document.title}`)} className="admin-header-icon-action" disabled={reindexing} onClick={() => void rebuildDocument(document)} title={phrase("单篇重建", "Reindex document")} type="button"><RefreshCw size={14} /></button></div>)}</div> : null}
    </div>
    <div className="ai-usage-summary">
      <header><span><Activity size={16} />{phrase("能力使用量与费用估算", "Capability usage and estimated cost")}</span><small>{phrase("费用来自管理员配置的官方单价，账单以供应商后台为准。", "Costs use admin-configured official rates; provider invoices remain authoritative.")}</small></header>
      <div><strong>{usage?.requests ?? 0}</strong><span>{phrase("次调用", "requests")}</span><strong>{usage?.tokens ?? 0}</strong><span>tokens</span><strong>{((usage?.estimatedCostMicros ?? 0) / 1000000).toFixed(6)}</strong><span>{items[0]?.billingCurrency ?? "USD"}</span><strong>{quality?.feedbackTotal ?? 0}</strong><span>{phrase("条质量反馈", "quality ratings")}</span><strong>{quality?.averageRating ?? "-"}</strong><span>{phrase("平均评分", "average rating")}</span></div>
    </div>
    <AppToast message={error || notice} onDismiss={() => { setError(""); setNotice(""); }} tone={error ? "error" : "success"} />
  </section>;
}
