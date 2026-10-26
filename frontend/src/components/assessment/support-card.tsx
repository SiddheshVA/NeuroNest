import Link from "next/link";
import { HeartHandshake, MessageCircle, Phone } from "lucide-react";
import type { ServerCrisisResource } from "@/lib/assessments/sync";
import { URGENT_DIRECTORY_HREF } from "@/lib/professionals-client";

interface SupportCardProps {
  /** The crisis lines to show, as the backend sent them with the result. */
  resources: ServerCrisisResource[];
  /** One extra line under the heading, e.g. for an answer about self-harm. */
  note?: string;
}

// "14416 or 1800-891-4416" -> ["14416", "1800-891-4416"]
function numbersIn(phone: string): string[] {
  return phone
    .split(/\s+or\s+|\s*\/\s*|\s*,\s*/i)
    .map((n) => n.trim())
    .filter(Boolean);
}

function telHref(number: string): string {
  return `tel:${number.replace(/[^\d+]/g, "")}`;
}

/**
 * The one calm "support" card shown under the score when a result is flagged (riskFlag): the
 * helplines the backend returned, each number tap-to-call, and a way to a licensed professional.
 * Warm and steady on purpose — the page's own greens, no alarm colours, no motion.
 */
export function SupportCard({ resources, note }: SupportCardProps) {
  return (
    <section
      aria-labelledby="support-card-heading"
      className="mb-8 rounded-[28px] border border-[#CBD4C5] bg-[#ECEFE6] p-6 sm:p-8"
    >
      <div className="mb-5 flex items-start gap-4">
        <span className="flex size-12 shrink-0 items-center justify-center rounded-full bg-white text-[#233E33]">
          <HeartHandshake className="size-6" aria-hidden="true" />
        </span>
        <div className="flex flex-col gap-1.5">
          <h2 id="support-card-heading" className="font-heading text-xl font-normal text-[#1C352D] sm:text-2xl">
            You don&apos;t have to go through this alone.
          </h2>
          <p className="text-sm leading-relaxed text-[#3F4D45] sm:text-base">
            {note ??
              "Talking with someone can make things feel lighter. These free helplines are there for you, any time."}
          </p>
        </div>
      </div>

      {resources.length > 0 && (
        <ul className="mb-6 flex flex-col gap-3">
          {resources.map((resource) => {
            const numbers = numbersIn(resource.phone);
            return (
              <li
                key={resource.name}
                className="flex flex-col gap-3 rounded-2xl bg-white p-4 sm:flex-row sm:items-center sm:justify-between"
              >
                <div className="flex flex-col gap-0.5">
                  <span className="font-medium text-[#1C352D]">{resource.name}</span>
                  <span className="text-sm text-[#3F4D45]">
                    {numbers.map((number, index) => (
                      <span key={number}>
                        {index > 0 && " or "}
                        <a
                          href={telHref(number)}
                          className="rounded-sm font-medium text-[#233E33] underline underline-offset-2 outline-none focus-visible:ring-3 focus-visible:ring-[#233E33]/40"
                        >
                          {number}
                        </a>
                      </span>
                    ))}
                    {resource.availability ? ` · ${resource.availability}` : null}
                  </span>
                </div>
                {numbers[0] && (
                  <a
                    href={telHref(numbers[0])}
                    aria-label={`Call ${resource.name} on ${numbers[0]}`}
                    className="inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-full border border-[#C8CEBE] bg-white px-5 text-sm font-medium text-[#1C352D] outline-none transition-colors duration-150 hover:bg-[#F4F6F0] focus-visible:ring-3 focus-visible:ring-[#233E33]/40"
                  >
                    <Phone className="size-4" aria-hidden="true" />
                    Call
                  </a>
                )}
              </li>
            );
          })}
        </ul>
      )}

      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap">
        <Link
          href={URGENT_DIRECTORY_HREF}
          className="inline-flex min-h-11 w-full items-center justify-center rounded-full bg-[#233E33] px-6 py-3 text-sm font-medium text-white outline-none transition-colors duration-150 hover:bg-[#192E26] focus-visible:ring-3 focus-visible:ring-[#233E33]/40 focus-visible:ring-offset-2 sm:w-auto"
        >
          Find a licensed professional
        </Link>
        <Link
          href="/ai-companion"
          className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-full border border-[#C8CEBE] bg-white px-6 py-3 text-sm font-medium text-[#1C352D] outline-none transition-colors duration-150 hover:bg-[#F4F6F0] focus-visible:ring-3 focus-visible:ring-[#233E33]/40 sm:w-auto"
        >
          <MessageCircle className="size-4" aria-hidden="true" />
          Talk with Neuro
        </Link>
      </div>
    </section>
  );
}
