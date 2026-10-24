/**
 * An amount from the server in paise, for example 150000 INR: "₹1,500" (paise shown only when not whole
 * rupees). Null when the amount or currency is unknown. Only ever formats what the server sent: no
 * amount, rate or commission is worked out in the browser.
 */
export function formatAmount(paise: number | null | undefined, currency: string | null | undefined): string | null {
  if (paise == null || !currency || !Number.isFinite(paise)) return null;
  const whole = paise % 100 === 0;
  try {
    return new Intl.NumberFormat("en-IN", {
      style: "currency",
      currency,
      minimumFractionDigits: whole ? 0 : 2,
      maximumFractionDigits: whole ? 0 : 2,
    }).format(paise / 100);
  } catch {
    return null;
  }
}

/** Totals from the payment reports are in Indian rupees: NeuroNest only charges INR. */
export const REPORT_CURRENCY = "INR";

/** Earnings and commission totals, in paise (PaymentReportQueries.Totals on the server). */
export interface PaymentTotals {
  grossPaise: number;
  commissionPaise: number;
  professionalSharePaise: number;
  paidSessions: number;
}

const num = (value: unknown) => (typeof value === "number" && Number.isFinite(value) ? value : 0);

/** Missing or older responses read as zero, so a missing field never breaks a page. */
export function normalizeTotals(t: Partial<PaymentTotals> | null | undefined): PaymentTotals {
  return {
    grossPaise: num(t?.grossPaise),
    commissionPaise: num(t?.commissionPaise),
    professionalSharePaise: num(t?.professionalSharePaise),
    paidSessions: num(t?.paidSessions),
  };
}

export function list<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : [];
}
