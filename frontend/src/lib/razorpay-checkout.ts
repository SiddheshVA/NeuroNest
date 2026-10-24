import { BookingApiError, createPaymentOrder, verifyPayment, type BookingView } from "./bookings-client";

/**
 * Paying for an AWAITING_PAYMENT booking with Razorpay Checkout. The key id, order id, amount and currency
 * all come from POST /api/bookings/{id}/payment/order; no Razorpay key is ever in frontend code or env
 * files. Nothing here logs keys, signatures or payment data.
 */

const CHECKOUT_URL = "https://checkout.razorpay.com/v1/checkout.js";
const VERIFY_RETRY_DELAYS_MS = [1500, 3000];

interface RazorpaySuccess {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

interface RazorpayOptions {
  key: string;
  amount: number;
  currency: string;
  order_id: string;
  name: string;
  description: string;
  handler: (response: RazorpaySuccess) => void;
  modal: { ondismiss: () => void };
}

interface RazorpayInstance {
  open: () => void;
  on: (event: "payment.failed", handler: () => void) => void;
}

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayOptions) => RazorpayInstance;
  }
}

/**
 * paid: verified, the booking is CONFIRMED. dismissed: the window was closed before paying. failed: a
 * payment attempt failed and the window was closed. unverified: Razorpay reported a payment but NeuroNest
 * could not verify it; the user must not pay again.
 */
export type PaymentResult =
  | { outcome: "paid"; booking: BookingView }
  | { outcome: "dismissed" }
  | { outcome: "failed" }
  | { outcome: "unverified"; message: string };

export const UNVERIFIED_MESSAGE =
  "Your payment may have gone through, but we could not confirm it yet. Please do not pay again. " +
  "Refresh this page in a minute to see your booking.";

let checkoutScript: Promise<void> | null = null;

// Loaded on first use and only once; a failed load can be tried again.
function loadCheckout(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  if (!checkoutScript) {
    checkoutScript = new Promise<void>((resolve, reject) => {
      const script = document.createElement("script");
      script.src = CHECKOUT_URL;
      script.async = true;
      script.onload = () => (window.Razorpay ? resolve() : reject(new Error("Razorpay Checkout did not load.")));
      script.onerror = () => {
        script.remove();
        reject(new Error("Razorpay Checkout did not load."));
      };
      document.head.appendChild(script);
    }).catch((error: unknown) => {
      checkoutScript = null;
      throw error;
    });
  }
  return checkoutScript;
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

// Retries twice, on network errors only (status 0): any answer from the server is final.
async function verifyWithRetry(bookingId: string, response: RazorpaySuccess): Promise<PaymentResult> {
  const body = {
    razorpay_order_id: response.razorpay_order_id,
    razorpay_payment_id: response.razorpay_payment_id,
    razorpay_signature: response.razorpay_signature,
  };
  for (let attempt = 0; ; attempt++) {
    try {
      return { outcome: "paid", booking: await verifyPayment(bookingId, body) };
    } catch (error) {
      const network = error instanceof BookingApiError && error.status === 0;
      if (network && attempt < VERIFY_RETRY_DELAYS_MS.length) {
        await wait(VERIFY_RETRY_DELAYS_MS[attempt]);
        continue;
      }
      // A 409 explains itself (for example: received, but the booking had expired, so it will be refunded).
      const message = error instanceof BookingApiError && error.status === 409 ? error.message : UNVERIFIED_MESSAGE;
      return { outcome: "unverified", message };
    }
  }
}

/**
 * Starts or resumes paying for one booking. Throws a BookingApiError when the order cannot be made (for
 * example the deadline passed) or Checkout cannot be loaded; otherwise resolves once the window closes.
 */
export async function payForBooking(bookingId: string, professionalName: string): Promise<PaymentResult> {
  const order = await createPaymentOrder(bookingId);
  try {
    await loadCheckout();
  } catch {
    throw new BookingApiError(0, "The payment window could not be opened. Please check your connection and try again.");
  }
  const Razorpay = window.Razorpay;
  if (!Razorpay) {
    throw new BookingApiError(0, "The payment window could not be opened. Please try again.");
  }

  return new Promise<PaymentResult>((resolve) => {
    let settled = false;
    let attemptFailed = false;
    const finish = (result: PaymentResult | Promise<PaymentResult>) => {
      if (settled) return;
      settled = true;
      resolve(result);
    };
    const checkout = new Razorpay({
      key: order.keyId,
      amount: order.amount,
      currency: order.currency,
      order_id: order.razorpayOrderId,
      name: "NeuroNest",
      description: `Session with ${professionalName}`,
      handler: (response) => finish(verifyWithRetry(bookingId, response)),
      modal: { ondismiss: () => finish({ outcome: attemptFailed ? "failed" : "dismissed" }) },
    });
    // Checkout stays open after a failed attempt so the user can retry; only the close settles it.
    checkout.on("payment.failed", () => {
      attemptFailed = true;
    });
    checkout.open();
  });
}
