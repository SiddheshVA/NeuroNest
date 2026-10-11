export const siteConfig = {
  name: "NeuroNest",
  tagline: "AI-powered mental wellness companion",
  description:
    "NeuroNest is a calm, private space to talk through how you're feeling, take wellness screenings, relax, and find support — no account required to start.",
} as const;

export type NavLink = {
  label: string;
  href: string;
};

export const mainNavLinks: NavLink[] = [
  { label: "Home", href: "/" },
  { label: "Chat with Neuro", href: "/ai-companion" },
  { label: "Assessments", href: "/assessment" },
  { label: "Relaxation", href: "/relaxation" },
  { label: "Resources", href: "/resources" },
  { label: "Find Professional", href: "/find-professional" },
];

/** True when `pathname` is `href` itself or a page nested under it. */
export function isNavLinkActive(href: string, pathname: string | null | undefined): boolean {
  if (!pathname) return false;
  if (href === "/") return pathname === "/";
  return pathname === href || pathname.startsWith(`${href}/`);
}

export const footerLinkGroups: { title: string; links: NavLink[] }[] = [
  {
    title: "Product",
    links: [
      { label: "AI Companion", href: "/ai-companion" },
      { label: "Assessments", href: "/assessment" },
      { label: "Relaxation Hub", href: "/relaxation" },
      { label: "Resources", href: "/resources" },
    ],
  },
  {
    title: "Company",
    links: [
      { label: "About", href: "/about" },
      { label: "Privacy Policy", href: "/privacy" },
      { label: "Terms of Service", href: "/terms" },
      { label: "Contact", href: "/contact" },
    ],
  },
];

export const socialLinks = [
  { label: "Instagram", href: "#", icon: "instagram" as const },
  { label: "X", href: "#", icon: "x" as const },
  { label: "LinkedIn", href: "#", icon: "linkedin" as const },
  { label: "YouTube", href: "#", icon: "youtube" as const },
];
