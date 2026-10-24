import Link from "next/link";
import { ArrowRight, ExternalLink, Leaf, LifeBuoy, Phone, User } from "lucide-react";

import { getCrisisResources } from "@/lib/ai/crisis-resources";
import { cn } from "@/lib/utils";
import { formatMessageTime } from "@/lib/nuro-dates";
import type { CrisisContact, MessageRole, NuroRecommendation } from "@/lib/nuro-client";
import { URGENT_DIRECTORY_HREF } from "@/lib/professionals-client";

function Avatar({ isNuro }: { isNuro: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "flex size-8 shrink-0 items-center justify-center rounded-full",
        isNuro ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground",
      )}
    >
      {isNuro ? <Leaf className="size-4" /> : <User className="size-4" />}
    </span>
  );
}

function TypingDots() {
  return (
    <span className="flex items-center gap-1.5 py-0.5">
      <span className="sr-only">Neuro is typing…</span>
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          aria-hidden="true"
          className="size-1.5 animate-bounce rounded-full bg-muted-foreground/60"
          style={{ animationDelay: `${i * 120}ms` }}
        />
      ))}
    </span>
  );
}

/**
 * Helplines from the backend's crisis resources. "alert": shown the moment this message is
 * flagged as a crisis. "calm": offered alongside a failed reply, without alarm.
 */
/**
 * Under a Find Professional card: a booking isn't instant, Tele-MANAS is. Numbers come from
 * crisis-resources.ts, never typed here.
 */
function TeleManasLine() {
  const teleManas = getCrisisResources().helplines.find((helpline) => helpline.label === "Tele-MANAS");
  if (!teleManas) return null;
  return (
    <p className="flex flex-wrap items-center gap-x-1.5 px-1 text-xs text-muted-foreground">
      <Phone className="size-3 shrink-0" aria-hidden="true" />
      <span>Need support right now? {teleManas.label} (24/7):</span>
      {teleManas.numbers.map((number, index) => (
        <span key={number}>
          {index > 0 ? "/ " : null}
          <a href={`tel:${number.replace(/[^\d+]/g, "")}`} className="font-medium underline underline-offset-2">
            {number}
          </a>
        </span>
      ))}
    </p>
  );
}

function CrisisContacts({ contacts, tone }: { contacts: CrisisContact[]; tone: "alert" | "calm" }) {
  const isAlert = tone === "alert";
  return (
    <div
      role={isAlert ? "alert" : undefined}
      className={cn(
        "w-full rounded-2xl border p-4",
        isAlert
          ? "border-[#E7C8C0] bg-[#FBEBE7] text-[#7A3226]"
          : "border-border bg-background/80 text-foreground",
      )}
    >
      <p className="flex items-center gap-2 text-sm font-semibold">
        <LifeBuoy className="size-4 shrink-0" aria-hidden="true" />
        {isAlert ? "Please reach out for support right now" : "If you'd like to talk to someone right now"}
      </p>
      <ul className="mt-2.5 flex flex-col gap-2">
        {contacts.map((contact) => (
          <li key={contact.name}>
            <a
              href={contact.href}
              className="inline-flex items-center gap-1.5 text-sm font-semibold underline underline-offset-2"
            >
              <Phone className="size-3.5 shrink-0" aria-hidden="true" />
              {contact.name}: {contact.phone}
            </a>
            <span className="block pl-5 text-xs opacity-80">{contact.availability}</span>
          </li>
        ))}
      </ul>
      {/* A new tab, so following the link never takes the person away from this conversation. */}
      <a
        href={URGENT_DIRECTORY_HREF}
        target="_blank"
        rel="noopener noreferrer"
        className="mt-3 inline-flex items-center gap-1.5 text-sm font-semibold underline underline-offset-2"
      >
        Talk to a licensed professional
        <ExternalLink className="size-3.5 shrink-0" aria-hidden="true" />
        <span className="sr-only">(opens in a new tab)</span>
      </a>
    </div>
  );
}

