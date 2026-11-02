import { useReducer, useState } from "react";
import Link from "next/link";
import { ChevronDown, ChevronLeft, ChevronRight, HeartPulse, Loader2, RotateCcw, Trash2 } from "lucide-react";

import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { deleteAllCheckins, deleteCheckin, submitCheckin } from "@/features/wellness/api.ts";
import { CheckinQuestionnaire } from "@/features/wellness/components/checkin-questionnaire.tsx";
import { CheckinResults } from "@/features/wellness/components/checkin-results.tsx";
import { ActionList, BandChip, DriverList } from "@/features/wellness/components/result-parts.tsx";
import {
  card,
  dangerButton,
  focusRing,
  muted,
  primaryButton,
  secondaryButton,
} from "@/features/wellness/components/styles.ts";
import { COMMON, ERRORS, HISTORY, PAGE, Q, RESULT } from "@/features/wellness/copy.ts";
import { errorKind, friendlyMessage, WellnessApiError } from "@/features/wellness/errors.ts";
import {
  HISTORY_PAGE_SIZE,
  INITIAL_DELETE_STATE,
  canConfirm,
  deleteReducer,
  pageAfterDelete,
} from "@/features/wellness/history.ts";
import { useCheckinHistory, useRelogin } from "@/features/wellness/hooks.ts";
import {
  EMPTY_ANSWERS,
  errorsByAnswer,
  stepForFieldErrors,
  toRequest,
  type Answers,
} from "@/features/wellness/questionnaire.ts";
import { WELLNESS_ROUTES, sleepFlag } from "@/features/wellness/results.ts";
import type { CheckinItem, CheckinRequest, CheckinResponse } from "@/features/wellness/types.ts";

type FieldErrors = Partial<Record<keyof Answers, string>>;

