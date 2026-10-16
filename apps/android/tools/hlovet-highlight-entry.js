import hljs from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/core.js";
import bash from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/bash.js";
import c from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/c.js";
import cpp from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/cpp.js";
import csharp from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/csharp.js";
import css from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/css.js";
import go from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/go.js";
import java from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/java.js";
import javascript from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/javascript.js";
import json from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/json.js";
import markdown from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/markdown.js";
import php from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/php.js";
import python from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/python.js";
import rust from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/rust.js";
import sql from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/sql.js";
import typescript from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/typescript.js";
import xml from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/xml.js";
import yaml from "../../../node_modules/.pnpm/highlight.js@11.11.2/node_modules/highlight.js/es/languages/yaml.js";

[
  ["bash", bash],
  ["c", c],
  ["cpp", cpp],
  ["csharp", csharp],
  ["css", css],
  ["go", go],
  ["java", java],
  ["javascript", javascript],
  ["json", json],
  ["markdown", markdown],
  ["php", php],
  ["python", python],
  ["rust", rust],
  ["sql", sql],
  ["typescript", typescript],
  ["xml", xml],
  ["yaml", yaml],
].forEach(([name, language]) => hljs.registerLanguage(name, language));

const aliases = {
  "c#": "csharp",
  cs: "csharp",
  html: "xml",
  js: "javascript",
  jsx: "javascript",
  md: "markdown",
  py: "python",
  shell: "bash",
  sh: "bash",
  ts: "typescript",
  tsx: "typescript",
  yml: "yaml",
};

function codeLanguage(pre, code) {
  const classLanguage = Array.from(code.classList || [])
    .find((name) => name.startsWith("language-"));
  const raw = (pre.getAttribute("data-language") || classLanguage || "plaintext")
    .replace(/^language-/i, "")
    .trim()
    .toLowerCase();
  return aliases[raw] || raw || "plaintext";
}

function highlight(source, language) {
  if (!source || language === "plaintext" || !hljs.getLanguage(language)) {
    return source
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }
  return hljs.highlight(source, { language, ignoreIllegals: true }).value;
}

function highlightReadOnly(root) {
  (root || document).querySelectorAll("pre > code").forEach((code) => {
    const pre = code.parentElement;
    if (!pre || pre.classList.contains("hlovet-code-editor")) return;
    const language = codeLanguage(pre, code);
    code.innerHTML = highlight(code.textContent || "", language);
    code.classList.add("hljs");
  });
}

function editorOverlay(pre) {
  if (!pre) return null;
  const code = pre.querySelector(":scope > code");
  if (!code) return null;
  let overlay = pre.querySelector(":scope > .hlovet-code-highlight");
  if (!overlay) {
    overlay = document.createElement("div");
    overlay.className = "hlovet-code-highlight hljs";
    overlay.setAttribute("contenteditable", "false");
    overlay.setAttribute("aria-hidden", "true");
    pre.insertBefore(overlay, code);
  }
  pre.classList.add("hlovet-code-editor");
  code.classList.add("hlovet-code-source");
  overlay.innerHTML = highlight(code.textContent || "", codeLanguage(pre, code));
  return overlay;
}

function refreshEditor(root, target) {
  const editor = root || document.getElementById("editor");
  if (!editor) return;
  const element = target && target.nodeType === Node.ELEMENT_NODE
    ? target
    : target && target.parentElement;
  const pre = element && element.closest ? element.closest("pre") : null;
  if (pre && editor.contains(pre)) {
    editorOverlay(pre);
    return;
  }
  editor.querySelectorAll("pre").forEach(editorOverlay);
}

function stripEditorArtifacts(root) {
  if (!root) return root;
  root.querySelectorAll(".hlovet-code-highlight").forEach((node) => node.remove());
  root.querySelectorAll(".hlovet-code-editor").forEach((node) => node.classList.remove("hlovet-code-editor"));
  root.querySelectorAll(".hlovet-code-source").forEach((node) => node.classList.remove("hlovet-code-source"));
  root.querySelectorAll("code.hljs").forEach((node) => node.classList.remove("hljs"));
  return root;
}

function serializeEditor(editor) {
  if (!editor) return "";
  const clone = editor.cloneNode(true);
  stripEditorArtifacts(clone);
  return clone.innerHTML;
}

function initEditor(editor) {
  if (!editor) return;
  refreshEditor(editor);
  if (editor.dataset.hlovetHighlightBound === "true") return;
  editor.dataset.hlovetHighlightBound = "true";
  editor.addEventListener("input", (event) => {
    window.requestAnimationFrame(() => refreshEditor(editor, event.target));
  });
}

window.HlovetSyntax = {
  highlightReadOnly,
  initEditor,
  refreshEditor,
  serializeEditor,
  stripEditorArtifacts,
};
