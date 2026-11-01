import type { HTMLAttributes } from "react";
import { cn } from "@/lib/utils";

export function Alert({ className, tone = "info", ...props }: HTMLAttributes<HTMLDivElement> & { tone?: "info" | "error" | "warning" }) {
  return (
    <div
      role={tone === "error" ? "alert" : "status"}
      className={cn(
        "rounded-md border px-4 py-3 text-sm",
        tone === "error" && "border-destructive/50 text-destructive",
        tone === "warning" && "border-amber-500/50 bg-amber-500/10",
        tone === "info" && "bg-muted",
        className,
      )}
      {...props}
    />
  );
}
