import React from "react";

interface LogoIconProps extends React.ImgHTMLAttributes<HTMLImageElement> {
  alt?: string;
}

// Blasai fork: the logo mark is hidden everywhere to reclaim space (especially on
// mobile). Kept as a no-op component so every call site keeps working.
export function LogoIcon(_props: LogoIconProps) {
  return null;
}
