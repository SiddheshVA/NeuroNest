import { forwardRef, type AnchorHTMLAttributes, type ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";

// Drop-in for next/link so the components ported from the Next.js app keep
// their `import Link from "next/link"` unchanged. Internal paths go through
// React Router; external, tel:, mailto: and hash links stay plain anchors.
type LinkProps = Omit<AnchorHTMLAttributes<HTMLAnchorElement>, "href"> & {
  href: string;
  prefetch?: boolean;
  replace?: boolean;
  scroll?: boolean;
  children?: ReactNode;
};

const Link = forwardRef<HTMLAnchorElement, LinkProps>(function Link(
  { href, prefetch: _prefetch, replace, scroll: _scroll, ...rest },
  ref,
) {
  const isInternal = href.startsWith("/") && !href.startsWith("//");
  if (!isInternal) {
    return <a ref={ref} href={href} {...rest} />;
  }
  return <RouterLink ref={ref} to={href} replace={replace} {...rest} />;
});

export default Link;
