/** "Screening tool, not a diagnosis", shared with the dashboard's check-in page. */
export function ScreeningToolDisclaimer({ label }: { label: string }) {
  return (
    <p className="mx-auto max-w-xl text-center text-xs leading-relaxed text-[#8A9690]">
      {label} is a brief self-reflection screening tool. It is not a diagnostic instrument and
      it does not determine risk on its own — it does not replace a professional evaluation.
    </p>
  );
}
