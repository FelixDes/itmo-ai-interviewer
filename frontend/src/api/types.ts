// Типы контракта. Источник истины — docs/api.md §2 и §3.

export type Grade = "JUNIOR" | "MIDDLE" | "MIDDLE_PLUS" | "SENIOR" | "LEAD";
export type RequirementKind = "MUST" | "NICE";
export type InterviewStatus =
  | "CREATED" | "READY" | "IN_PROGRESS" | "ANALYZING"
  | "READY_REPORT" | "FAILED" | "EXPIRED";
export type QuestionKind = "CORE" | "PERSONAL" | "FOLLOWUP";
export type QuestionOrigin = "VACANCY" | "RESUME" | "PREVIOUS_ANSWER";
export type QuestionSetSource = "LLM" | "REFERENCE";
export type ProcessingStage = "SAVING" | "TRANSCRIBING" | "EVALUATING" | "PREPARING_NEXT";
export type AnswerStatus = "EVALUATED" | "UNRATEABLE" | "SKIPPED" | "FAILED";
export type Recommendation = "FIT" | "NOT_FIT" | "NEEDS_CHECK";
export type Confidence = "LOW" | "MEDIUM" | "HIGH";
export type RequirementStatus = "CONFIRMED" | "PARTIAL" | "NOT_CONFIRMED" | "NOT_CHECKED";
export type EvidenceBasis = "ANSWER" | "RESUME" | "NONE";
export type FindingType = "FACT" | "INFERENCE" | "ASSUMPTION";
export type AntifraudEventType = "TAB_HIDDEN" | "COPY" | "PASTE";

export interface CurrentUser { username: string; displayName: string }

export interface RequirementInput {
  id?: string;
  text: string;
  kind: RequirementKind;
  weight: number;
  stopFactor: boolean;
  notVerifiable: boolean;
}
export interface Requirement extends Required<Omit<RequirementInput, "id">> { id: string }

export interface VacancyInput {
  title: string;
  grade: Grade;
  description: string;
  requirements: RequirementInput[];
}

export interface QuestionSetRef {
  id: string; version: number; frozen: boolean; questionCount: number;
}

export interface Vacancy {
  id: string; title: string; grade: Grade; description: string;
  requirements: Requirement[];
  activeQuestionSet: QuestionSetRef | null;
  draftQuestionSet: QuestionSetRef | null;
  createdAt: string;
}

export interface VacancyListItem {
  id: string; title: string; grade: Grade;
  interviewCount: number; hasFrozenQuestionSet: boolean; createdAt: string;
}

export interface QuestionInput {
  id?: string; text: string; requirementId: string | null; strongSignals: string[];
}

export interface Question {
  id: string; ord: number; text: string;
  requirementId: string | null; strongSignals: string[]; origin: QuestionOrigin;
}

export interface QuestionSet {
  id: string; vacancyId: string; version: number; source: QuestionSetSource;
  frozen: boolean; frozenAt: string | null; questions: Question[]; createdAt: string;
}

export interface ShareLink {
  url: string; token: string; expiresAt: string; revoked: boolean;
}

export interface InterviewListItem {
  id: string; candidateName: string; status: InterviewStatus;
  recommendation: Recommendation | null; overallScore: number | null;
  answered: number; planned: number; createdAt: string; completedAt: string | null;
}

export interface InterviewDetail {
  id: string; vacancyId: string; vacancyTitle: string; questionSetVersion: number;
  candidateName: string; resumeText: string | null; status: InterviewStatus;
  candidateUrl: string; expiresAt: string; consentAt: string | null;
  answered: number; planned: number; share: ShareLink | null;
  reportAvailable: boolean;
  failure: { stage: string; message: string } | null;
  createdAt: string; completedAt: string | null;
}

export interface CandidateQuestion {
  id: string; ord: number; kind: QuestionKind; text: string;
  audioUrl: string; requirementText: string | null;
}

export interface CandidateState {
  status: InterviewStatus;
  vacancyTitle: string; companyName: string; candidateName: string;
  answered: number; planned: number;
  expectedDurationMinutes: number; maxAnswerDurationSec: number;
  rules: string[]; consentText: string; antifraudEnabled: boolean;
  currentQuestion: CandidateQuestion | null;
  processing: { answerId: string; stage: ProcessingStage } | null;
  message: string | null;
}

export interface AnswerUpload {
  answerId: string; uploadUrl: string; contentType: string; expiresAt: string;
}

export interface Evidence {
  answerId: string | null; questionOrd: number; quote: string;
  startMs: number | null; endMs: number | null;
}
export interface Finding { text: string; type: FindingType; evidence: Evidence[] }

export interface Scores {
  technicalCorrectness: number | null;
  depth: number | null;
  relevance: number | null;
  example: number | null;
  personalContribution: number | null;
  scaleAndMetrics: number | null;
}

export interface RequirementVerdict {
  requirementId: string; text: string; kind: RequirementKind; weight: number;
  stopFactor: boolean; status: RequirementStatus; basis: EvidenceBasis;
  comment: string; evidence: Evidence[];
}

export interface AnswerReport {
  answerId: string | null; questionId: string; ord: number; kind: QuestionKind;
  questionText: string; requirementId: string | null; origin: QuestionOrigin;
  parentQuestionId: string | null; status: AnswerStatus;
  videoUrl: string | null; durationMs: number | null;
  transcriptRefined: string | null; transcriptRaw: string | null;
  scores: Scores | null; confidence: Confidence | null;
  comment: string | null; evidence: Evidence[];
}

export interface Report {
  interviewId: string; candidateName: string; vacancyTitle: string;
  vacancyGrade: Grade; completedAt: string;
  recommendation: Recommendation; overallScore: number; confidence: Confidence;
  summary: string;
  requirementsMust: RequirementVerdict[];
  requirementsNice: RequirementVerdict[];
  answers: AnswerReport[];
  strengths: Finding[]; risks: Finding[];
  skillsFound: string[]; skillsNotChecked: string[];
  nextStageQuestions: string[]; candidateFeedback: string;
  technical: {
    antifraudEvents: { type: AntifraudEventType; occurredAt: string }[];
    unrateableAnswers: number; failedAnswers: number; notes: string[];
  };
  meta: {
    model: string; promptVersion: string; rubricVersion: string;
    questionSetVersion: number; generatedAt: string;
  };
}

export interface DemoSeedResult { vacancyId: string; questionSetId: string }

export interface ApiErrorBody {
  code: string; message: string; details: Record<string, string> | null;
}