export function NuroMessageBubble({
  role,
  content,
  createdAt,
  recommendations,
  crisisContacts,
  crisisTone = "alert",
  isStreaming,
}: {
  role: MessageRole;
  content: string;
  /** Omit for a purely local/synthetic bubble (e.g. the welcome greeting). */
  createdAt?: string;
  recommendations?: NuroRecommendation[];
  crisisContacts?: CrisisContact[];
  crisisTone?: "alert" | "calm";
  /** Reply still arriving: shows typing dots until the first text. */
  isStreaming?: boolean;
}) {
  const isNuro = role !== "USER";

  return (
    <div
      className={cn(
        "flex w-full items-start gap-2.5",
        isNuro ? "justify-start" : "flex-row-reverse justify-start",
      )}
    >
      <Avatar isNuro={isNuro} />
      <div
        className={cn(
          "flex max-w-[85%] flex-col gap-1 sm:max-w-[75%]",
          isNuro ? "items-start" : "items-end",
        )}
      >
        {isNuro ? (
          <span className="px-1 text-xs font-medium text-muted-foreground">
            Neuro
          </span>
        ) : null}
        {crisisContacts && crisisContacts.length > 0 ? (
          <CrisisContacts contacts={crisisContacts} tone={crisisTone} />
        ) : null}
        <div
          className={cn(
            "rounded-2xl px-4 py-3 text-sm leading-relaxed whitespace-pre-wrap",
            isNuro
              ? "rounded-tl-sm bg-muted text-foreground"
              : "rounded-tr-sm bg-primary/10 text-foreground",
          )}
        >
          {isStreaming && !content ? <TypingDots /> : content}
        </div>
        {recommendations && recommendations.length > 0 ? (
          <div className="mt-2 flex flex-col gap-2 w-full">
            <span className="text-xs font-semibold text-muted-foreground px-1">
              Suggested Next Steps:
            </span>
            {recommendations.map((rec) => (
              <Link
                key={rec.type + rec.route}
                href={rec.route}
                className="group flex flex-col gap-0.5 rounded-xl border border-primary/20 bg-primary/5 p-3 text-left transition-colors hover:border-primary/40 hover:bg-primary/10"
              >
                <div className="flex items-center justify-between text-sm font-semibold text-primary">
                  <span>{rec.title}</span>
                  <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5" />
                </div>
                <p className="text-xs text-muted-foreground">{rec.description}</p>
              </Link>
            ))}
            {recommendations.some((rec) => rec.type === "PROFESSIONAL") ? <TeleManasLine /> : null}
          </div>
        ) : null}
        {createdAt ? (
          <span className="px-1 text-xs text-muted-foreground">
            {formatMessageTime(createdAt)}
          </span>
        ) : null}
      </div>
    </div>
  );
}

/**
 * Small, calm reminder kept visible for the rest of a conversation in which the user
 * disclosed a crisis earlier. Not an alert: the full red box belongs to the message
 * that triggered it.
 */
export function NuroCrisisBanner({ contacts }: { contacts: CrisisContact[] }) {
  return (
    <div className="sticky top-0 z-10 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-[#E7C8C0]/70 bg-[#FBF4F1]/95 px-3.5 py-2 text-xs text-[#7A3226] backdrop-blur-sm">
      <span className="flex items-center gap-1.5 font-medium">
        <LifeBuoy className="size-3.5 shrink-0" aria-hidden="true" />
        Support is here anytime, free and confidential:
      </span>
      {contacts.map((contact) => (
        <a key={contact.name} href={contact.href} className="underline underline-offset-2">
          {contact.name} {contact.phone}
        </a>
      ))}
    </div>
  );
}

export function NuroTypingIndicator() {
  return (
    <div className="flex w-full items-start gap-2.5">
      <Avatar isNuro />
      <div className="rounded-2xl rounded-tl-sm bg-muted px-4 py-3">
        <TypingDots />
      </div>
    </div>
  );
}
