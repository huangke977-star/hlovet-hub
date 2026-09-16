export const ARTICLE_CODE_BLOCK_LANGUAGES = [
  "plaintext", "html", "css", "javascript", "typescript", "json", "sql", "python",
  "bash", "java", "go", "c", "cpp", "csharp", "php", "rust", "xml", "yaml",
  "markdown",
] as const;

export type ArticleCodeBlockLanguage = (typeof ARTICLE_CODE_BLOCK_LANGUAGES)[number];

const codeBlockLanguages = new Set<string>(ARTICLE_CODE_BLOCK_LANGUAGES);

export function normalizeArticleCodeBlockLanguage(value: unknown): ArticleCodeBlockLanguage {
  const candidate = String(value ?? "").trim().toLowerCase().replace(/^language-/, "");
  return codeBlockLanguages.has(candidate) ? candidate as ArticleCodeBlockLanguage : "plaintext";
}

const allowedTags = new Set([
  "p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "em", "s", "u",
  "blockquote", "ul", "ol", "li", "pre", "code", "a", "img", "hr", "table",
  "thead", "tbody", "tr", "th", "td", "div", "span", "label", "input", "resource-block",
]);

const allowedAttributes: Record<string, Set<string>> = {
  a: new Set(["href", "target", "rel"]),
  img: new Set(["src", "alt", "title"]),
  ul: new Set(["data-type"]),
  li: new Set(["class", "data-type", "data-checked"]),
  input: new Set(["type", "checked"]),
  span: new Set(["style"]),
  pre: new Set(["data-language"]),
  code: new Set(["class"]),
  "resource-block": new Set(["data-points"]),
};

function isSafeUrl(value: string) {
  return value.startsWith("/") || /^(?:https?:\/\/|mailto:)/i.test(value);
}

function isSafeTextStyle(value: string) {
  return /^\s*font-size\s*:\s*(?:\d+(?:\.\d+)?(?:px|rem|em|%)|small|medium|large)\s*;?\s*$/i.test(value);
}

/**
 * Mirrors the article HTML allowlist while a source draft is being previewed.
 * The API remains the authoritative sanitizer before any article is stored.
 */
export function sanitizeArticleHtmlForPreview(source: string) {
  if (typeof DOMParser === "undefined") return "";
  const documentFragment = new DOMParser().parseFromString(`<div>${source}</div>`, "text/html");
  const root = documentFragment.body.firstElementChild;
  if (!root) return "";

  const sanitizeNode = (element: Element) => {
    Array.from(element.children).forEach(sanitizeNode);
    const tag = element.tagName.toLowerCase();
    if (!allowedTags.has(tag)) {
      element.replaceWith(...Array.from(element.childNodes));
      return;
    }

    const supportedAttributes = allowedAttributes[tag] ?? new Set<string>();
    Array.from(element.attributes).forEach((attribute) => {
      const name = attribute.name.toLowerCase();
      const value = attribute.value.trim();
      if (!supportedAttributes.has(name)) {
        element.removeAttribute(attribute.name);
        return;
      }
      if ((name === "href" || name === "src") && !isSafeUrl(value)) {
        element.removeAttribute(attribute.name);
        return;
      }
      if (name === "style" && !isSafeTextStyle(value)) element.removeAttribute(attribute.name);
      if (tag === "code" && name === "class" && !/^language-(?:plaintext|html|css|javascript|typescript|json|sql|python|bash|java|go|c|cpp|csharp|php|rust|xml|yaml|markdown)$/i.test(value)) {
        element.removeAttribute(attribute.name);
      }
    });

    if (tag === "pre") {
      const code = element.querySelector(":scope > code");
      const language = normalizeArticleCodeBlockLanguage(element.getAttribute("data-language") ?? code?.getAttribute("class"));
      element.setAttribute("data-language", language);
      if (code) code.setAttribute("class", `language-${language}`);
    }
  };

  Array.from(root.children).forEach(sanitizeNode);
  return root.innerHTML.trim();
}
