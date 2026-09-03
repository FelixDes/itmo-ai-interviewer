import type { ReactNode } from "react";
import type {
  AnswerStatus, Confidence, InterviewStatus, QuestionKind,
  Recommendation, RequirementStatus, FindingType, EvidenceBasis,
} from "../api/types";

/** Человеческие подписи вместо кодов: enum'ы наружу не показываем. */

export const statusLabel: Record<InterviewStatus, string> = {
  CREATED: "ссылка выдана",
  READY: "согласие дано",
  IN_PROGRESS: "интервью идёт",
  ANALYZING: "обрабатывается",
  READY_REPORT: "готово",
  FAILED: "ошибка",
  EXPIRED: "ссылка истекла",
};

export const recommendationLabel: Record<Recommendation, string> = {
  FIT: "Подходит",
  NOT_FIT: "Не подходит",
  NEEDS_CHECK: "Требуется дополнительная проверка",
};

export const requirementStatusLabel: Record<RequirementStatus, string> = {
  CONFIRMED: "подтверждено",
  PARTIAL: "частично",
  NOT_CONFIRMED: "не подтверждено",
  NOT_CHECKED: "не проверялось",
};

export const basisLabel: Record<EvidenceBasis, string> = {
  ANSWER: "из ответа",
  RESUME: "из резюме",
  NONE: "нет основания",
};

export const answerStatusLabel: Record<AnswerStatus, string> = {
  EVALUATED: "оценён",
  UNRATEABLE: "не удалось оценить",
  SKIPPED: "пропущен",
  FAILED: "ошибка обработки",
};

export const questionKindLabel: Record<QuestionKind, string> = {
  CORE: "основной",
  PERSONAL: "по резюме",
  FOLLOWUP: "уточняющий",
};

export const confidenceLabel: Record<Confidence, string> = {
  LOW: "низкая", MEDIUM: "средняя", HIGH: "высокая",
};

/** Типы выводов различаем визуально: это требование Рамки §8, а не украшение. */
export const findingLabel: Record<FindingType, string> = {
  FACT: "факт",
  INFERENCE: "вывод модели",
  ASSUMPTION: "предположение",
};

export const findingTone: Record<FindingType, string> = {
  FACT: "ok", INFERENCE: "", ASSUMPTION: "warn",
};

export function Badge({ tone = "", children }: { tone?: string; children: ReactNode }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

export function StatusBadge({ status }: { status: InterviewStatus }) {
  const tone =
    status === "READY_REPORT" ? "ok"
    : status === "FAILED" || status === "EXPIRED" ? "bad"
    : status === "ANALYZING" ? "warn"
    : "";
  return <Badge tone={tone}>{statusLabel[status]}</Badge>;
}

export function RecommendationBadge({ value }: { value: Recommendation }) {
  const tone = value === "FIT" ? "ok" : value === "NOT_FIT" ? "bad" : "warn";
  return <Badge tone={tone}>{recommendationLabel[value]}</Badge>;
}

export function RequirementStatusBadge({ status }: { status: RequirementStatus }) {
  const tone =
    status === "CONFIRMED" ? "ok"
    : status === "PARTIAL" ? "warn"
    : status === "NOT_CONFIRMED" ? "bad"
    : "muted";
  return <Badge tone={tone}>{requirementStatusLabel[status]}</Badge>;
}

export function Spinner({ text }: { text?: string }) {
  return (
    <span>
      <span className="spinner" /> {text && <span className="muted"> {text}</span>}
    </span>
  );
}

export function ErrorBox({ error }: { error: string | null }) {
  if (!error) return null;
  return <div className="panel error">{error}</div>;
}

export function Progress({ value, total }: { value: number; total: number }) {
  const percent = total > 0 ? Math.round((value / total) * 100) : 0;
  return (
    <div className="progress">
      <div style={{ width: `${percent}%` }} />
    </div>
  );
}

export function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("ru-RU", {
    day: "2-digit", month: "2-digit", year: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}

export function formatTimecode(ms: number | null): string {
  if (ms === null) return "";
  const total = Math.floor(ms / 1000);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}
