"use client";

import { ExternalLink, FileScan, Image as ImageIcon, LoaderCircle, Mic, RefreshCw, Sparkles, Upload, X } from "lucide-react";
import { createPortal } from "react-dom";
import { useEffect, useMemo, useState } from "react";
import { AppToast } from "@/components/app-toast";
import { GlassSelect } from "@/components/glass-select";
import { useLanguage } from "@/components/language-provider";
import { getAiMediaTask, getAiUserMediaCapabilities, downloadAiMediaTaskImage, listAiMediaTasks, runAiImageGeneration, runAiOcr, runAiTranscription, type AiMediaTaskDetail, type AiMediaTaskSummary, type AiUserMediaCapability } from "@/lib/ai-api";

type MediaKind = "ocr" | "transcription" | "image_generation";

const labels: Record<MediaKind, { zh: string; en: string }> = {
  ocr: { zh: "图片 OCR", en: "Image OCR" },
  transcription: { zh: "语音转文字", en: "Transcription" },
  image_generation: { zh: "图片生成", en: "Image generation" },
};

function formatTaskTime(value: string, locale: string): string {
  return new Date(value).toLocaleString(locale === "zh-CN" ? "zh-CN" : "en-US", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function AiMediaTools({ token, onNotice, onError }: { token: string; onNotice: (message: string) => void; onError: (message: string) => void }) {
  const { locale, phrase } = useLanguage();
  const [capabilities, setCapabilities] = useState<AiUserMediaCapability[]>([]);
  const [tasks, setTasks] = useState<AiMediaTaskSummary[]>([]);
  const [kind, setKind] = useState<MediaKind | null>(null);
  const [selected, setSelected] = useState<AiMediaTaskDetail | null>(null);
  const [imageUrl, setImageUrl] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [prompt, setPrompt] = useState("");
  const [size, setSize] = useState("1024x1024");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const capabilityMap = useMemo(() => new Map(capabilities.map((item) => [item.capability, item])), [capabilities]);

  async function load() {
    setLoading(true);
    try {
      const [capabilityResult, taskResult] = await Promise.all([getAiUserMediaCapabilities(token), listAiMediaTasks(token)]);
      setCapabilities(capabilityResult.items);
      setTasks(taskResult.items);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : phrase("媒体能力读取失败。", "Could not load AI media capabilities."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    const loadTimer = window.setTimeout(() => { void load(); }, 0);
    return () => {
      window.clearTimeout(loadTimer);
      if (imageUrl) URL.revokeObjectURL(imageUrl);
    };
    // The token is the only identity input; imageUrl is intentionally not a reload dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  function resetModal() {
    if (imageUrl) URL.revokeObjectURL(imageUrl);
    setKind(null);
    setSelected(null);
    setImageUrl("");
    setFile(null);
    setPrompt("");
    setSize("1024x1024");
  }

  function open(kindValue: MediaKind) {
    if (!capabilityMap.get(kindValue)?.available) return;
    resetModal();
    setKind(kindValue);
  }

  async function showTask(task: AiMediaTaskSummary) {
    setKind(task.capability);
    setSelected(null);
    setError("");
    try {
      const detail = await getAiMediaTask(token, task.id);
      setSelected(detail);
      if (detail.hasStoredImage) {
        const blob = await downloadAiMediaTaskImage(token, task.id);
        setImageUrl(URL.createObjectURL(blob));
      }
    } catch (taskError) {
      setError(taskError instanceof Error ? taskError.message : phrase("媒体结果读取失败。", "Could not load the media result."));
    }
  }

  async function submit() {
    if (!kind || submitting) return;
    if (kind !== "image_generation" && !file) {
      setError(phrase("请选择文件。", "Choose a file first."));
      return;
    }
    if (kind === "image_generation" && !prompt.trim()) {
      setError(phrase("请输入图片描述。", "Enter an image prompt."));
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      const result = kind === "ocr"
        ? await runAiOcr(token, file!, prompt)
        : kind === "transcription"
          ? await runAiTranscription(token, file!)
          : await runAiImageGeneration(token, prompt, size);
      setSelected(result);
      if (result.hasStoredImage) {
        const blob = await downloadAiMediaTaskImage(token, result.id);
        setImageUrl(URL.createObjectURL(blob));
      }
      await load();
      onNotice(phrase(`${labels[kind].zh}已完成。`, `${labels[kind].en} completed.`));
    } catch (submitError) {
      const message = submitError instanceof Error ? submitError.message : phrase("媒体任务执行失败。", "The media task failed.");
      setError(message);
      onError(message);
    } finally {
      setSubmitting(false);
    }
  }

  function taskStatus(status: string) {
    if (status === "completed") return phrase("完成", "Completed");
    if (status === "failed") return phrase("失败", "Failed");
    if (status === "processing") return phrase("处理中", "Processing");
    return phrase("排队中", "Queued");
  }

  const selectedLabel = kind ? phrase(labels[kind].zh, labels[kind].en) : "";

  return <section className="ai-media-tools">
    <header><span><Sparkles size={15} />{phrase("媒体工具", "Media tools")}</span><button aria-label={phrase("刷新媒体任务", "Refresh media tasks")} className="ai-media-refresh" disabled={loading} onClick={() => void load()} title={phrase("刷新", "Refresh")} type="button"><RefreshCw className={loading ? "spin" : ""} size={14} /></button></header>
    <p className="ai-media-help">{phrase("OCR、语音和图片生成由后台独立控制；未配置时按钮会保持不可用。", "OCR, transcription, and image generation are controlled independently by the admin; unavailable capabilities stay disabled.")}</p>
    <div className="ai-media-action-grid">
      {(["ocr", "transcription", "image_generation"] as MediaKind[]).map((item) => {
        const Icon = item === "ocr" ? FileScan : item === "transcription" ? Mic : ImageIcon;
        const available = capabilityMap.get(item)?.available ?? false;
        return <button className="ai-media-action" disabled={!available || loading || submitting} key={item} onClick={() => open(item)} type="button"><Icon size={15} /><span><strong>{phrase(labels[item].zh, labels[item].en)}</strong><small>{available ? phrase("已配置，可使用", "Configured") : phrase("未配置", "Not configured")}</small></span></button>;
      })}
    </div>
    <div className="ai-media-history"><div className="ai-media-history-title"><span>{phrase("最近任务", "Recent tasks")}</span><small>{tasks.length}</small></div>{tasks.length ? tasks.slice(0, 8).map((task) => <button className="ai-media-task-row" key={task.id} onClick={() => void showTask(task)} type="button"><span><strong>{phrase(labels[task.capability].zh, labels[task.capability].en)}</strong><small>{formatTaskTime(task.createdAt, locale)}</small></span><em className={`status-${task.status}`}>{taskStatus(task.status)}</em></button>) : <small className="ai-media-empty">{phrase("还没有媒体任务。", "No media tasks yet.")}</small>}</div>
    {kind && typeof document !== "undefined" ? createPortal(<div className="modal-backdrop ai-media-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget && !submitting) resetModal(); }} role="presentation"><section aria-modal="true" className="ai-media-modal" onMouseDown={(event) => event.stopPropagation()} role="dialog"><header><span><Sparkles size={17} />{selectedLabel}</span><button aria-label={phrase("关闭", "Close")} disabled={submitting} onClick={resetModal} title={phrase("关闭", "Close")} type="button"><X size={17} /></button></header>{selected ? <div className="ai-media-result">{selected.status === "failed" ? <p className="ai-media-result-error">{selected.errorSummary || phrase("任务失败。", "The task failed.")}</p> : null}{selected.resultText ? <pre>{selected.resultText}</pre> : null}{selected.capability === "image_generation" && imageUrl ? <img alt={phrase("AI 生成图片", "AI generated image")} src={imageUrl} /> : null}{selected.capability === "image_generation" && !imageUrl && selected.resultUrl ? <a href={selected.resultUrl} rel="noreferrer" target="_blank"><ExternalLink size={14} />{phrase("打开图片结果", "Open image result")}</a> : null}{selected.capability === "image_generation" && !imageUrl && !selected.resultUrl && selected.status === "completed" ? <p>{phrase("图片服务没有返回可保存的图片地址。", "The image provider returned no reusable image URL.")}</p> : null}</div> : <div className="ai-media-form">{kind !== "image_generation" ? <label><span>{phrase("选择文件", "Choose file")}</span><div className="ai-media-file-picker"><Upload size={15} /><strong>{file?.name || phrase("点击选择文件", "Choose a file")}</strong><input accept={kind === "ocr" ? "image/*" : "audio/*"} onChange={(event) => setFile(event.target.files?.[0] ?? null)} type="file" /></div></label> : null}{kind === "ocr" ? <label><span>{phrase("识别提示（可选）", "Optional instruction")}</span><textarea maxLength={1000} onChange={(event) => setPrompt(event.target.value)} placeholder={phrase("例如：保留表格和代码结构。", "For example: preserve tables and code structure.")} rows={3} value={prompt} /></label> : null}{kind === "image_generation" ? <><label><span>{phrase("图片描述", "Image prompt")}</span><textarea maxLength={4000} onChange={(event) => setPrompt(event.target.value)} placeholder={phrase("描述你想生成的图片。", "Describe the image you want to generate.")} rows={6} value={prompt} /></label><label><span>{phrase("尺寸", "Size")}</span><GlassSelect ariaLabel={phrase("图片尺寸", "Image size")} onChange={setSize} options={[{ value: "1024x1024", label: "1024 × 1024" }, { value: "1024x1792", label: "1024 × 1792" }, { value: "1792x1024", label: "1792 × 1024" }]} value={size} /></label></> : null}</div>}<footer>{selected ? <button className="button secondary" onClick={() => { setSelected(null); if (imageUrl) { URL.revokeObjectURL(imageUrl); setImageUrl(""); } }} type="button">{phrase("新任务", "New task")}</button> : null}<button className="button" disabled={submitting || (!selected && !capabilityMap.get(kind)?.available)} onClick={() => void (selected ? resetModal() : submit())} type="button">{submitting ? <LoaderCircle className="spin" size={14} /> : null}{selected ? phrase("关闭", "Close") : phrase("开始处理", "Run")}</button></footer></section></div>, document.body) : null}
    <AppToast message={error} onDismiss={() => setError("")} tone="error" />
  </section>;
}
