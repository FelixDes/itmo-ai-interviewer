/**
 * Предупреждение кандидату. Не блокирует запись: остановить её на середине
 * ответа было бы хуже самого нарушения — кандидат потерял бы уже сказанное.
 */
export default function AntifraudWarning({ text, onDismiss }: {
  text: string;
  onDismiss: () => void;
}) {
  return (
    <div className="overlay" role="alertdialog" aria-modal="true">
      <div className="panel" style={{ maxWidth: 460, margin: 0 }}>
        <h3 style={{ marginTop: 0 }}>Нарушение условий прохождения</h3>
        <p>{text}</p>
        <button className="primary" onClick={onDismiss} autoFocus>Понятно</button>
      </div>
    </div>
  );
}
