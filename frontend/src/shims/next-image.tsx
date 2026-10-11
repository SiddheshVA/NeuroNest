import { forwardRef, type CSSProperties, type ImgHTMLAttributes } from "react";

// Drop-in for next/image: renders a plain <img>. `fill` reproduces Next's
// absolutely-positioned, parent-filling behaviour; `priority` maps to eager
// loading. Optimisation-only props are accepted and ignored.
type ImageProps = Omit<ImgHTMLAttributes<HTMLImageElement>, "src"> & {
  src: string | { src: string };
  fill?: boolean;
  priority?: boolean;
  quality?: number;
  placeholder?: string;
  blurDataURL?: string;
  unoptimized?: boolean;
};

const Image = forwardRef<HTMLImageElement, ImageProps>(function Image(
  { src, fill, priority, quality: _quality, placeholder: _placeholder, blurDataURL: _blur, unoptimized: _u, style, loading, ...rest },
  ref,
) {
  const fillStyle: CSSProperties | undefined = fill
    ? { position: "absolute", inset: 0, width: "100%", height: "100%" }
    : undefined;
  return (
    <img
      ref={ref}
      src={typeof src === "string" ? (src.includes("%") ? src : encodeURI(src)) : src.src}
      loading={loading ?? (priority ? "eager" : "lazy")}
      decoding="async"
      style={{ ...fillStyle, ...style }}
      {...rest}
    />
  );
});

export default Image;
