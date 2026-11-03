import { useReducer, useState } from "react";
import Link from "next/link";
import { ChevronLeft, ChevronRight, HeartPulse, Loader2, RotateCcw, Trash2 } from "lucide-react";

import { BackLink } from "@/components/navigation/back-link";
import { deleteAllCheckins, deleteCheckin, submitCheckin } from "@/features/wellness/api.ts";
import { CheckinQuestionnaire } from "@/features/wellness/components/checkin-questionnaire.tsx";
import { CheckinResults } from "@/features/wellness/components/checkin-results.tsx";
import { DriverList } from "@/features/wellness/components/result-parts.tsx";
import { WellnessShell } from "@/features/wellness/components/shell.tsx";
import {
  dangerLink,
  eyebrow,
  focusRing,
  ghostButton,
  h1,
  h3,
  iconButton,
  primaryButton,
} from "@/features/wellness/components/styles.ts";
import { BandPill, Card, CardTint, ConfirmDelete, Pill } from "@/features/wellness/components/ui.tsx";
import { COMMON, ERRORS, HISTORY, PAGE, RESULT } from "@/features/wellness/copy.ts";
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
  STEP_FIELDS,
  errorsByAnswer,
  stepForFieldErrors,
  toRequest,
  type Answers,
} from "@/features/wellness/questionnaire.ts";
import { WELLNESS_ROUTES, daysAgo, sleepFlag } from "@/features/wellness/results.ts";
import type { CheckinItem, CheckinRequest, CheckinResponse } from "@/features/wellness/types.ts";

type FieldErrors = Partial<Record<keyof Answers, string>>;

