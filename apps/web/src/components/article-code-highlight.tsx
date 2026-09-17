"use client";

import { common, createLowlight } from "lowlight";
import { Fragment, type ReactNode } from "react";
import { normalizeArticleCodeBlockLanguage, type ArticleCodeBlockLanguage } from "@/lib/article-html";

type LowlightNode = {
  children?: LowlightNode[];
  properties?: { className?: unknown };
  type?: string;
  value?: unknown;
};

export const articleLowlight = createLowlight(common);

function highlightNodes(source: string, language: ArticleCodeBlockLanguage): LowlightNode[] | null {
  if (language === "plaintext") return null;
  try {
    return articleLowlight.highlight(language, source).children as LowlightNode[];
  } catch {
    return null;
  }
}

function nodeClassName(node: LowlightNode): string | undefined {
  const className = node.properties?.className;
  if (!Array.isArray(className)) return undefined;
  const values = className.filter((value): value is string => typeof value === "string");
  return values.length ? values.join(" ") : undefined;
}

function renderNodes(nodes: LowlightNode[], keyPrefix: string): ReactNode[] {
  return nodes.map((node, index) => {
    const key = `${keyPrefix}-${index}`;
    if (typeof node.value === "string") return <Fragment key={key}>{node.value}</Fragment>;
    const children = renderNodes(node.children ?? [], key);
    const className = nodeClassName(node);
    return className ? <span className={className} key={key}>{children}</span> : <Fragment key={key}>{children}</Fragment>;
  });
}

export function HighlightedArticleCode({ code, language }: { code: string; language: string | undefined }) {
  const normalizedLanguage = normalizeArticleCodeBlockLanguage(language);
  const nodes = highlightNodes(code, normalizedLanguage);
  return <>{nodes ? renderNodes(nodes, normalizedLanguage) : code}</>;
}

// HTML article content is rendered from a sanitized string. Build the same
// lowlight markup from text nodes so highlighting never changes stored HTML.
export function appendHighlightedArticleCode(documentRef: Document, target: HTMLElement, code: string, language: string | undefined) {
  const normalizedLanguage = normalizeArticleCodeBlockLanguage(language);
  const nodes = highlightNodes(code, normalizedLanguage);
  const fragment = documentRef.createDocumentFragment();

  const appendNodes = (parent: DocumentFragment | HTMLElement, currentNodes: LowlightNode[]) => {
    currentNodes.forEach((node) => {
      if (typeof node.value === "string") {
        parent.append(documentRef.createTextNode(node.value));
        return;
      }
      const span = documentRef.createElement("span");
      const className = nodeClassName(node);
      if (className) span.className = className;
      appendNodes(span, node.children ?? []);
      parent.append(span);
    });
  };

  if (nodes) appendNodes(fragment, nodes);
  else fragment.append(documentRef.createTextNode(code));
  target.replaceChildren(fragment);
}
