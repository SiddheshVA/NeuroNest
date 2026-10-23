import { Component, type ReactNode } from "react";

/**
 * Catches a rendering error in one page and shows a calm message instead of a blank screen. The error
 * itself (which could include page data) is never shown.
 */
export class PageErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <section className="py-10 sm:py-14">
        <div
          role="alert"
          className="mx-auto flex max-w-xl flex-col items-start gap-3 rounded-2xl bg-card p-6 ring-1 ring-foreground/10"
        >
          <p className="text-sm font-medium text-foreground">Something went wrong, please refresh the page.</p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="font-medium text-primary underline underline-offset-4"
          >
            Refresh
          </button>
        </div>
      </section>
    );
  }
}