/** /wellness-snapshot: the questionnaire, then the result. Open to guests (their result is not stored). */
export function WellnessCheckinPage() {
  const [answers, setAnswers] = useState<Answers>(EMPTY_ANSWERS);
  const [step, setStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [result, setResult] = useState<{ response: CheckinResponse; request: CheckinRequest } | null>(null);
  const [openOptional, setOpenOptional] = useState(false);

  const submit = async () => {
    const request = toRequest(answers);
    setSubmitting(true);
    setBanner(null);
    setFieldErrors({});
    try {
      const response = await submitCheckin(request);
      setResult({ response, request });
      setOpenOptional(false);
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
    <WellnessShell top={<BackLink className="mb-4" />}>
      {result ? (
        <CheckinResults
          response={result.response}
          request={result.request}
          onEdit={() => {
            setResult(null);
            setStep(0);
          }}
          onAddOptional={() => {
            setResult(null);
            setOpenOptional(true);
            setStep(STEP_FIELDS.length - 1);
            window.scrollTo({ top: 0 });
          }}
          onStartAgain={() => {
            setResult(null);
            setAnswers(EMPTY_ANSWERS);
            setStep(0);
            window.scrollTo({ top: 0 });
          }}
        />
      ) : (
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
          optionalOpenAtStart={openOptional}
        />
      )}
    </WellnessShell>
  );
}

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(undefined, { day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" });
}

const SLEEP_FLAG_TEXT = { concern: HISTORY.sleepConcern, ok: HISTORY.sleepOk, guidance: HISTORY.sleepGuidance };

/** /wellness-snapshot/history: the signed-in user's own check-ins (RequireAuth in App.tsx). */
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
  const askingAll = del.kind === "confirmAll" || (deleting && del.target === "all");
  const askingOne = (id: string) => (del.kind === "confirmOne" && del.id === id) || (deleting && del.target !== "all" && del.target.one === id);

  return (
    <WellnessShell top={<BackLink className="mb-4" />}>
      <Card as="div" className="mx-auto flex max-w-[860px] flex-col gap-6">
        <header className="flex flex-wrap items-end justify-between gap-4">
          <div className="flex flex-col gap-2">
            <p className={eyebrow}>{PAGE.eyebrow}</p>
            <h1 className={h1}>{PAGE.historyTitle}</h1>
            <p className="text-[15px] text-(--wn-ink-2)">{PAGE.historyPrivacy}</p>
          </div>
          <Link href={WELLNESS_ROUTES.checkin} className={primaryButton}>
            {HISTORY.newCheckin}
          </Link>
        </header>

        {askingAll ? (
          <ConfirmDelete
            title={HISTORY.confirmAllTitle}
            text={HISTORY.confirmAllText(total)}
            error={del.kind === "confirmAll" ? del.error : undefined}
            cancelLabel={HISTORY.cancelAll}
            confirmLabel={HISTORY.confirmDeleteAll}
            busyLabel={HISTORY.deleting}
            busy={deleting}
            confirmDisabled={!canConfirm(del)}
            onCancel={() => dispatch({ type: "cancel" })}
            onConfirm={confirmDelete}
          >
            <label className="flex flex-col gap-1.5 text-[15px] font-semibold text-(--wn-ink)">
              {HISTORY.confirmAllType}
              <input
                type="text"
                autoComplete="off"
                value={del.kind === "confirmAll" ? del.typed : HISTORY.confirmWord}
                disabled={deleting}
                onChange={(e) => dispatch({ type: "typed", text: e.target.value })}
                className={`h-11 max-w-xs rounded-[14px] border-[1.5px] border-(--wn-line) bg-(--wn-surface) px-3 text-base ${focusRing}`}
              />
            </label>
          </ConfirmDelete>
        ) : null}

        {state.status === "loading" ? (
          <div className="flex justify-center py-16 text-(--wn-ink-2)">
            <Loader2 className="size-6 motion-safe:animate-spin" strokeWidth={2} aria-label={COMMON.loading} />
          </div>
        ) : state.status === "error" ? (
          <div role="alert" className="flex flex-col items-start gap-3">
            <p className="text-[15px]">
              {ERRORS.loadHistory} {friendlyMessage(state.error)}
            </p>
            <button type="button" className={ghostButton} onClick={retry}>
              <RotateCcw className="size-4" strokeWidth={2} aria-hidden="true" />
              {ERRORS.retry}
            </button>
          </div>
        ) : state.data.items.length === 0 ? (
          <CardTint className="flex flex-col items-start gap-3">
            <span className="flex size-10 items-center justify-center rounded-[12px] bg-(--wn-surface) text-(--wn-brand)">
              <HeartPulse className="size-5" strokeWidth={2} aria-hidden="true" />
            </span>
            <p className="text-[16px] font-bold">{HISTORY.empty}</p>
            <p className="text-[15px] text-(--wn-ink-2)">{HISTORY.emptyText}</p>
            <Link href={WELLNESS_ROUTES.checkin} className={primaryButton}>
              {HISTORY.start}
            </Link>
          </CardTint>
        ) : (
          <>
            <ul className="flex flex-col divide-y divide-(--wn-line) border-y border-(--wn-line)">
              {state.data.items.map((item) => (
                <HistoryRow
                  key={item.id}
                  item={item}
                  open={expanded.has(item.id)}
                  onToggle={() => toggle(item.id)}
                  onDelete={() => dispatch({ type: "askOne", id: item.id })}
                  confirm={
                    askingOne(item.id) ? (
                      <ConfirmDelete
                        title={HISTORY.confirmOneTitle}
                        text={HISTORY.confirmOneText}
                        error={del.kind === "confirmOne" ? del.error : undefined}
                        cancelLabel={HISTORY.cancel}
                        confirmLabel={HISTORY.confirmDelete}
                        busyLabel={HISTORY.deleting}
                        busy={deleting}
                        onCancel={() => dispatch({ type: "cancel" })}
                        onConfirm={confirmDelete}
                      />
                    ) : null
                  }
                />
              ))}
            </ul>
            <div className="flex flex-wrap items-center justify-between gap-3">
              {state.data.totalPages > 1 ? (
                <nav className="flex items-center gap-3" aria-label={HISTORY.pageOf(page + 1, state.data.totalPages)}>
                  <button type="button" className={ghostButton} disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                    <ChevronLeft className="size-4" strokeWidth={2} aria-hidden="true" />
                    {HISTORY.previous}
                  </button>
                  <span className="text-sm text-(--wn-ink-2)">{HISTORY.pageOf(page + 1, state.data.totalPages)}</span>
                  <button
                    type="button"
                    className={ghostButton}
                    disabled={page + 1 >= state.data.totalPages}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    {HISTORY.next}
                    <ChevronRight className="size-4" strokeWidth={2} aria-hidden="true" />
                  </button>
                </nav>
              ) : (
                <span />
              )}
              <button type="button" className={`${dangerLink} text-[15px]`} disabled={deleting} onClick={() => dispatch({ type: "askAll" })}>
                <Trash2 className="size-4" strokeWidth={2} aria-hidden="true" />
                {HISTORY.deleteAll}
              </button>
            </div>
          </>
        )}
      </Card>
    </WellnessShell>
  );
}

function HistoryRow({
  item,
  open,
  onToggle,
  onDelete,
  confirm,
}: {
  item: CheckinItem;
  open: boolean;
  onToggle: () => void;
  onDelete: () => void;
  confirm: React.ReactNode;
}) {
  const detailsId = `checkin-${item.id}`;
  const flag = sleepFlag(item.result.sleep, item.suggestedActions);
  const date = formatDate(item.createdAt);
  return (
    <li className="flex flex-col gap-3 py-4">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="min-w-[9rem] flex-1">
          <p className="text-[15px] font-bold text-(--wn-ink)">
            <time dateTime={item.createdAt}>{date}</time>
          </p>
          <p className="text-sm text-(--wn-ink-2)">{HISTORY.relative(daysAgo(item.createdAt))}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <BandPill band={item.result.mood.risk_band} />
          <Pill variant="brand">{SLEEP_FLAG_TEXT[flag]}</Pill>
          <span className="text-sm text-(--wn-ink-2)">{RESULT.confidence.label(item.result.mood.confidence)}</span>
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            aria-expanded={open}
            aria-controls={detailsId}
            onClick={onToggle}
            className={`${ghostButton} px-4`}
          >
            {open ? HISTORY.hide : HISTORY.view}
          </button>
          <button
            type="button"
            onClick={onDelete}
            aria-label={`${HISTORY.delete}: ${date}`}
            className={`${iconButton} text-(--wn-high-text) hover:bg-(--wn-high-soft)`}
          >
            <Trash2 className="size-[18px]" strokeWidth={2} aria-hidden="true" />
          </button>
        </div>
      </div>
      <div id={detailsId} hidden={!open} className="flex flex-col gap-3 rounded-[14px] bg-(--wn-bg) p-4">
        <h3 className={h3}>{RESULT.driversTitle}</h3>
        <DriverList drivers={item.result.mood.drivers} />
        {item.result.sleep.in_range && item.result.sleep.display_text ? (
          <p className="text-[15px] text-(--wn-ink)">{item.result.sleep.display_text}</p>
        ) : null}
      </div>
      {confirm}
    </li>
  );
}
