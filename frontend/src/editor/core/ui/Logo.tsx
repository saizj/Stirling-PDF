import type { CSSProperties } from "react";

/** iconOnly = mark; textOnly = "Stirling" wordmark; iconAndText = both. */
export type LogoVariant = "iconOnly" | "iconAndText" | "textOnly";

interface LogoProps {
  variant?: LogoVariant;
  /** Layout for iconAndText: mark left of text, or stacked above it. */
  orientation?: "horizontal" | "vertical";
  /** Height of the mark (CSS length). */
  iconHeight?: string;
  /** Height of the wordmark (CSS length). */
  textHeight?: string;
  /** Gap between mark and wordmark. */
  gap?: string;
  className?: string;
  style?: CSSProperties;
  alt?: string;
}

// Blasai fork: the Stirling brand lockup is hidden everywhere to reclaim space
// (especially on mobile), the same way LogoIcon and Wordmark are. Those two only
// covered the previous design; this component is what the current one renders,
// so hiding the brand has to happen here too. Kept as a no-op so every call site
// keeps working.
export function Logo(_props: LogoProps) {
  return null;
}
