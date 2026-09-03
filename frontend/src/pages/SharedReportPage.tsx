import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { Report } from "../api/types";
import ReportView from "../components/ReportView";

/**
 * Нанимающий менеджер: один отчёт только для чтения.
 * Выхода в список кандидатов нет физически — в токене лежит одно интервью.
 */
export default function SharedReportPage() {
  const { token } = useParams<{ token: string }>();
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.sharedReport(token!)
      .then(setReport)
      .catch((e) => setError(
        e instanceof ApiError
          ? e.code === "LINK_REVOKED" ? "Ссылка отозвана рекрутером."
            : e.code === "LINK_EXPIRED" ? "Срок действия ссылки истёк."
            : e.code === "REPORT_NOT_READY" ? "Результат ещё готовится, зайдите позже."
            : "Ссылка недействительна."
          : (e as Error).message,
      ));
  }, [token]);

  if (error) {
    return (
      <div className="layout narrow" style={{ paddingTop: 80 }}>
        <div className="panel">
          <h1>Результат недоступен</h1>
          <p className="muted">{error}</p>
        </div>
      </div>
    );
  }

  if (!report) return <div className="layout"><span className="spinner" /></div>;

  return (
    <div className="layout">
      <p className="sub">Результат интервью · только просмотр</p>
      <ReportView report={report} />
    </div>
  );
}
