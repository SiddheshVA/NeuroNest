import Image from "next/image";
import Link from "next/link";
import {
  BookOpen,
  ChevronRight,
  Leaf,
  Sun,
  Users,
  type LucideIcon,
} from "lucide-react";
import CloseRounded from "@mui/icons-material/CloseRounded";

import { NuroPanelToggle } from "@/components/nuro/nuro-panel-controls";
import { cn } from "@/lib/utils";

const wellnessLinks: {
  icon: LucideIcon;
  title: string;
  description: string;
  href: string;
  tone: string;
}[] = [
  {
    icon: Leaf,
    title: "Breathing Exercise",
    description: "Take a 2-minute break",
    href: "/relaxation",
    tone: "bg-primary/10 text-primary",
  },
  {
    icon: Sun,
    title: "Mood Check-in",
    description: "How are you feeling today?",
    href: "/assessment",
    tone: "bg-accent text-accent-foreground",
  },
  {
    icon: BookOpen,
    title: "Helpful Resources",
    description: "Articles and tools",
    href: "/resources",
    tone: "bg-secondary text-secondary-foreground",
  },
  {
    icon: Users,
    title: "Talk to Someone Now",
    description: "Tele-MANAS 14416 · free, 24/7",
    href: "tel:14416",
    tone: "bg-muted text-foreground",
  },
];

export function NuroWellnessPanelContent({ onClose }: { onClose?: () => void }) {
  return (
    <div className="flex h-full flex-col gap-5 overflow-y-auto bg-background px-5 py-5">
      <div className="flex items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Leaf className="size-5" aria-hidden="true" />
        </span>
        <div>
          <h2 className="font-heading text-base font-semibold text-foreground">
            You&apos;re not alone
          </h2>
          <p className="text-sm text-muted-foreground">
            Small steps make a big difference.
          </p>
        </div>
        {onClose ? (
          <div className="-mt-1 -mr-2 ml-auto shrink-0">
            <NuroPanelToggle label="Close suggestions" onClick={onClose}>
              <CloseRounded fontSize="small" />
            </NuroPanelToggle>
          </div>
        ) : null}
      </div>

      <div className="flex flex-col gap-2">
        <span className="px-1 text-xs font-medium tracking-wide text-muted-foreground uppercase">
          Try something helpful
        </span>
        <div className="flex flex-col gap-1.5">
          {wellnessLinks.map((item) => (
            <Link
              key={item.title}
              href={item.href}
              className="group flex items-center gap-3 rounded-xl border border-border/70 bg-card px-3 py-2.5 shadow-sm transition-colors hover:border-primary/30 hover:bg-primary/[0.04]"
            >
              <span
                className={`flex size-9 shrink-0 items-center justify-center rounded-full ${item.tone}`}
              >
                <item.icon className="size-4.5" aria-hidden="true" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium text-foreground">
                  {item.title}
                </span>
                <span className="block truncate text-xs text-muted-foreground">
                  {item.description}
                </span>
              </span>
              <ChevronRight
                className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5"
                aria-hidden="true"
              />
            </Link>
          ))}
        </div>
      </div>

      <div className="relative mt-auto aspect-[4/5] overflow-hidden rounded-2xl ring-1 ring-border">
        <Image
          src="/images/lower-right.png"
          alt=""
          aria-hidden="true"
          fill
          sizes="320px"
          priority
          className="object-cover object-bottom"
        />
        <div className="absolute inset-x-0 top-0 bg-gradient-to-b from-background/85 via-background/35 to-transparent px-4 pt-5 pb-12 text-center">
          <p className="font-heading text-base font-semibold text-balance text-foreground">
            A calmer mind brings a brighter tomorrow.
          </p>
          <div className="mx-auto my-2 h-px w-8 bg-foreground/20" />
          <p className="text-xs text-muted-foreground">You&apos;re doing enough.</p>
        </div>
      </div>
    </div>
  );
}

/** Inline (desktop) suggestions panel; collapses to zero width when closed. */
export function NuroWellnessPanel({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  return (
    <aside
      aria-label="Suggestions"
      inert={!open}
      className={cn(
        "shrink-0 overflow-hidden border-border transition-[width] duration-300 ease-in-out motion-reduce:transition-none",
        open ? "w-72 border-l lg:w-80" : "w-0",
      )}
    >
      <div className="h-full w-72 lg:w-80">
        <NuroWellnessPanelContent onClose={onClose} />
      </div>
    </aside>
  );
}
