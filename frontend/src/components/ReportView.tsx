import { useRef, useState } from "react";
import type { AnswerReport, Evidence, Finding, Report, RequirementVerdict, Scores } from "../api/types";
import {
  answerStatusLabel, Badge, basisLabel, confidenceLabel, findingLabel, findingTone,
  formatDate, formatTimecode, questionKindLabel, RecommendationBadge, RequirementStatusBadge,
} from "./ui";

const SCORE_LABELS: Record<keyof Scores, string> = {
  technicalCorrectness: "Техническая корректность",
  depth: "Глубина понимания",
  relevance: "Релевантность вопросу",
  example: "Есть практический пример",
  personalContribution: "Личный вклад",
  scaleAndMetrics: "Масштаб и метрики",
};

/**
 * Карточка результата — единственный экран, на котором защищается вся работа.
 * Один компонент для рекрутера и нанимающего менеджера, отличаются только
 * действия сверху.
 */
export default function ReportView({ report, actions }: { report: Report; actions?: React.ReactNode }) {
  return (
    <>
      <div className="panel">
        <div className="row between">
          <div>
            <h1 style={{ marginBottom: 8 }}>{report.candidateName}</h1>
            <p className="sub" style={{ marginBottom: 0 }}>
              {report.vacancyTitle} · {report.vacancyGrade} · интервью завершено {formatDate(report.completedAt)}
            </p>
          </div>
          {actions}
        </div>

        <div className="row" style={{ marginTop: 16, alignItems: "flex-start", gap: 24 }}>
          <div>
            <div className="score">{report.overallScore}</div>
            <div className="muted small">из 10</div>
          </div>
          <div className="grow">
            <div className="row" style={{ marginBottom: 8 }}>
              <RecommendationBadge value={report.recommendation} />
              <span className="muted small">уверенность модели: {confidenceLabel[report.confidence]}</span>
            </div>
            <p style={{ margin: 0 }}>{report.summary}</p>
          </div>
        </div>
      </div>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Обязательные требования</h2>
        <RequirementTable verdicts={report.requirementsMust} />
        <h2>Желательные требования</h2>
        <RequirementTable verdicts={report.requirementsNice} />
      </div>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Разбор по вопросам</h2>
        {report.answers.map((answer) => <AnswerBlock key={answer.questionId} answer={answer} />)}
      </div>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Сильные стороны</h2>
        <FindingList findings={report.strengths} />
        <h2>Риски и зоны роста</h2>
        <FindingList findings={report.risks} />
      </div>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Навыки</h2>
        <h3>Выявлены</h3>
        <div className="row">
          {report.skillsFound.length === 0
            ? <span className="muted small">ничего не подтверждено</span>
            : report.skillsFound.map((s) => <Badge key={s} tone="ok">{s}</Badge>)}
        </div>
        <h3>Не проверены этим интервью</h3>
        <p className="small muted" style={{ marginTop: 0 }}>
          Это не значит, что навыка нет — значит, что интервью его не затронуло.
        </p>
        <div className="row">
          {report.skillsNotChecked.map((s) => <Badge key={s} tone="muted">{s}</Badge>)}
        </div>
      </div>

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Проверить на следующем этапе</h2>
        <ul className="tight">
          {report.nextStageQuestions.map((q, i) => <li key={i}>{q}</li>)}
        </ul>
      </div>

      <div className="panel">
        <div className="row between">
          <h2 style={{ margin: 0 }}>Обратная связь кандидату</h2>
          <button onClick={() => navigator.clipboard.writeText(report.candidateFeedback)}>
            Скопировать
          </button>
        </div>
        <p className="small muted">
          Система её не отправляет: текст передаёт кандидату рекрутер.
        </p>
        <div style={{ whiteSpace: "pre-wrap" }}>{report.candidateFeedback}</div>
      </div>

      {/* Технические события отделены от профессиональной оценки намеренно */}
      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Техническая информация</h2>
        <p className="small muted">
          Не является частью профессиональной оценки. Антифрод-событие — сигнал
          для человека, а не доказательство нарушения.
        </p>
        <ul className="tight small">
          {report.technical.notes.map((n, i) => <li key={i}>{n}</li>)}
          {report.technical.unrateableAnswers > 0 && (
            <li>Не удалось оценить ответов: {report.technical.unrateableAnswers}</li>
          )}
          {report.technical.failedAnswers > 0 && (
            <li>Ошибок обработки: {report.technical.failedAnswers}</li>
          )}
          {report.technical.antifraudEvents.length === 0
            ? <li>Антифрод-событий не зафиксировано</li>
            : report.technical.antifraudEvents.map((e, i) => (
                <li key={i}>{e.type} · {formatDate(e.occurredAt)}</li>
              ))}
        </ul>
        <p className="small muted mono">
          модель {report.meta.model} · промпт {report.meta.promptVersion} ·
          рубрика {report.meta.rubricVersion} · вопросы версии {report.meta.questionSetVersion} ·
          собрано {formatDate(report.meta.generatedAt)}
        </p>
      </div>
    </>
  );
}

