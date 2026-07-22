/**
 * Blasai fork: compose an Adobe-style signature appearance into a single flat PNG.
 *
 * Layout mirrors Adobe's "Personalizar aspecto": the drawn/typed/uploaded image on the left and a
 * text block (signer name + date) on the right, separated by a thin rule. Any subset can be
 * omitted. The result is a base64 PNG that gets stamped onto the PDF at the placed rectangle.
 */
export interface SignatureAppearanceOptions {
  /** Base64/dataURL image (drawing, typed text rendered to image, or uploaded picture). */
  signatureImage?: string | null;
  /** Signer name (from the certificate). */
  name?: string;
  /** Signer national id (DNI/CIF/NIE) from the certificate. */
  signerId?: string;
  /** Pre-formatted date string. */
  date?: string;
  includeImage: boolean;
  includeName: boolean;
  includeId: boolean;
  includeDate: boolean;
}

const CANVAS_WIDTH = 640;
const CANVAS_HEIGHT = 200;

const loadImage = (src: string): Promise<HTMLImageElement> =>
  new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = src;
  });

const drawContain = (
  ctx: CanvasRenderingContext2D,
  img: HTMLImageElement,
  x: number,
  y: number,
  w: number,
  h: number,
) => {
  const scale = Math.min(w / img.width, h / img.height);
  const dw = img.width * scale;
  const dh = img.height * scale;
  ctx.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh);
};

const wrapText = (
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
): string[] => {
  const words = text.split(" ");
  const lines: string[] = [];
  let current = "";
  for (const word of words) {
    const candidate = current ? `${current} ${word}` : word;
    if (ctx.measureText(candidate).width > maxWidth && current) {
      lines.push(current);
      current = word;
    } else {
      current = candidate;
    }
  }
  if (current) lines.push(current);
  return lines;
};

export async function composeSignatureAppearance(
  options: SignatureAppearanceOptions,
): Promise<string> {
  const canvas = document.createElement("canvas");
  canvas.width = CANVAS_WIDTH;
  canvas.height = CANVAS_HEIGHT;
  const ctx = canvas.getContext("2d");
  if (!ctx) return "";

  const hasText =
    (options.includeName && !!options.name) ||
    (options.includeId && !!options.signerId) ||
    (options.includeDate && !!options.date);
  const hasImage = options.includeImage && !!options.signatureImage;

  const padding = 16;
  const gap = 16;

  // Determine columns.
  let imageRegion: { x: number; y: number; w: number; h: number } | null = null;
  let textRegion: { x: number; y: number; w: number; h: number } | null = null;

  if (hasImage && hasText) {
    const half = (CANVAS_WIDTH - padding * 2 - gap) / 2;
    imageRegion = {
      x: padding,
      y: padding,
      w: half,
      h: CANVAS_HEIGHT - padding * 2,
    };
    textRegion = {
      x: padding + half + gap,
      y: padding,
      w: half,
      h: CANVAS_HEIGHT - padding * 2,
    };
  } else if (hasImage) {
    imageRegion = {
      x: padding,
      y: padding,
      w: CANVAS_WIDTH - padding * 2,
      h: CANVAS_HEIGHT - padding * 2,
    };
  } else if (hasText) {
    textRegion = {
      x: padding,
      y: padding,
      w: CANVAS_WIDTH - padding * 2,
      h: CANVAS_HEIGHT - padding * 2,
    };
  }

  if (imageRegion && options.signatureImage) {
    const img = await loadImage(options.signatureImage);
    drawContain(
      ctx,
      img,
      imageRegion.x,
      imageRegion.y,
      imageRegion.w,
      imageRegion.h,
    );
  }

  // Divider between the two columns.
  if (imageRegion && textRegion) {
    ctx.strokeStyle = "rgba(0,0,0,0.25)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(textRegion.x - gap / 2, padding);
    ctx.lineTo(textRegion.x - gap / 2, CANVAS_HEIGHT - padding);
    ctx.stroke();
  }

  if (textRegion) {
    ctx.fillStyle = "#111111";
    ctx.textBaseline = "top";
    const lines: { text: string; size: number; bold: boolean }[] = [];
    if (options.includeName && options.name) {
      lines.push({ text: options.name, size: 22, bold: true });
    }
    if (options.includeId && options.signerId) {
      lines.push({ text: options.signerId, size: 16, bold: false });
    }
    if (options.includeDate && options.date) {
      lines.push({ text: options.date, size: 18, bold: false });
    }

    const LEADING = 1.5;
    const BLOCK_GAP = 12;
    const textX = textRegion.x + 8;
    const textW = textRegion.w - 8;

    // Vertically center the block.
    let totalHeight = 0;
    const rendered: { parts: string[]; size: number; bold: boolean }[] = [];
    for (const line of lines) {
      ctx.font = `${line.bold ? "bold " : ""}${line.size}px Helvetica, Arial, sans-serif`;
      const parts = wrapText(ctx, line.text, textW);
      rendered.push({ parts, size: line.size, bold: line.bold });
      totalHeight += parts.length * (line.size * LEADING) + BLOCK_GAP;
    }
    totalHeight -= BLOCK_GAP; // no trailing gap after the last line

    let y = textRegion.y + Math.max(0, (textRegion.h - totalHeight) / 2);
    for (const line of rendered) {
      ctx.font = `${line.bold ? "bold " : ""}${line.size}px Helvetica, Arial, sans-serif`;
      for (const part of line.parts) {
        ctx.fillText(part, textX, y);
        y += line.size * LEADING;
      }
      y += BLOCK_GAP;
    }
  }

  return canvas.toDataURL("image/png");
}
