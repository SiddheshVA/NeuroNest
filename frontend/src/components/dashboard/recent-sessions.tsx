import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@clerk/clerk-react";

import { SessionReviewControl } from "@/components/reviews/session-review-control";
import { formatIstDateTime } from "@/lib/ist-time";
import { CREDENTIAL_TYPE_LABELS, type CredentialType } from "@/lib/professionals-client";
import { getReviewableBookings, type MyReview, type ReviewableBooking } from "@/lib/reviews-client";

const SHOWN = 3;

/**
 * Inside "Your appointments": the latest past confirmed sessions, each with "Rate your session" or the
 * user's stars and "Edit". Shows nothing until there is a past session, and nothing if it cannot load
 * (the rest of the dashboard is unaffected).
 */
export function RecentSessions() {
  const { isLoaded, isSignedIn } = useAuth();
  const [items, setItems] = useState<ReviewableBooking[] | null>(null);

  useEffect(() => {
    if (!isLoaded || !isSignedIn) return;
    let cancelled = false;
    getReviewableBookings()
      .then((list) => !cancelled && setItems(list))
      .catch(() => !cancelled && setItems([]));
    return () => {
      cancelled = true;
    };
  }, [isLoaded, isSignedIn]);

  if (!items || items.length === 0) return null;

  const update = (bookingId: string, review: MyReview | null) =>
    setItems((current) =>
      (current ?? []).map((item) =>
        item.bookingId === bookingId ? { ...item, review, canReview: review === null } : item,
      ),
    );

  return (
    <div className="mt-6 flex flex-col gap-3 border-t border-[#EEF0EA] pt-6">
      <h3 className="text-sm font-semibold">Your recent sessions</h3>
      <ul className="flex flex-col gap-3">
        {items.slice(0, SHOWN).map((item) => {
          const p = item.professional;
          const type = CREDENTIAL_TYPE_LABELS[p.credentialType as CredentialType] ?? p.credentialType;
          return (
            <li
              key={item.bookingId}
              className="flex flex-col gap-3 rounded-2xl bg-[#FBF9F4] p-4 sm:flex-row sm:items-center sm:justify-between"
            >
              <div className="flex min-w-0 flex-col">
                <span className="font-medium">{p.fullName}</span>
                <span className="text-sm text-[#4A5750]">
                  {[type, formatIstDateTime(item.scheduledTime)].filter(Boolean).join(" · ")}
                </span>
              </div>
              <SessionReviewControl entry={item} onChanged={(review) => update(item.bookingId, review)} />
            </li>
          );
        })}
      </ul>
      {items.length > SHOWN ? (
        <Link
          href="/appointments"
          className="w-fit rounded-sm text-sm font-medium text-[#233E33] underline underline-offset-4 outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          See all your past sessions
        </Link>
      ) : null}
    </div>
  );
}
