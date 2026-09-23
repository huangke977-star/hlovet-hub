"use client";

import { ChevronDown, ChevronUp } from "lucide-react";
import { type ChangeEvent, type ComponentProps, useRef } from "react";

type AiNumberInputProps = Omit<ComponentProps<"input">, "type"> & {
  decreaseLabel?: string;
  increaseLabel?: string;
};

function numericLimit(value: number, limit: string | number | undefined) {
  if (limit === undefined || limit === "") return value;
  const parsed = Number(limit);
  return Number.isFinite(parsed) ? parsed : value;
}

export function AiNumberInput({ decreaseLabel = "Decrease value", increaseLabel = "Increase value", onChange, step, ...props }: AiNumberInputProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  function adjust(direction: 1 | -1) {
    const input = inputRef.current;
    if (!input || props.disabled) return;

    const stepText = String(step ?? 1);
    const parsedStep = Number(stepText);
    const increment = Number.isFinite(parsedStep) && parsedStep > 0 ? parsedStep : 1;
    const precision = stepText.includes(".") ? stepText.split(".")[1]?.length ?? 0 : 0;
    const current = Number(input.value);
    const baseValue = Number.isFinite(current) ? current : numericLimit(0, props.min);
    const next = Math.round((baseValue + (increment * direction)) * (10 ** precision)) / (10 ** precision);
    const minimum = numericLimit(Number.NEGATIVE_INFINITY, props.min);
    const maximum = numericLimit(Number.POSITIVE_INFINITY, props.max);
    const value = Math.min(maximum, Math.max(minimum, next));

    onChange?.({ target: { value: String(value) } } as ChangeEvent<HTMLInputElement>);
    input.focus();
  }

  return <span className="ai-number-input">
    <input {...props} onChange={onChange} ref={inputRef} step={step} type="number" />
    <span className="ai-number-input-controls">
      <button aria-label={increaseLabel} disabled={props.disabled} onMouseDown={(event) => event.preventDefault()} onClick={() => adjust(1)} type="button"><ChevronUp size={11} /></button>
      <button aria-label={decreaseLabel} disabled={props.disabled} onMouseDown={(event) => event.preventDefault()} onClick={() => adjust(-1)} type="button"><ChevronDown size={11} /></button>
    </span>
  </span>;
}
