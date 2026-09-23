"use client";

import { Activity, Database, Download, FileScan, Image, LoaderCircle, Mic, RefreshCw, Save, Sparkles, TestTube2, Trash2 } from "lucide-react";
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
  type AiModelOption,
  type AiProvider,
  getAiAdminCapabilities,
  getAiAdminKnowledge,
  getAiAdminKnowledgeDocuments,
  getAiAdminUsage,
  listAiAdminModels,
  reindexAiKnowledge,
  reindexAiKnowledgeDocument,
  testAiAdminCapability,
  updateAiAdminCapability,
} from "@/lib/ai-api";

const icons = { embedding: Database, ocr: FileScan, transcription: Mic, image_generation: Image } satisfies Record<AiCapability, typeof Database>;

function initialDraft(item: AiCapabilityConfiguration): AiCapabilityConfigurationUpdate {
  return {
    enabled: item.enabled,
    provider: item.provider === "openai-compatible" ? "custom" : item.provider,
    baseUrl: item.baseUrl,
    model: item.model,
    fallbackEnabled: item.fallbackEnabled,
    fallbackProvider: item.fallbackProvider ?? "custom",
    fallbackBaseUrl: item.fallbackBaseUrl,
    fallbackModel: item.fallbackModel,
    globalConcurrency: item.globalConcurrency,
    userConcurrency: item.userConcurrency,
    requestTimeoutSeconds: item.requestTimeoutSeconds,
    dailyRequestLimit: item.dailyRequestLimit,
    monthlyBudgetMicros: item.monthlyBudgetMicros,
    billingCurrency: item.billingCurrency,
    inputCostPerMillionMicros: item.inputCostPerMillionMicros,
    cachedInputCostPerMillionMicros: item.cachedInputCostPerMillionMicros,
    outputCostPerMillionMicros: item.outputCostPerMillionMicros,
    fallbackInputCostPerMillionMicros: item.fallbackInputCostPerMillionMicros,
    fallbackCachedInputCostPerMillionMicros: item.fallbackCachedInputCostPerMillionMicros,
    fallbackOutputCostPerMillionMicros: item.fallbackOutputCostPerMillionMicros,
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
  const [fallbackKeys, setFallbackKeys] = useState<Record<string, string>>({});
  const [clearFallbackKeys, setClearFallbackKeys] = useState<Record<string, boolean>>({});
  const [usage, setUsage] = useState<{ requests: number; tokens: number; cachedInputUnits: number; estimatedCostMicros: number } | null>(null);
  const [quality, setQuality] = useState<{ feedbackTotal: number; helpful: number; unhelpful: number; averageRating: number | null } | null>(null);
  const [knowledge, setKnowledge] = useState<{ documents: number; chunks: number; ready: number; keywordReady: number; pendingEmbedding: number; failed: number; vectors: number; semanticSearchAvailable: boolean; keywordSearchAvailable: boolean } | null>(null);
  const [documents, setDocuments] = useState<AiKnowledgeDocument[]>([]);
  const [modelsByCapability, setModelsByCapability] = useState<Record<string, AiModelOption[]>>({});
  const [fallbackModelsByCapability, setFallbackModelsByCapability] = useState<Record<string, AiModelOption[]>>({});
  const [modelsLoading, setModelsLoading] = useState("");
  const [fallbackModelsLoading, setFallbackModelsLoading] = useState("");
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
    setModelsByCapability({});
    setFallbackModelsByCapability({});
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

  function changeProvider(item: AiCapabilityConfiguration, provider: AiProvider) {
    const defaultBaseUrls: Partial<Record<AiProvider, string>> = {
      openai: "https://api.openai.com/v1",
      deepseek: "https://api.deepseek.com/v1",
      anthropic: "https://api.anthropic.com",
      google: "https://generativelanguage.googleapis.com/v1beta",
      custom: "https://api.example.com/v1",
    };
    setDrafts((current) => {
      const draft = current[item.capability];
      if (!draft) return current;
      const knownBaseUrls = Object.values(defaultBaseUrls);
      const shouldUseProviderDefault = !draft.baseUrl.trim() || knownBaseUrls.includes(draft.baseUrl);
      return { ...current, [item.capability]: { ...draft, provider, baseUrl: shouldUseProviderDefault ? (defaultBaseUrls[provider] ?? draft.baseUrl) : draft.baseUrl } };
    });
    setModelsByCapability((current) => ({ ...current, [item.capability]: [] }));
  }

  function changeFallbackProvider(item: AiCapabilityConfiguration, provider: AiProvider) {
    const defaultBaseUrls: Partial<Record<AiProvider, string>> = {
      openai: "https://api.openai.com/v1",
      deepseek: "https://api.deepseek.com/v1",
      anthropic: "https://api.anthropic.com",
      google: "https://generativelanguage.googleapis.com/v1beta",
      custom: "https://api.example.com/v1",
    };
    setDrafts((current) => {
      const draft = current[item.capability];
      if (!draft) return current;
      const knownBaseUrls = Object.values(defaultBaseUrls);
      const currentBaseUrl = draft.fallbackBaseUrl ?? "";
      const shouldUseProviderDefault = !currentBaseUrl.trim() || knownBaseUrls.includes(currentBaseUrl);
      return { ...current, [item.capability]: { ...draft, fallbackProvider: provider, fallbackBaseUrl: shouldUseProviderDefault ? (defaultBaseUrls[provider] ?? currentBaseUrl) : currentBaseUrl } };
    });
    setFallbackModelsByCapability((current) => ({ ...current, [item.capability]: [] }));
  }

  async function fetchCapabilityModels(item: AiCapabilityConfiguration) {
    const draft = drafts[item.capability];
    if (!draft || modelsLoading) return;
    setModelsLoading(item.capability);
    setError("");
    try {
      const result = await listAiAdminModels(token, { capability: item.capability, provider: draft.provider, baseUrl: draft.baseUrl, apiKey: keys[item.capability]?.trim() || undefined });
      setModelsByCapability((current) => ({ ...current, [item.capability]: result.models }));
      if (!draft.model && result.models[0]) update(item.capability, "model", result.models[0].id);
      setNotice(phrase(`${item.label.zh}已获取 ${result.models.length} 个模型。`, `${item.label.en}: ${result.models.length} models loaded.`));
    } catch (modelError) {
      setError(modelError instanceof Error ? modelError.message : phrase("模型列表获取失败。", "Could not load the model list."));
    } finally {
      setModelsLoading("");
    }
  }

  async function fetchFallbackCapabilityModels(item: AiCapabilityConfiguration) {
    const draft = drafts[item.capability];
    if (!draft || fallbackModelsLoading || !(draft.fallbackBaseUrl ?? "").trim()) return;
    setFallbackModelsLoading(item.capability);
    setError("");
    try {
      const result = await listAiAdminModels(token, { credential: "fallback", capability: item.capability, provider: draft.fallbackProvider ?? "custom", baseUrl: draft.fallbackBaseUrl ?? "", apiKey: fallbackKeys[item.capability]?.trim() || undefined });
      setFallbackModelsByCapability((current) => ({ ...current, [item.capability]: result.models }));
      if (!draft.fallbackModel && result.models[0]) update(item.capability, "fallbackModel", result.models[0].id);
      setNotice(phrase(`${item.label.zh}已获取 ${result.models.length} 个备用模型。`, `${item.label.en}: ${result.models.length} fallback models loaded.`));
    } catch (modelError) {
      setError(modelError instanceof Error ? modelError.message : phrase("备用模型列表获取失败。", "Could not load the fallback model list."));
    } finally {
      setFallbackModelsLoading("");
    }
  }

  async function save(item: AiCapabilityConfiguration) {
    const draft = drafts[item.capability];
    if (!draft || saving) return;
    setSaving(item.capability);
    setError("");
    try {
      await updateAiAdminCapability(token, item.capability, { ...draft, apiKey: keys[item.capability]?.trim() || undefined, clearApiKey: clearKeys[item.capability], fallbackApiKey: fallbackKeys[item.capability]?.trim() || undefined, clearFallbackApiKey: clearFallbackKeys[item.capability] });
      setNotice(phrase(`${item.label.zh}配置已保存。`, `${item.label.en} settings saved.`));
      await load();
      setKeys((current) => ({ ...current, [item.capability]: "" }));
      setClearKeys((current) => ({ ...current, [item.capability]: false }));
      setFallbackKeys((current) => ({ ...current, [item.capability]: "" }));
      setClearFallbackKeys((current) => ({ ...current, [item.capability]: false }));
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
      setNotice(phrase(`已扫描 ${result.scanned} 篇文章，向量完成 ${result.indexed} 篇，关键词可用 ${result.keywordReady} 篇，待处理 ${result.pending} 篇。`, `Scanned ${result.scanned} articles; ${result.indexed} have vectors, ${result.keywordReady} are keyword-ready, and ${result.pending} remain pending.`));
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
        const models = modelsByCapability[item.capability] ?? [];
        const fallbackModels = fallbackModelsByCapability[item.capability] ?? [];
        const modelOptions = [
          ...(draft.model && !models.some((model) => model.id === draft.model) ? [{ value: draft.model, label: draft.model }] : []),
          ...models.map((model) => ({ value: model.id, label: model.label })),
          ...(!draft.model && !models.length ? [{ value: "", label: phrase("点击右侧图标获取模型", "Use the button to load models") }] : []),
        ];
        const fallbackModelOptions = [
          ...(draft.fallbackModel && !fallbackModels.some((model) => model.id === draft.fallbackModel) ? [{ value: draft.fallbackModel, label: draft.fallbackModel }] : []),
          ...fallbackModels.map((model) => ({ value: model.id, label: model.label })),
          ...(!draft.fallbackModel && !fallbackModels.length ? [{ value: "", label: phrase("点击右侧图标获取模型", "Use the button to load models") }] : []),
        ];
        return <article className="ai-capability-card" key={item.capability}>
          <header><span><Icon size={16} /><strong>{phrase(item.label.zh, item.label.en)}</strong></span><label className="ai-capability-toggle"><input checked={draft.enabled} onChange={(event) => update(item.capability, "enabled", event.target.checked)} type="checkbox" /><span>{draft.enabled ? phrase("已启用", "Enabled") : phrase("未启用", "Disabled")}</span></label></header>
          <p className="ai-capability-note">{phrase(pricingNote[0], pricingNote[1] ?? pricingNote[0])}</p>
          <div className="ai-capability-fields">
            <label><span>{phrase("供应商", "Provider")}</span><GlassSelect ariaLabel={phrase("供应商", "Provider")} onChange={(value) => changeProvider(item, value as AiProvider)} options={[{ value: "openai", label: "OpenAI" }, { value: "custom", label: phrase("通用第三方（OpenAI 兼容）", "Generic third party (OpenAI-compatible)") }, { value: "deepseek", label: "DeepSeek" }, { value: "google", label: "Google" }, { value: "anthropic", label: "Anthropic" }]} value={draft.provider} /></label>
            <label><span>{phrase("接口地址", "Base URL")}</span><input onChange={(event) => update(item.capability, "baseUrl", event.target.value)} placeholder="https://api.example.com/v1" value={draft.baseUrl} /></label>
            <label><span>{phrase("模型", "Model")}</span><div className="ai-model-picker"><GlassSelect ariaLabel={phrase("模型", "Model")} menuClassName="ai-model-menu" menuPortal onChange={(value) => update(item.capability, "model", value)} options={modelOptions} value={draft.model} /><button aria-label={phrase("获取模型列表", "Load models")} className="ai-model-fetch-button" disabled={Boolean(modelsLoading) || !draft.baseUrl.trim()} onClick={() => void fetchCapabilityModels(item)} title={phrase("获取模型列表", "Load model list")} type="button">{modelsLoading === item.capability ? <RefreshCw className="spin" size={14} /> : <Download size={14} />}</button></div></label>
            <label><span>API Key {item.apiKeyConfigured ? phrase("（已配置）", "(configured)") : ""}</span><div className={`ai-capability-key${item.apiKeyConfigured ? " has-clear-key" : ""}`}><PasswordInput autoComplete="new-password" onChange={(event) => { setKeys((current) => ({ ...current, [item.capability]: event.target.value })); if (event.target.value) setClearKeys((current) => ({ ...current, [item.capability]: false })); }} placeholder={clearKeys[item.capability] ? phrase("保存时清除", "Clear on save") : item.apiKeyConfigured ? phrase("留空保持不变", "Leave blank to keep") : phrase("输入 API Key", "Enter API key")} value={keys[item.capability] || ""} />{item.apiKeyConfigured ? <button aria-pressed={clearKeys[item.capability]} className={`ai-clear-key-button${clearKeys[item.capability] ? " active" : ""}`} onClick={() => setClearKeys((current) => ({ ...current, [item.capability]: !current[item.capability] }))} title={phrase("清除已保存的 API Key", "Clear saved API key")} type="button"><Trash2 size={14} /></button> : null}</div></label>
          </div>
           <div className="ai-capability-fallback"><label className="ai-config-toggle"><span>{phrase("启用备用模型", "Enable fallback model")}</span><input checked={Boolean(draft.fallbackEnabled)} onChange={(event) => update(item.capability, "fallbackEnabled", event.target.checked)} type="checkbox" /></label><small>{phrase("仅在超时、网络暂时不可达、429 或 5xx 时切换。", "Switches only for timeouts, transient network failures, 429, or 5xx.")}</small>{draft.fallbackEnabled ? <div className="ai-capability-fields"><label><span>{phrase("备用供应商", "Fallback provider")}</span><GlassSelect ariaLabel={phrase("备用供应商", "Fallback provider")} onChange={(value) => changeFallbackProvider(item, value as AiProvider)} options={[{ value: "openai", label: "OpenAI" }, { value: "custom", label: phrase("通用第三方", "Generic third party") }, { value: "deepseek", label: "DeepSeek" }, { value: "google", label: "Google" }, { value: "anthropic", label: "Anthropic" }]} value={draft.fallbackProvider ?? "custom"} /></label><label><span>{phrase("备用接口地址", "Fallback base URL")}</span><input onChange={(event) => update(item.capability, "fallbackBaseUrl", event.target.value)} placeholder="https://api.example.com/v1" value={draft.fallbackBaseUrl ?? ""} /></label><label><span>{phrase("备用模型", "Fallback model")}</span><div className="ai-model-picker"><GlassSelect ariaLabel={phrase("备用模型", "Fallback model")} menuClassName="ai-model-menu" menuPortal onChange={(value) => update(item.capability, "fallbackModel", value)} options={fallbackModelOptions} value={draft.fallbackModel ?? ""} /><button aria-label={phrase("获取备用模型列表", "Load fallback models")} className="ai-model-fetch-button" disabled={fallbackModelsLoading === item.capability || !(draft.fallbackBaseUrl ?? "").trim()} onClick={() => void fetchFallbackCapabilityModels(item)} title={phrase("获取备用模型列表", "Load fallback model list")} type="button">{fallbackModelsLoading === item.capability ? <RefreshCw className="spin" size={14} /> : <Download size={14} />}</button></div></label><label><span>{phrase("备用 API Key", "Fallback API key")}{item.fallbackApiKeyConfigured ? phrase("（已配置）", "(configured)") : ""}</span><div className={"ai-capability-key" + (item.fallbackApiKeyConfigured ? " has-clear-key" : "")}><PasswordInput autoComplete="new-password" onChange={(event) => { setFallbackKeys((current) => ({ ...current, [item.capability]: event.target.value })); if (event.target.value) setClearFallbackKeys((current) => ({ ...current, [item.capability]: false })); }} placeholder={clearFallbackKeys[item.capability] ? phrase("保存时清除", "Clear on save") : item.fallbackApiKeyConfigured ? phrase("留空保持不变", "Leave blank to keep") : phrase("输入备用 API Key", "Enter fallback API key")} value={fallbackKeys[item.capability] || ""} />{item.fallbackApiKeyConfigured ? <button aria-pressed={clearFallbackKeys[item.capability]} className={"ai-clear-key-button" + (clearFallbackKeys[item.capability] ? " active" : "")} onClick={() => setClearFallbackKeys((current) => ({ ...current, [item.capability]: !current[item.capability] }))} title={phrase("清除已保存的备用 API Key", "Clear saved fallback API Key")} type="button"><Trash2 size={14} /></button> : null}</div></label></div> : null}</div>
          <div className="ai-pricing-grid">
            <label><span>{phrase("输入价 / 百万 tokens", "Input price / million tokens")}</span><input min={0} onChange={(event) => update(item.capability, "inputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={draft.inputCostPerMillionMicros / 1000000} /></label>
            <label><span>{phrase("备用输入价 / 百万 tokens", "Fallback input price / million tokens")}</span><input min={0} onChange={(event) => update(item.capability, "fallbackInputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={(draft.fallbackInputCostPerMillionMicros ?? 0) / 1000000} /></label>
            <label><span>{phrase("缓存输入价 / 百万 tokens", "Cached input price / million tokens")}</span><input min={0} onChange={(event) => update(item.capability, "cachedInputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={(draft.cachedInputCostPerMillionMicros ?? 0) / 1000000} /></label>
            <label><span>{phrase("备用缓存输入价 / 百万 tokens", "Fallback cached input price / million tokens")}</span><input min={0} onChange={(event) => update(item.capability, "fallbackCachedInputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={(draft.fallbackCachedInputCostPerMillionMicros ?? 0) / 1000000} /></label>
            <label><span>{phrase("输出价 / 百万 tokens", "Output price / million tokens")}</span><input min={0} onChange={(event) => update(item.capability, "outputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={draft.outputCostPerMillionMicros / 1000000} /></label>
            <label><span>{phrase("备用输出价 / 百万 tokens", "Fallback output price / million tokens")}</span><input min={0} onChange={(event) => update(item.capability, "fallbackOutputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={(draft.fallbackOutputCostPerMillionMicros ?? 0) / 1000000} /></label>
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
      <div className="ai-knowledge-stats"><span><strong>{knowledge?.documents ?? 0}</strong>{phrase("篇文章", "documents")}</span><span><strong>{knowledge?.chunks ?? 0}</strong>{phrase("个片段", "chunks")}</span><span><strong>{knowledge?.vectors ?? 0}</strong>{phrase("个向量", "vectors")}</span><span><strong>{knowledge?.keywordReady ?? 0}</strong>{phrase("关键词可用", "keyword-ready")}</span><span className={knowledge?.semanticSearchAvailable ? "ready" : "pending"}>{knowledge?.semanticSearchAvailable ? phrase("语义检索可用", "Semantic search ready") : phrase("关键词检索可用", "Keyword search ready")}</span></div>
      <p>{phrase("切片不依赖 Embedding。未配置时使用权限过滤的关键词检索；配置后再次刷新索引即可补充向量并启用语义检索。", "Chunking does not require Embeddings. Without them, permission-aware keyword search remains active; configure Embeddings and refresh the index to add vectors and enable semantic search.")}</p>
      {documents.length ? <div className="ai-knowledge-documents">{documents.map((document) => <div className="ai-knowledge-document" key={document.id}><span><strong>{document.title}</strong><small>{document.status === "keyword_ready" ? phrase("关键词可用", "keyword-ready") : document.status === "ready" ? phrase("向量可用", "vector-ready") : document.status} · {document.chunkCount} {phrase("片段", "chunks")} · {document.vectorCount} {phrase("向量", "vectors")}</small></span><button aria-label={phrase(`重建 ${document.title}`, `Reindex ${document.title}`)} className="admin-header-icon-action" disabled={reindexing} onClick={() => void rebuildDocument(document)} title={phrase("单篇重建", "Reindex document")} type="button"><RefreshCw size={14} /></button></div>)}</div> : null}
    </div>
    <div className="ai-usage-summary">
      <header><span><Activity size={16} />{phrase("能力使用量与费用估算", "Capability usage and estimated cost")}</span><small>{phrase("费用来自管理员配置的官方单价，账单以供应商后台为准。", "Costs use admin-configured official rates; provider invoices remain authoritative.")}</small></header>
      <div><strong>{usage?.requests ?? 0}</strong><span>{phrase("次调用", "requests")}</span><strong>{usage?.tokens ?? 0}</strong><span>tokens</span><strong>{usage?.cachedInputUnits ?? 0}</strong><span>{phrase("缓存输入", "cached input")}</span><strong>{((usage?.estimatedCostMicros ?? 0) / 1000000).toFixed(6)}</strong><span>{items[0]?.billingCurrency ?? "USD"}</span><strong>{quality?.feedbackTotal ?? 0}</strong><span>{phrase("条质量反馈", "quality ratings")}</span><strong>{quality?.averageRating ?? "-"}</strong><span>{phrase("平均评分", "average rating")}</span></div>
    </div>
    <AppToast message={error || notice} onDismiss={() => { setError(""); setNotice(""); }} tone={error ? "error" : "success"} />
  </section>;
}
