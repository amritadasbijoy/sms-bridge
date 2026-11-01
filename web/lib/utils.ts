import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });
const timeOnly = new Intl.DateTimeFormat(undefined, { timeStyle: "short" });
const dateOnly = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

export function formatDateTime(ms: number | string | null | undefined): string {
  if (ms === null || ms === undefined || ms === "") return "—";
  return dateTime.format(new Date(ms));
}

/** Time for today, date otherwise (message lists). */
export function formatShort(ms: number): string {
  const d = new Date(ms);
  return d.toDateString() === new Date().toDateString() ? timeOnly.format(d) : dateOnly.format(d);
}