function RequirementTable({ verdicts }: { verdicts: RequirementVerdict[] }) {
  if (verdicts.length === 0) return <p className="muted small">Нет требований этого типа.</p>;
  return (
    <table>
      <thead>
        <tr>
          <th>Требование</th>
          <th style={{ width: 60 }}>Вес</th>
          <th style={{ width: 150 }}>Статус</th>
          <th style={{ width: 120 }}>Основание</th>
          <th>Комментарий</th>
        </tr>
      </thead>
      <tbody>
        {verdicts.map((v) => (
          <tr key={v.requirementId}>
            <td>
              {v.text}
              {v.stopFactor && <> <Badge tone="bad">стоп-фактор</Badge></>}
            </td>
            <td className="muted">{v.weight}</td>
            <td><RequirementStatusBadge status={v.status} /></td>
            <td className="muted small">{basisLabel[v.basis]}</td>
            <td className="small">{v.comment}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function AnswerBlock({ answer }: { answer: AnswerReport }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [raw, setRaw] = useState(false);

  const seek = (evidence: Evidence) => {
    if (videoRef.current && evidence.startMs !== null) {
      videoRef.current.currentTime = evidence.startMs / 1000;
      videoRef.current.play().catch(() => undefined);
    }
  };

  const tone =
    answer.status === "EVALUATED" ? ""
    : answer.status === "SKIPPED" ? "warn"
    : "bad";

  return (
    <details>
      <summary>
        <b>{answer.ord}.</b> {answer.questionText.slice(0, 110)}
        {answer.questionText.length > 110 ? "…" : ""}{" "}
        <Badge tone="muted">{questionKindLabel[answer.kind]}</Badge>{" "}
        <Badge tone={tone}>{answerStatusLabel[answer.status]}</Badge>
      </summary>

      <div style={{ paddingLeft: 12 }}>
        {answer.videoUrl && <video ref={videoRef} src={answer.videoUrl} controls />}

        {answer.transcriptRefined && (
          <>
            <div className="row" style={{ marginTop: 12 }}>
              <b className="small">Расшифровка</b>
              <button className="link small" onClick={() => setRaw(!raw)}>
                {raw ? "показать выправленную" : "показать сырую"}
              </button>
            </div>
            <p className="small" style={{ marginTop: 4 }}>
              {raw ? answer.transcriptRaw : answer.transcriptRefined}
            </p>
          </>
        )}

        {answer.scores && (
          <table style={{ maxWidth: 460, marginTop: 8 }}>
            <tbody>
              {(Object.keys(SCORE_LABELS) as (keyof Scores)[]).map((key) => (
                <tr key={key}>
                  <td className="small">{SCORE_LABELS[key]}</td>
                  <td style={{ width: 90 }}>
                    {answer.scores![key] === null
                      ? <span className="muted small">судить нельзя</span>
                      : <b>{answer.scores![key]} / 5</b>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {answer.comment && <p className="small" style={{ marginTop: 8 }}>{answer.comment}</p>}

        {answer.evidence.length > 0 && (
          <>
            <b className="small">Цитаты</b>
            {answer.evidence.map((e, i) => (
              <div key={i} className="quote" onClick={() => seek(e)} title="Перемотать видео на этот момент">
                «{e.quote}» <span className="mono">{formatTimecode(e.startMs)}</span>
              </div>
            ))}
          </>
        )}
      </div>
    </details>
  );
}

function FindingList({ findings }: { findings: Finding[] }) {
  if (findings.length === 0) return <p className="muted small">Пусто.</p>;
  return (
    <ul className="tight">
      {findings.map((f, i) => (
        <li key={i} style={{ marginBottom: 8 }}>
          <Badge tone={findingTone[f.type]}>{findingLabel[f.type]}</Badge> {f.text}
          {f.evidence.map((e, j) => (
            <div key={j} className="quote">«{e.quote}» <span className="mono">{formatTimecode(e.startMs)}</span></div>
          ))}
        </li>
      ))}
    </ul>
  );
}
