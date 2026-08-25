interface BrandMarkProps {
  /** Height of the mark (CSS length). */
  height?: string;
  className?: string;
}

// Blasai fork: the Stirling logo mark is hidden everywhere — see Logo.tsx.
// Kept as a no-op component so every call site keeps working.
export function BrandMark(_props: BrandMarkProps) {
  return null;
}
