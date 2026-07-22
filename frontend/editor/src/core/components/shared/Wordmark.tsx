import React from "react";

interface WordmarkProps extends React.ImgHTMLAttributes<HTMLImageElement> {
  alt?: string;
  muted?: boolean;
}

// Blasai fork: the "Stirling PDF" wordmark is hidden everywhere to reclaim space
// (especially on mobile). Kept as a no-op component so every call site keeps working.
export function Wordmark(_props: WordmarkProps) {
  return null;
}
