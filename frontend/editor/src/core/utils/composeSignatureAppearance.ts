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

const CANVAS_WIDTH = 820;
const CANVAS_HEIGHT = 250;

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

export async function composeSignatureAppearance(
  options: SignatureAppearanceOptions,
): Promise<string> {
  // Render at 3x so the stamped image stays crisp when scaled to the PDF box.
  const SCALE = 3;
  const canvas = document.createElement("canvas");
  canvas.width = CANVAS_WIDTH * SCALE;
  canvas.height = CANVAS_HEIGHT * SCALE;
  const ctx = canvas.getContext("2d");
  if (!ctx) return "";
  ctx.scale(SCALE, SCALE);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = "high";

  const hasText =
    (options.includeName && !!options.name) ||
    (options.includeId && !!options.signerId) ||
    (options.includeDate && !!options.date);
  const hasImage = options.includeImage && !!options.signatureImage;

  const padding = 10;
  const gap = 14;

  // Determine columns.
  let imageRegion: { x: number; y: number; w: number; h: number } | null = null;
  let textRegion: { x: number; y: number; w: number; h: number } | null = null;

  if (hasImage && hasText) {
    const content = CANVAS_WIDTH - padding * 2 - gap;
    const imgW = content * 0.42;
    const txtW = content * 0.58;
    imageRegion = {
      x: padding,
      y: padding,
      w: imgW,
      h: CANVAS_HEIGHT - padding * 2,
    };
    textRegion = {
      x: padding + imgW + gap,
      y: padding,
      w: txtW,
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
    const textX = textRegion.x + 6;
    const textW = textRegion.w - 12;

    const items: { text: string; bold: boolean; rel: number }[] = [];
    if (options.includeName && options.name) {
      items.push({ text: options.name, bold: true, rel: 1 });
    }
    if (options.includeId && options.signerId) {
      items.push({ text: options.signerId, bold: false, rel: 0.74 });
    }
    if (options.includeDate && options.date) {
      items.push({ text: options.date, bold: false, rel: 0.74 });
    }

    if (items.length > 0) {
      // Largest font that still fits the width — so the text fills the card.
      const fitWidth = (text: string, bold: boolean, maxSize: number): number => {
        const f = (s: number) =>
          `${bold ? "bold " : ""}${s}px Helvetica, Arial, sans-serif`;
        let size = maxSize;
        ctx.font = f(size);
        while (size > 10 && ctx.measureText(text).width > textW) {
          size -= 1;
          ctx.font = f(size);
        }
        return size;
      };

      const nameSize = fitWidth(items[0].text, items[0].bold, 48);

      // Spread the lines across the full height so they fill the card.
      const slotH = textRegion.h / items.length;
      ctx.textBaseline = "middle";
      items.forEach((it, i) => {
        const target = Math.round(nameSize * it.rel);
        const size = Math.min(target, fitWidth(it.text, it.bold, target));
        ctx.font = `${it.bold ? "bold " : ""}${size}px Helvetica, Arial, sans-serif`;
        ctx.fillText(it.text, textX, textRegion.y + slotH * i + slotH / 2);
      });
    }
  }

  return canvas.toDataURL("image/png");
}
