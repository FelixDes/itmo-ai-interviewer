import type * as T from "./types";

/** Ошибка с кодом из docs/api.md §1: по коду принимаются решения в UI. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, string> | null = null,
  ) {
    super(message);
  }
}

async function request<R>(method: string, path: string, body?: unknown): Promise<R> {
  const response = await fetch(path, {
    method,
    credentials: "include",
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    let payload: T.ApiErrorBody | null = null;
    try {
      payload = await response.json();
    } catch {
      // тело может быть пустым — это нормально
    }
    throw new ApiError(
      response.status,
      payload?.code ?? "UNKNOWN",
      payload?.message ?? `Ошибка ${response.status}`,
      payload?.details ?? null,
    );
  }

  if (response.status === 204) return undefined as R;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as R;
}

const get = <R>(path: string) => request<R>("GET", path);
const post = <R>(path: string, body?: unknown) => request<R>("POST", path, body);
const put = <R>(path: string, body?: unknown) => request<R>("PUT", path, body);
const del = <R>(path: string) => request<R>("DELETE", path);

export const api = {
  // ---------- аутентификация ----------
  login: (username: string, password: string) =>
    post<void>("/api/auth/login", { username, password }),
  logout: () => post<void>("/api/auth/logout"),
  me: () => get<T.CurrentUser>("/api/auth/me"),

  // ---------- вакансии ----------
  vacancies: () => get<T.VacancyListItem[]>("/api/vacancies"),
  vacancy: (id: string) => get<T.Vacancy>(`/api/vacancies/${id}`),
  createVacancy: (input: T.VacancyInput) => post<T.Vacancy>("/api/vacancies", input),
  updateVacancy: (id: string, input: T.VacancyInput) => put<T.Vacancy>(`/api/vacancies/${id}`, input),
  seedDemo: () => post<T.DemoSeedResult>("/api/demo/seed"),

  // ---------- наборы вопросов ----------
  questionSets: (vacancyId: string) => get<T.QuestionSet[]>(`/api/vacancies/${vacancyId}/question-sets`),
  generateQuestions: (vacancyId: string, source: T.QuestionSetSource) =>
    post<T.QuestionSet>(`/api/vacancies/${vacancyId}/question-sets`, { source }),
  questionSet: (id: string) => get<T.QuestionSet>(`/api/question-sets/${id}`),
  updateQuestions: (id: string, questions: T.QuestionInput[]) =>
    put<T.QuestionSet>(`/api/question-sets/${id}`, { questions }),
  reviseQuestionSet: (id: string) => post<T.QuestionSet>(`/api/question-sets/${id}/revise`),
  freezeQuestionSet: (id: string) => post<T.QuestionSet>(`/api/question-sets/${id}/freeze`),

  // ---------- интервью ----------
  interviews: (vacancyId?: string) =>
    get<T.InterviewListItem[]>(`/api/interviews${vacancyId ? `?vacancyId=${vacancyId}` : ""}`),
  interview: (id: string) => get<T.InterviewDetail>(`/api/interviews/${id}`),
  createInterview: (vacancyId: string, candidateName: string, resumeText: string | null) =>
    post<T.InterviewDetail>("/api/interviews", { vacancyId, candidateName, resumeText }),
  report: (id: string) => get<T.Report>(`/api/interviews/${id}/report`),
  reanalyze: (id: string) => post<T.InterviewDetail>(`/api/interviews/${id}/reanalyze`),
  share: (id: string, ttlDays?: number) => post<T.ShareLink>(`/api/interviews/${id}/share`, { ttlDays }),
  revokeShare: (id: string) => del<void>(`/api/interviews/${id}/share`),

  // ---------- кандидат ----------
  candidateState: (token: string) => get<T.CandidateState>(`/api/s/${token}`),
  consent: (token: string) => post<T.CandidateState>(`/api/s/${token}/consent`),
  start: (token: string) => post<T.CandidateState>(`/api/s/${token}/start`),
  startAnswer: (token: string, questionId: string, contentType: string) =>
    post<T.AnswerUpload>(`/api/s/${token}/answers`, { questionId, contentType }),
  completeAnswer: (token: string, answerId: string, durationMs: number) =>
    post<T.CandidateState>(`/api/s/${token}/answers/${answerId}/complete`, { durationMs }),
  retryUpload: (token: string, answerId: string) =>
    post<T.AnswerUpload>(`/api/s/${token}/answers/${answerId}/retry-upload`),
  skipQuestion: (token: string, questionId: string) =>
    post<T.CandidateState>(`/api/s/${token}/questions/${questionId}/skip`),
  antifraudEvent: (token: string, type: T.AntifraudEventType) =>
    // fire-and-forget: событие не должно ломать интервью
    post<void>(`/api/s/${token}/events`, { type }).catch(() => undefined),

  // ---------- нанимающий менеджер ----------
  sharedReport: (token: string) => get<T.Report>(`/api/r/${token}`),
};

/**
 * Загрузка ответа прямо в MinIO по presigned URL.
 * Content-Type обязан совпадать с тем, что вернул сервер, иначе подпись не сойдётся.
 */
export async function uploadMedia(upload: T.AnswerUpload, blob: Blob): Promise<void> {
  const response = await fetch(upload.uploadUrl, {
    method: "PUT",
    headers: { "Content-Type": upload.contentType },
    body: blob,
  });
  if (!response.ok) throw new Error(`Не удалось загрузить запись: ${response.status}`);
}