/** /wellness-check-in: the questionnaire, then the result. Open to guests (their result is not stored). */
export function WellnessCheckinPage() {
  const [answers, setAnswers] = useState<Answers>(EMPTY_ANSWERS);
  const [step, setStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [result, setResult] = useState<{ response: CheckinResponse; request: CheckinRequest } | null>(null);

  const submit = async () => {
    const request = toRequest(answers);
    setSubmitting(true);
    setBanner(null);
    setFieldErrors({});
    try {
      const response = await submitCheckin(request);
      setResult({ response, request });
      window.scrollTo({ top: 0 });
    } catch (error) {
      // Answers stay in state, so the user can fix them or simply try again.
      if (errorKind(error) === "invalid" && error instanceof WellnessApiError) {
        const inline = errorsByAnswer(error.fields);
        setFieldErrors(inline);
        const target = stepForFieldErrors(error.fields);
        if (target !== null) setStep(target);
        setBanner(Object.keys(inline).length > 0 ? ERRORS.invalid : error.message);
      } else {
        setBanner(friendlyMessage(error));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const retryable = banner !== null && Object.keys(fieldErrors).length === 0 && banner !== ERRORS.invalid;

  return (
    <div className="min-h-screen bg-[#FBF9F4] text-[#1C352D]">
      <Container className="max-w-3xl py-10 sm:py-14">
        <BackLink className="mb-4" />
        {result ? (
          <CheckinResults
            response={result.response}
            request={result.request}
            onEdit={() => {
              setResult(null);
              setStep(0);
            }}
            onStartAgain={() => {
              setResult(null);
              setAnswers(EMPTY_ANSWERS);
              setStep(0);
            }}
          />
        ) : (
          <div className="flex flex-col gap-6">
            <header>
              <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">{PAGE.eyebrow}</p>
              <h1 className="mt-2 font-heading text-3xl font-normal sm:text-4xl">{PAGE.title}</h1>
              <p className={`mt-3 max-w-2xl text-base ${muted}`}>{PAGE.intro}</p>
            </header>
            <CheckinQuestionnaire
              answers={answers}
              onChange={(patch) => {
                setAnswers((a) => ({ ...a, ...patch }));
                // A changed answer clears its own server message.
                setFieldErrors((errors) => {
                  const next = { ...errors };
                  for (const key of Object.keys(patch) as (keyof Answers)[]) delete next[key];
                  return next;
                });
              }}
              step={step}
              onStep={setStep}
              onSubmit={submit}
              submitting={submitting}
              fieldErrors={fieldErrors}
              banner={banner}
              onRetry={retryable ? submit : undefined}
            />
          </div>
        )}
      </Container>
    </div>
  );
}

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(undefined, { day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" });
}

const SLEEP_FLAG_TEXT = { concern: HISTORY.sleepConcern, ok: HISTORY.sleepOk, guidance: HISTORY.sleepGuidance };

/** /wellness-check-in/history: the signed-in user's own check-ins (RequireAuth in App.tsx). */
export function WellnessHistoryPage() {
  const [page, setPage] = useState(0);
  const { state, setState, reload, retry } = useCheckinHistory(page, HISTORY_PAGE_SIZE);
  const [del, dispatch] = useReducer(deleteReducer, INITIAL_DELETE_STATE);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const relogin = useRelogin();

  const confirmDelete = async () => {
    if (!canConfirm(del)) return;
    const target = del.kind === "confirmOne" ? { one: del.id } : "all";
    dispatch({ type: "confirm" });
    try {
      if (target === "all") {
        await deleteAllCheckins();
        setState({ status: "ready", data: { items: [], page: 0, size: HISTORY_PAGE_SIZE, totalElements: 0, totalPages: 0 } });
        setPage(0);
      } else {
        await deleteCheckin(target.one);
        // Update the list in place, then refetch the page so it fills up again (no full reload).
        if (state.status === "ready") {
          const items = state.data.items.filter((i) => i.id !== target.one);
          setState({
            status: "ready",
            data: { ...state.data, items, totalElements: Math.max(0, state.data.totalElements - 1) },
          });
          const nextPage = pageAfterDelete(page, items.length);
          if (nextPage !== page) setPage(nextPage);
          else reload();
        }
      }
      dispatch({ type: "done" });
    } catch (error) {
      if (errorKind(error) === "unauthorized") {
        relogin();
        return;
      }
      dispatch({ type: "failed", message: ERRORS.deleteFailed });
    }
  };

  const toggle = (id: string) =>
    setExpanded((set) => {
      const next = new Set(set);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const total = state.status === "ready" ? state.data.totalElements : 0;
  const deleting = del.kind === "deleting";

  return (
    <div className="min-h-screen bg-[#FBF9F4] text-[#1C352D]">
      <Container className="max-w-3xl py-10 sm:py-14">
        <BackLink className="mb-4" />
        <header className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">{PAGE.eyebrow}</p>
            <h1 className="mt-2 font-heading text-3xl font-normal sm:text-4xl">{PAGE.historyTitle}</h1>
            <p className={`mt-2 text-sm ${muted}`}>{PAGE.historyIntro}</p>
          </div>
          <div className="flex flex-wrap gap-3">
            <Link href={WELLNESS_ROUTES.checkin} className={primaryButton}>
              {HISTORY.newCheckin}
            </Link>
            {total > 0 ? (
              <button type="button" className={secondaryButton} onClick={() => dispatch({ type: "askAll" })}>
                <Trash2 className="size-4" aria-hidden="true" />
                {HISTORY.deleteAll}
              </button>
            ) : null}
          </div>
        </header>

        {state.status === "loading" ? (
          <div className="flex justify-center py-24 text-[#4A5750]">
            <Loader2 className="size-6 motion-safe:animate-spin" aria-label={COMMON.loading} />
          </div>
        ) : state.status === "error" ? (
          <div role="alert" className={`${card} flex flex-col items-start gap-3`}>
            <p className="text-sm">
              {ERRORS.loadHistory} {friendlyMessage(state.error)}
            </p>
            <button type="button" className={secondaryButton} onClick={retry}>
              <RotateCcw className="size-4" aria-hidden="true" />
              {ERRORS.retry}
            </button>
          </div>
        ) : state.data.items.length === 0 ? (
          <div className={`${card} flex flex-col items-start gap-3`}>
            <span className="flex size-10 items-center justify-center rounded-full bg-[#ECEFE6] text-[#233E33]">
              <HeartPulse className="size-5" aria-hidden="true" />
            </span>
            <p className="text-sm font-semibold">{HISTORY.empty}</p>
            <p className={`text-sm ${muted}`}>{HISTORY.emptyText}</p>
            <Link href={WELLNESS_ROUTES.checkin} className={primaryButton}>
              {HISTORY.start}
            </Link>
          </div>
        ) : (
          <>
            <ul className="flex flex-col gap-3">
              {state.data.items.map((item) => (
                <HistoryRow
                  key={item.id}
                  item={item}
                  open={expanded.has(item.id)}
                  onToggle={() => toggle(item.id)}
                  onDelete={() => dispatch({ type: "askOne", id: item.id })}
                />
              ))}
            </ul>
            {state.data.totalPages > 1 ? (
              <nav className="mt-6 flex items-center justify-between" aria-label={HISTORY.pageOf(page + 1, state.data.totalPages)}>
                <button type="button" className={secondaryButton} disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  <ChevronLeft className="size-4" aria-hidden="true" />
                  {HISTORY.previous}
                </button>
                <span className={`text-sm ${muted}`}>{HISTORY.pageOf(page + 1, state.data.totalPages)}</span>
                <button
                  type="button"
                  className={secondaryButton}
                  disabled={page + 1 >= state.data.totalPages}
                  onClick={() => setPage((p) => p + 1)}
                >
                  {HISTORY.next}
                  <ChevronRight className="size-4" aria-hidden="true" />
                </button>
              </nav>
            ) : null}
          </>
        )}
      </Container>

      <Dialog
        open={del.kind === "confirmOne" || (deleting && del.target !== "all")}
        onOpenChange={(open) => !open && dispatch({ type: "cancel" })}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{HISTORY.confirmOneTitle}</DialogTitle>
            <DialogDescription>{HISTORY.confirmOneText}</DialogDescription>
          </DialogHeader>
          {del.kind === "confirmOne" && del.error ? (
            <p role="alert" className="text-sm text-[#8A3324]">
              {del.error}
            </p>
          ) : null}
          <DialogFooter>
            <button type="button" className={secondaryButton} disabled={deleting} onClick={() => dispatch({ type: "cancel" })}>
              {HISTORY.cancel}
            </button>
            <button type="button" className={dangerButton} disabled={deleting} onClick={confirmDelete}>
              {deleting ? HISTORY.deleting : HISTORY.confirmDelete}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={del.kind === "confirmAll" || (deleting && del.target === "all")}
        onOpenChange={(open) => !open && dispatch({ type: "cancel" })}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{HISTORY.confirmAllTitle}</DialogTitle>
            <DialogDescription>{HISTORY.confirmAllText(total)}</DialogDescription>
          </DialogHeader>
          <label className="flex flex-col gap-1.5 text-sm font-medium text-[#1C352D]">
            {HISTORY.confirmAllType}
            <input
              type="text"
              autoComplete="off"
              value={del.kind === "confirmAll" ? del.typed : HISTORY.confirmWord}
              disabled={deleting}
              onChange={(e) => dispatch({ type: "typed", text: e.target.value })}
              className={`rounded-xl border border-[#C8CEBE] bg-white px-3 py-2 text-base ${focusRing}`}
            />
          </label>
          {del.kind === "confirmAll" && del.error ? (
            <p role="alert" className="text-sm text-[#8A3324]">
              {del.error}
            </p>
          ) : null}
          <DialogFooter>
            <button type="button" className={secondaryButton} disabled={deleting} onClick={() => dispatch({ type: "cancel" })}>
              {HISTORY.cancelAll}
            </button>
            <button type="button" className={dangerButton} disabled={deleting || !canConfirm(del)} onClick={confirmDelete}>
              {deleting ? HISTORY.deleting : HISTORY.deleteAll}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function HistoryRow({
  item,
  open,
  onToggle,
  onDelete,
}: {
  item: CheckinItem;
  open: boolean;
  onToggle: () => void;
  onDelete: () => void;
}) {
  const detailsId = `checkin-${item.id}`;
  const flag = sleepFlag(item.result.sleep, item.suggestedActions);
  return (
    <li className="rounded-2xl border border-[#E5E8E1] bg-white">
      <div className="flex flex-wrap items-center gap-3 p-4">
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold">
            <time dateTime={item.createdAt}>{formatDate(item.createdAt)}</time>
          </p>
          <p className={`text-xs ${muted}`}>{Q.segment.options[item.segment] ?? item.segment}</p>
        </div>
        <BandChip band={item.result.mood.risk_band} />
        <span className="rounded-full bg-[#F5F7F2] px-3 py-1 text-xs font-medium text-[#3D4A43]">{SLEEP_FLAG_TEXT[flag]}</span>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={detailsId}
          onClick={onToggle}
          className={`inline-flex cursor-pointer items-center gap-1 rounded-full px-2 py-1 text-sm font-medium text-[#233E33] hover:bg-[#F4F6F0] ${focusRing}`}
        >
          {open ? HISTORY.hideDetails : HISTORY.details}
          <ChevronDown className={`size-4 motion-safe:transition-transform ${open ? "rotate-180" : ""}`} aria-hidden="true" />
        </button>
        <button
          type="button"
          onClick={onDelete}
          aria-label={`${HISTORY.delete}: ${formatDate(item.createdAt)}`}
          className={`inline-flex size-9 cursor-pointer items-center justify-center rounded-full text-[#8A3324] hover:bg-[#FBEBE7] ${focusRing}`}
        >
          <Trash2 className="size-4" aria-hidden="true" />
        </button>
      </div>
      <div id={detailsId} hidden={!open} className="flex flex-col gap-4 border-t border-[#EEF0EA] p-4">
        <div>
          <p className="mb-2 text-xs font-semibold text-[#4A5750]">{RESULT.driversTitle}</p>
          <DriverList drivers={item.result.mood.drivers} kind="mood" />
        </div>
        {item.result.sleep.in_range && item.result.sleep.display_text ? (
          <p className="text-sm text-[#3D4A43]">{item.result.sleep.display_text}</p>
        ) : null}
        <div>
          <p className="mb-2 text-xs font-semibold text-[#4A5750]">{RESULT.actionsTitle}</p>
          <ActionList codes={item.suggestedActions} />
        </div>
      </div>
    </li>
  );
}
