"use client";

import { Activity, BrainCircuit, CheckCircle2, CircleAlert, Cpu, Database, Download, KeyRound, PlugZap, RefreshCw, Save, Server, ShieldCheck, Sparkles, Trash2 } from "lucide-react";
import { FormEvent, MouseEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { AdminPageHeader, AdminPageLoading } from "@/components/admin-page-header";
import { AppToast } from "@/components/app-toast";
import { AiCapabilitiesPanel } from "@/components/ai-capabilities-panel";
import { GlassSelect } from "@/components/glass-select";
import { PasswordInput } from "@/components/password-input";
import { useLanguage } from "@/components/language-provider";
import { type AuthUser, getMe, isAuthExpiredError } from "@/lib/auth-api";
import { clearAuthTokens, readAccessToken } from "@/lib/auth-storage";
import { getAiAdminConfiguration, getAiAdminInvocations, getAiAdminToolInvocations, listAiAdminModels, testAiAdminConnection, type AiAdminConfiguration, type AiAdminConfigurationUpdate, type AiInvocationOverview, type AiModelOption, type AiPricingPreset, type AiProvider, type AiToolInvocationAudit, updateAiAdminConfiguration } from "@/lib/ai-api";
import { localizedPath } from "@/lib/i18n";

type Draft = AiAdminConfigurationUpdate;

const PROVIDER_DEFAULT_BASE_URLS: Partial<Record<AiProvider, string>> = {
  openai: "https://api.openai.com/v1",
  deepseek: "https://api.deepseek.com/v1",
  anthropic: "https://api.anthropic.com",
  google: "https://generativelanguage.googleapis.com/v1beta",
  custom: "https://api.example.com/v1",
};

function toDraft(config: AiAdminConfiguration): Draft {
  return {
    enabled: config.enabled,
    // Older installations stored the generic provider as openai-compatible.
    provider: config.provider === "openai-compatible" ? "custom" : config.provider,
    baseUrl: config.baseUrl,
    model: config.model,
    fallbackEnabled: config.fallbackEnabled,
    fallbackProvider: config.fallbackProvider ?? "custom",
    fallbackBaseUrl: config.fallbackBaseUrl,
    fallbackModel: config.fallbackModel,
    globalConcurrency: config.globalConcurrency,
    userConcurrency: config.userConcurrency,
    maxOutputTokens: config.maxOutputTokens,
    requestTimeoutSeconds: config.requestTimeoutSeconds,
    dailyRequestLimit: config.dailyRequestLimit,
    billingCurrency: config.billingCurrency,
    inputCostPerMillionMicros: config.inputCostPerMillionMicros,
    cachedInputCostPerMillionMicros: config.cachedInputCostPerMillionMicros,
    outputCostPerMillionMicros: config.outputCostPerMillionMicros,
    fallbackInputCostPerMillionMicros: config.fallbackInputCostPerMillionMicros,
    fallbackCachedInputCostPerMillionMicros: config.fallbackCachedInputCostPerMillionMicros,
    fallbackOutputCostPerMillionMicros: config.fallbackOutputCostPerMillionMicros,
    ragEnabled: config.ragEnabled,
    ragTopK: config.ragTopK,
    contextMaxMessages: config.contextMaxMessages,
    contextSummaryThreshold: config.contextSummaryThreshold,
    contextRetentionDays: config.contextRetentionDays,
    qualityEvaluationEnabled: config.qualityEvaluationEnabled,
  };
}

export default function AiAdminPage() {
  const router = useRouter();
  const { locale, phrase } = useLanguage();
  const [token, setToken] = useState("");
  const [user, setUser] = useState<AuthUser | null>(null);
  const [config, setConfig] = useState<AiAdminConfiguration | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [apiKey, setApiKey] = useState("");
  const [clearApiKey, setClearApiKey] = useState(false);
  const [fallbackApiKey, setFallbackApiKey] = useState("");
  const [clearFallbackApiKey, setClearFallbackApiKey] = useState(false);
  const [models, setModels] = useState<AiModelOption[]>([]);
  const [fallbackModels, setFallbackModels] = useState<AiModelOption[]>([]);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [fallbackModelsLoading, setFallbackModelsLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [testing, setTesting] = useState(false);
  const [overview, setOverview] = useState<AiInvocationOverview | null>(null);
  const [toolAudits, setToolAudits] = useState<AiToolInvocationAudit[]>([]);

  async function load(currentToken: string) {
    const [next, nextOverview, nextToolAudits] = await Promise.all([getAiAdminConfiguration(currentToken), getAiAdminInvocations(currentToken), getAiAdminToolInvocations(currentToken)]);
    setConfig(next);
    setDraft(toDraft(next));
    setOverview(nextOverview);
    setToolAudits(nextToolAudits.items);
    setApiKey("");
    setClearApiKey(false);
    setFallbackApiKey("");
    setClearFallbackApiKey(false);
    setModels([]);
    setFallbackModels([]);
  }

  async function testConnection() {
    if (!token || testing) return;
    setTesting(true);
    setError("");
    try {
      const result = await testAiAdminConnection(token);
      setNotice(phrase(`连接成功，耗时 ${result.durationMs} ms。`, `Connection succeeded in ${result.durationMs} ms.`));
      setOverview(await getAiAdminInvocations(token));
      setToolAudits((await getAiAdminToolInvocations(token)).items);
    } catch (testError) {
      setError(testError instanceof Error ? testError.message : phrase("AI 连接测试失败。", "AI connection test failed."));
      setOverview(await getAiAdminInvocations(token).catch(() => overview));
      setToolAudits((await getAiAdminToolInvocations(token).catch(() => ({ items: toolAudits }))).items);
    } finally {
      setTesting(false);
    }
  }

  async function refresh() {
    if (!token) return;
    setError("");
    try {
      await load(token);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : phrase("AI 配置读取失败。", "Could not load AI settings."));
    }
  }

  useEffect(() => {
    const currentToken = readAccessToken();
    if (!currentToken) {
      router.replace(localizedPath("/login", locale));
      return;
    }
    let active = true;
    void getMe(currentToken).then(async (currentUser) => {
      if (!active) return;
      setUser(currentUser);
      setToken(currentToken);
      if (currentUser.isSuperAdmin) await load(currentToken);
    }).catch((loadError) => {
      if (isAuthExpiredError(loadError)) {
        clearAuthTokens();
        router.replace(localizedPath("/", locale));
        return;
      }
      if (active) setError(loadError instanceof Error ? loadError.message : phrase("AI 配置读取失败。", "Could not load AI settings."));
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [locale, phrase, router]);

  function update<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((current) => current ? { ...current, [key]: value } : current);
  }

  function handleProviderChange(provider: AiProvider) {
    setModels([]);
    setDraft((current) => {
      if (!current) return current;
      const knownBaseUrls = Object.values(PROVIDER_DEFAULT_BASE_URLS);
      const shouldUseProviderDefault = !current.baseUrl.trim() || knownBaseUrls.includes(current.baseUrl);
      return { ...current, provider, baseUrl: shouldUseProviderDefault ? (PROVIDER_DEFAULT_BASE_URLS[provider] ?? current.baseUrl) : current.baseUrl };
    });
  }

  function handleFallbackProviderChange(provider: AiProvider) {
    setFallbackModels([]);
    setDraft((current) => {
      if (!current) return current;
      const knownBaseUrls = Object.values(PROVIDER_DEFAULT_BASE_URLS);
      const currentBaseUrl = current.fallbackBaseUrl ?? "";
      const shouldUseProviderDefault = !currentBaseUrl.trim() || knownBaseUrls.includes(currentBaseUrl);
      return { ...current, fallbackProvider: provider, fallbackBaseUrl: shouldUseProviderDefault ? (PROVIDER_DEFAULT_BASE_URLS[provider] ?? currentBaseUrl) : currentBaseUrl };
    });
  }

  async function fetchModels() {
    if (!token || !draft || modelsLoading) return;
    setModelsLoading(true);
    setError("");
    try {
      const result = await listAiAdminModels(token, { provider: draft.provider, baseUrl: draft.baseUrl, apiKey: apiKey.trim() || undefined });
      setModels(result.models);
      if (!draft.model && result.models[0]) update("model", result.models[0].id);
      setNotice(phrase(`已获取 ${result.models.length} 个模型。`, `${result.models.length} models loaded.`));
    } catch (modelError) {
      setError(modelError instanceof Error ? modelError.message : phrase("模型列表获取失败。", "Could not load the model list."));
    } finally {
      setModelsLoading(false);
    }
  }

  async function fetchFallbackModels() {
    if (!token || !draft || fallbackModelsLoading) return;
    setFallbackModelsLoading(true);
    setError("");
    try {
      const result = await listAiAdminModels(token, { credential: "fallback", provider: draft.fallbackProvider ?? "custom", baseUrl: draft.fallbackBaseUrl ?? "", apiKey: fallbackApiKey.trim() || undefined });
      setFallbackModels(result.models);
      if (!draft.fallbackModel && result.models[0]) update("fallbackModel", result.models[0].id);
      setNotice(phrase(`已获取 ${result.models.length} 个备用模型。`, `${result.models.length} fallback models loaded.`));
    } catch (modelError) {
      setError(modelError instanceof Error ? modelError.message : phrase("备用模型列表获取失败。", "Could not load the fallback model list."));
    } finally {
      setFallbackModelsLoading(false);
    }
  }

  function pricingPresetFor(draftValue: Draft | null): AiPricingPreset | null {
    if (!draftValue || !config) return null;
    const model = draftValue.model.trim().toLowerCase();
    return config.pricingPresets.find((preset) => preset.provider === draftValue.provider && preset.model.toLowerCase() === model) ?? null;
  }

  function applyPricingPreset() {
    if (!draft) return;
    const preset = pricingPresetFor(draft);
    if (!preset) return;
    setDraft((current) => current ? { ...current, billingCurrency: preset.billingCurrency, inputCostPerMillionMicros: preset.inputCostPerMillionMicros, cachedInputCostPerMillionMicros: preset.cachedInputCostPerMillionMicros, outputCostPerMillionMicros: preset.outputCostPerMillionMicros } : current);
    setNotice(phrase(`已套用 ${preset.label} 的价格参考。`, `Applied the ${preset.label} pricing reference.`));
  }

  function clearSavedApiKey(event: MouseEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    setApiKey("");
    setClearApiKey((current) => !current);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!token || !draft || saving) return;
    setSaving(true);
    setError("");
    try {
      const saved = await updateAiAdminConfiguration(token, { ...draft, apiKey: apiKey.trim() || undefined, clearApiKey, fallbackApiKey: fallbackApiKey.trim() || undefined, clearFallbackApiKey });
      setConfig(saved);
      setDraft(toDraft(saved));
      setApiKey("");
      setClearApiKey(false);
      setFallbackApiKey("");
      setClearFallbackApiKey(false);
      setFallbackModels([]);
      setNotice(phrase("AI 配置已保存。", "AI settings saved."));
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : phrase("AI 配置保存失败。", "Could not save AI settings."));
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <AdminPageLoading title={phrase("AI 配置", "AI settings")} description={phrase("配置外部 AI 服务和资源保护参数。", "Configure an external AI service and resource limits.")} loadingLabel={phrase("正在读取 AI 配置", "Loading AI settings")} />;
  if (!user?.isSuperAdmin) return <section className="page-shell admin-shell"><AdminPageHeader title={phrase("无权访问", "Access denied")} description={phrase("AI 配置仅超级管理员可管理。", "AI settings can be managed only by the super admin.")} /></section>;
  if (!config || !draft) return <section className="page-shell admin-shell"><AdminPageHeader title={phrase("AI 配置", "AI settings")} description={phrase("暂时无法读取 AI 配置。", "AI settings are temporarily unavailable.")} /><AppToast message={error || phrase("AI 配置读取失败。", "Could not load AI settings.")} onDismiss={() => setError("")} tone="error" /></section>;
  const recommendation = config.recommendation;
  const providerOptions = [
    { value: "openai" as const, label: "OpenAI" },
    { value: "deepseek" as const, label: "DeepSeek" },
    { value: "custom" as const, label: phrase("通用第三方（OpenAI 兼容）", "Generic third party (OpenAI-compatible)") },
    { value: "anthropic" as const, label: "Anthropic" },
    { value: "google" as const, label: "Google" },
  ];
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
  const pricingPreset = pricingPresetFor(draft);

  return <section className="page-shell admin-shell ai-admin-page">
    <AdminPageHeader title={phrase("AI 配置", "AI settings")} description={phrase("配置外部模型、密钥和并发保护。未启用时不会产生 AI 请求。", "Configure the external model, key, and concurrency protection. No AI requests are made while disabled.")} actions={<button className="admin-header-icon-action" onClick={() => void refresh()} title={phrase("刷新", "Refresh")} type="button"><RefreshCw size={17} /></button>} />
    <section className="ai-resource-summary">
      <header><span><Server size={17} />{phrase("服务器推荐", "Server recommendation")}</span><small>{phrase("根据当前运行环境动态计算", "Calculated from the current runtime")}</small></header>
      <div className="ai-resource-grid"><span><Cpu size={15} /><strong>{recommendation.cpuCores}</strong><small>{phrase("CPU 核心", "CPU cores")}</small></span><span><Database size={15} /><strong>{recommendation.totalMemoryMiB} MiB</strong><small>{phrase("总内存", "Total memory")}</small></span><span><BrainCircuit size={15} /><strong>{recommendation.globalConcurrency}</strong><small>{phrase("推荐全站并发", "Recommended global concurrency")}</small></span><span><ShieldCheck size={15} /><strong>{recommendation.userConcurrency}</strong><small>{phrase("推荐单用户并发", "Recommended per-user concurrency")}</small></span></div>
      <p>{phrase(`当前保护上限：全站 ${recommendation.maxGlobalConcurrency}，单用户 ${recommendation.maxUserConcurrency}。推荐值会随服务器资源变化，保存时仍会执行上限校验。`, `Current protection limits: global ${recommendation.maxGlobalConcurrency}, per user ${recommendation.maxUserConcurrency}. Recommendations follow server resources and are enforced on save.`)}</p>
    </section>
    <form className="ai-config-panel" onSubmit={(event) => void submit(event)}>
      <header><span><BrainCircuit size={17} />{phrase("模型连接", "Model connection")}</span><small>{config.encryptionConfigured ? phrase("密钥加密已就绪", "Secret encryption ready") : phrase("未配置密钥加密", "Secret encryption unavailable")}</small></header>
      <label className="ai-config-toggle"><span>{phrase("启用 AI 功能", "Enable AI features")}</span><input checked={draft.enabled} onChange={(event) => update("enabled", event.target.checked)} type="checkbox" /></label>
      <div className="ai-config-fields"><label><span>{phrase("供应商", "Provider")}</span><GlassSelect ariaLabel={phrase("供应商", "Provider")} leadingIcon={<BrainCircuit size={14} />} menuPortal onChange={(value) => handleProviderChange(value as AiProvider)} options={providerOptions} value={draft.provider} /></label><label><span>{phrase("接口地址", "Base URL")}</span><input onChange={(event) => update("baseUrl", event.target.value)} placeholder={PROVIDER_DEFAULT_BASE_URLS[draft.provider] ?? "https://api.example.com/v1"} value={draft.baseUrl} /></label><label><span>{phrase("模型名称", "Model")}</span><div className="ai-model-picker"><GlassSelect ariaLabel={phrase("模型名称", "Model")} leadingIcon={<BrainCircuit size={14} />} menuClassName="ai-model-menu" menuPortal onChange={(value) => update("model", value)} options={modelOptions} value={draft.model} /><button aria-label={phrase("获取模型列表", "Load models")} className="ai-model-fetch-button" disabled={modelsLoading || !draft.baseUrl.trim()} onClick={() => void fetchModels()} title={phrase("获取模型列表", "Load model list")} type="button">{modelsLoading ? <RefreshCw className="spin" size={15} /> : <Download size={15} />}</button></div></label><label><span><KeyRound size={13} /> API Key {config.apiKeyConfigured ? phrase("（已配置，留空保持不变）", "(configured; leave blank to keep)") : ""}</span><div className={`ai-api-key-field${config.apiKeyConfigured ? " has-clear-key" : ""}`}><PasswordInput autoComplete="new-password" disabled={!config.encryptionConfigured} onChange={(event) => { setApiKey(event.target.value); if (event.target.value) setClearApiKey(false); }} placeholder={clearApiKey ? phrase("保存时清除 API Key", "API key will be cleared on save") : config.apiKeyConfigured ? phrase("已配置，留空保持不变", "Configured; leave blank to keep") : phrase("输入 API Key", "Enter API key")} value={apiKey} />{config.apiKeyConfigured ? <button aria-pressed={clearApiKey} aria-label={phrase("清除已保存的 API Key", "Clear the saved API key")} className={`ai-clear-key-button${clearApiKey ? " active" : ""}`} onClick={clearSavedApiKey} title={phrase("清除已保存的 API Key", "Clear the saved API key")} type="button"><Trash2 size={15} /></button> : null}</div></label></div>
       <section className="ai-fallback-panel"><label className="ai-config-toggle"><span>{phrase("启用备用模型（仅临时故障自动切换）", "Enable fallback model (transient failures only)")}</span><input checked={Boolean(draft.fallbackEnabled)} onChange={(event) => update("fallbackEnabled", event.target.checked)} type="checkbox" /></label><small>{phrase("仅在超时、网络暂时不可达、429 或 5xx 时切换；权限错误、参数错误和模型不存在不会重试。", "Switches only for timeouts, transient network failures, 429, or 5xx; authentication, parameter, and missing-model errors are not retried.")}</small>{draft.fallbackEnabled ? <div className="ai-config-fields"><label><span>{phrase("备用供应商", "Fallback provider")}</span><GlassSelect ariaLabel={phrase("备用供应商", "Fallback provider")} onChange={(value) => handleFallbackProviderChange(value as AiProvider)} options={providerOptions} value={draft.fallbackProvider ?? "custom"} /></label><label><span>{phrase("备用接口地址", "Fallback base URL")}</span><input onChange={(event) => update("fallbackBaseUrl", event.target.value)} placeholder={PROVIDER_DEFAULT_BASE_URLS[draft.fallbackProvider ?? "custom"] ?? "https://api.example.com/v1"} value={draft.fallbackBaseUrl ?? ""} /></label><label><span>{phrase("备用模型", "Fallback model")}</span><div className="ai-model-picker"><GlassSelect ariaLabel={phrase("备用模型", "Fallback model")} menuClassName="ai-model-menu" menuPortal onChange={(value) => update("fallbackModel", value)} options={fallbackModelOptions} value={draft.fallbackModel ?? ""} /><button aria-label={phrase("获取备用模型列表", "Load fallback models")} className="ai-model-fetch-button" disabled={fallbackModelsLoading || !(draft.fallbackBaseUrl ?? "").trim()} onClick={() => void fetchFallbackModels()} title={phrase("获取备用模型列表", "Load fallback model list")} type="button">{fallbackModelsLoading ? <RefreshCw className="spin" size={15} /> : <Download size={15} />}</button></div></label><label><span>{phrase("备用 API Key", "Fallback API key")}{config.fallbackApiKeyConfigured ? phrase("（已配置，留空保持不变）", "(configured; leave blank to keep)") : ""}</span><div className={"ai-api-key-field" + (config.fallbackApiKeyConfigured ? " has-clear-key" : "")}><PasswordInput autoComplete="new-password" disabled={!config.encryptionConfigured} onChange={(event) => { setFallbackApiKey(event.target.value); if (event.target.value) setClearFallbackApiKey(false); }} placeholder={clearFallbackApiKey ? phrase("保存时清除备用 Key", "Fallback key will be cleared on save") : config.fallbackApiKeyConfigured ? phrase("已配置，留空保持不变", "Configured; leave blank to keep") : phrase("输入备用 API Key", "Enter fallback API key")} value={fallbackApiKey} />{config.fallbackApiKeyConfigured ? <button aria-pressed={clearFallbackApiKey} aria-label={phrase("清除已保存的备用 API Key", "Clear the saved fallback API key")} className={"ai-clear-key-button" + (clearFallbackApiKey ? " active" : "")} onClick={(event) => { event.preventDefault(); event.stopPropagation(); setFallbackApiKey(""); setClearFallbackApiKey((current) => !current); }} title={phrase("清除已保存的备用 API Key", "Clear the saved fallback API key")} type="button"><Trash2 size={15} /></button> : null}</div></label></div> : null}</section>
      <div className="ai-pricing-grid">
        <label><span>{phrase("输入价 / 百万 tokens", "Input price / million tokens")}</span><input min={0} onChange={(event) => update("inputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={draft.inputCostPerMillionMicros / 1000000} /></label>
        <label><span>{phrase("备用输入价 / 百万 tokens", "Fallback input price / million tokens")}</span><input min={0} onChange={(event) => update("fallbackInputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={(draft.fallbackInputCostPerMillionMicros ?? 0) / 1000000} /></label>
        <label><span>{phrase("缓存输入价 / 百万 tokens", "Cached input price / million tokens")}</span><input min={0} onChange={(event) => update("cachedInputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={(draft.cachedInputCostPerMillionMicros ?? 0) / 1000000} /></label>
        <label><span>{phrase("备用缓存输入价 / 百万 tokens", "Fallback cached input price / million tokens")}</span><input min={0} onChange={(event) => update("fallbackCachedInputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={(draft.fallbackCachedInputCostPerMillionMicros ?? 0) / 1000000} /></label>
        <label><span className="ai-pricing-label"><span>{phrase("输出价 / 百万 tokens", "Output price / million tokens")}</span>{pricingPreset ? <button aria-label={phrase("套用模型价格预设", "Apply model pricing preset")} className="ai-pricing-preset-button" onClick={applyPricingPreset} title={phrase(`${pricingPreset.label}：套用价格参考`, `Apply ${pricingPreset.label} pricing reference`)} type="button"><Sparkles size={13} /></button> : null}</span><input min={0} onChange={(event) => update("outputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={draft.outputCostPerMillionMicros / 1000000} /></label>
        <label><span>{phrase("备用输出价 / 百万 tokens", "Fallback output price / million tokens")}</span><input min={0} onChange={(event) => update("fallbackOutputCostPerMillionMicros", Math.round(Number(event.target.value) * 1000000))} step={0.000001} type="number" value={(draft.fallbackOutputCostPerMillionMicros ?? 0) / 1000000} /></label>
      </div>{pricingPreset ? <p className="ai-pricing-preset-note">{phrase(`${pricingPreset.note} 点击输出价格右侧图标可套用。`, `${pricingPreset.note} Use the icon beside output price to apply it.`)}</p> : null}
      <div className="ai-config-fields ai-limit-fields"><label><span>{phrase("全站并发", "Global concurrency")}</span><input max={recommendation.maxGlobalConcurrency} min={1} onChange={(event) => update("globalConcurrency", Number(event.target.value))} type="number" value={draft.globalConcurrency} /></label><label><span>{phrase("单用户并发", "Per-user concurrency")}</span><input max={recommendation.maxUserConcurrency} min={1} onChange={(event) => update("userConcurrency", Number(event.target.value))} type="number" value={draft.userConcurrency} /></label><label><span>{phrase("最大输出 tokens", "Max output tokens")}</span><input max={8000} min={256} onChange={(event) => update("maxOutputTokens", Number(event.target.value))} type="number" value={draft.maxOutputTokens} /></label><label><span>{phrase("请求超时（秒）", "Request timeout (seconds)")}</span><input max={300} min={10} onChange={(event) => update("requestTimeoutSeconds", Number(event.target.value))} type="number" value={draft.requestTimeoutSeconds} /></label><label><span>{phrase("全站每日请求上限（0 为不限）", "Global daily request limit (0 = unlimited)")}</span><input max={100000} min={0} onChange={(event) => update("dailyRequestLimit", Number(event.target.value))} type="number" value={draft.dailyRequestLimit} /></label><label className="ai-currency-field"><span>{phrase("计价币种", "Billing currency")}</span><GlassSelect ariaLabel={phrase("计价币种", "Billing currency")} leadingIcon={<Activity size={14} />} menuClassName="ai-currency-menu" menuPortal onChange={(value) => update("billingCurrency", value)} options={[{ value: "USD", label: "USD" }, { value: "CNY", label: "CNY" }]} value={draft.billingCurrency} /></label></div>
      <div className="ai-context-settings"><div className="ai-context-toggles"><label className="ai-config-toggle"><span>{phrase("启用知识库检索", "Enable knowledge retrieval")}</span><input checked={Boolean(draft.ragEnabled)} onChange={(event) => update("ragEnabled", event.target.checked)} type="checkbox" /></label><label className="ai-config-toggle"><span>{phrase("记录回答质量指标", "Record quality metrics")}</span><input checked={Boolean(draft.qualityEvaluationEnabled)} onChange={(event) => update("qualityEvaluationEnabled", event.target.checked)} type="checkbox" /></label></div><div className="ai-context-number-fields"><label><span>{phrase("每次最多引用片段", "Max retrieved chunks")}</span><input max={20} min={1} onChange={(event) => update("ragTopK", Number(event.target.value))} type="number" value={draft.ragTopK ?? 6} /></label><label><span>{phrase("保留最近消息数", "Recent messages")}</span><input max={80} min={4} onChange={(event) => update("contextMaxMessages", Number(event.target.value))} type="number" value={draft.contextMaxMessages ?? 24} /></label><label><span>{phrase("自动压缩阈值", "Summary threshold")}</span><input max={200} min={8} onChange={(event) => update("contextSummaryThreshold", Number(event.target.value))} type="number" value={draft.contextSummaryThreshold ?? 32} /></label><label><span>{phrase("上下文保留天数", "Context retention days")}</span><input max={3650} min={1} onChange={(event) => update("contextRetentionDays", Number(event.target.value))} type="number" value={draft.contextRetentionDays ?? 365} /></label></div></div>
      <footer><small>{phrase("密钥仅用于服务端调用，不会返回到浏览器；缓存输入价填 0 表示不估算缓存折扣。", "The key is used only for server-side calls and is never returned to the browser. A cached input price of 0 disables cache-discount estimation.")}</small><div className="ai-config-actions"><button className="button ai-test-button" disabled={testing || saving || !config.apiKeyConfigured} onClick={() => void testConnection()} type="button"><PlugZap size={15} />{testing ? phrase("测试中", "Testing") : phrase("测试连接", "Test connection")}</button><button className="button" disabled={saving || !config.encryptionConfigured} type="submit"><Save size={15} />{saving ? phrase("保存中", "Saving") : phrase("保存配置", "Save settings")}</button></div></footer>
    </form>
    <AiCapabilitiesPanel token={token} />
    <section className="ai-log-panel">
      <header><span><Activity size={17} />{phrase("调用记录", "Invocation log")}</span><small>{overview ? phrase(`今日 ${overview.today.requests} 次，${overview.today.totalTokens} tokens，缓存命中 ${overview.today.cachedPromptTokens}（${overview.today.cacheHitRate}%），约 ${(overview.today.estimatedCostMicros / 1000000).toFixed(6)} ${config.billingCurrency}`, `${overview.today.requests} today, ${overview.today.totalTokens} tokens, ${overview.today.cachedPromptTokens} cached (${overview.today.cacheHitRate}%), about ${(overview.today.estimatedCostMicros / 1000000).toFixed(6)} ${config.billingCurrency}`) : phrase("暂无记录", "No records")}</small></header>
      {overview?.logs.length ? <div className="ai-log-list">{overview.logs.map((log) => <div className="ai-log-row" key={log.id}><span className={log.status === "success" ? "ai-log-status success" : "ai-log-status failed"}>{log.status === "success" ? <CheckCircle2 size={14} /> : <CircleAlert size={14} />}{log.status === "success" ? phrase("成功", "Success") : phrase("失败", "Failed")}</span><span>{log.operation === "test_connection" ? phrase("连接测试", "Connection test") : log.operation}</span><span>{log.model || "-"}</span><span>{log.totalTokens ?? "-"} tokens</span><span>{log.cachedPromptTokens ? `${log.cachedPromptTokens} cached` : "-"}</span><span>{log.durationMs} ms</span><time dateTime={log.createdAt}>{new Date(log.createdAt).toLocaleString(locale === "zh-CN" ? "zh-CN" : "en-US")}</time>{log.errorSummary ? <small>{log.errorSummary}</small> : null}</div>)}</div> : <p className="ai-log-empty">{phrase("连接测试或文章助手调用后，脱敏记录会显示在这里。", "Redacted records appear here after a connection test or assistant invocation.")}</p>}
    </section>
    <section className="ai-tool-audit-panel">
      <header><span><ShieldCheck size={17} />{phrase("工具审计", "Tool audit")}</span><small>{phrase("仅显示元数据，不显示输入参数和输出正文", "Metadata only; inputs and outputs are hidden")}</small></header>
      {toolAudits.length ? <div className="ai-tool-audit-list">{toolAudits.map((audit) => <div className="ai-tool-audit-row" key={audit.id}><span className={audit.status === "success" ? "ai-log-status success" : audit.status === "awaiting_confirmation" ? "ai-tool-audit-status pending" : "ai-log-status failed"}>{audit.status === "success" ? <CheckCircle2 size={14} /> : <CircleAlert size={14} />}{audit.status === "success" ? phrase("成功", "Success") : audit.status === "awaiting_confirmation" ? phrase("待确认", "Awaiting confirmation") : phrase("失败", "Failed")}</span><strong>{audit.toolName}</strong><span>{audit.nickname || audit.username} <small>@{audit.username}</small></span><span>{audit.requiresConfirmation ? phrase("需确认", "Confirmation required") : phrase("只读", "Read-only")}</span><time dateTime={audit.createdAt}>{new Date(audit.createdAt).toLocaleString(locale === "zh-CN" ? "zh-CN" : "en-US")}</time></div>)}</div> : <p className="ai-log-empty">{phrase("用户执行受控工具后，审计记录会显示在这里。", "Tool audit records appear here after users run controlled tools.")}</p>}
    </section>
    <AppToast message={error || notice} onDismiss={() => { setError(""); setNotice(""); }} tone={error ? "error" : "success"} />
  </section>;
}
