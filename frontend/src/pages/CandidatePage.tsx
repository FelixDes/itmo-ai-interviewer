import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { api, ApiError, uploadMedia } from "../api/client";
import type { CandidateState, ProcessingStage } from "../api/types";
import { ErrorBox, Progress, Spinner } from "../components/ui";
import AntifraudWarning from "../components/AntifraudWarning";
import { detectSecondScreen, useAntifraud } from "../components/antifraud";

const STAGE_LABEL: Record<ProcessingStage, string> = {
  SAVING: "Сохраняем запись",
  TRANSCRIBING: "Расшифровываем ответ",
  EVALUATING: "Разбираем ответ",
  PREPARING_NEXT: "Готовим следующий вопрос",
};

/** Браузеры пишут в разных контейнерах — берём первый поддерживаемый. */
function pickMimeType(): string {
  const candidates = [
    "video/webm;codecs=vp9,opus",
    "video/webm;codecs=vp8,opus",
    "video/webm",
    "video/mp4",
  ];
  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) ?? "";
}

export default function CandidatePage() {
  const { token } = useParams<{ token: string }>();
  const [state, setState] = useState<CandidateState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    setState(await api.candidateState(token!));
  }, [token]);

  useEffect(() => {
    refresh().catch((e) => setError(
      e instanceof ApiError && e.status === 404
        ? "Ссылка не найдена. Проверьте адрес или запросите новую у рекрутера."
        : (e as Error).message,
    ));
  }, [refresh]);

  // Поллинг раз в секунду, пока идёт обработка или сборка карточки
  useEffect(() => {
    if (!state) return;
    const waiting = state.processing !== null || state.status === "ANALYZING";
    if (!waiting) return;
    const timer = setInterval(() => { refresh().catch(() => undefined); }, 1000);
    return () => clearInterval(timer);
  }, [state, refresh]);

  // Антифрод. О фиксации кандидат предупреждён на первом экране,
  // до того как что-либо записывается.
  const antifraud = useAntifraud(
    token!,
    state?.antifraudEnabled ?? false,
    state?.status === "IN_PROGRESS",
  );

  if (error && !state) {
    return (
      <div className="layout narrow" style={{ paddingTop: 80 }}>
        <div className="panel"><h1>Не получилось открыть интервью</h1><p className="muted">{error}</p></div>
      </div>
    );
  }
  if (!state) return <div className="layout"><span className="spinner" /></div>;

  const act = async (action: () => Promise<CandidateState>) => {
    setBusy(true);
    setError(null);
    try { setState(await action()); }
    catch (e) { setError(e instanceof ApiError ? e.message : (e as Error).message); }
    finally { setBusy(false); }
  };

  const done = state.status === "ANALYZING" || state.status === "READY_REPORT" || state.status === "FAILED";

  return (
    <div className="layout narrow">
      {antifraud.warning && (
        <AntifraudWarning text={antifraud.warning} onDismiss={antifraud.dismissWarning} />
      )}
      <p className="sub">{state.companyName} · {state.vacancyTitle}</p>
      <ErrorBox error={error} />

      {state.status === "EXPIRED" && (
        <div className="panel"><h1>Ссылка недействительна</h1><p className="muted">{state.message}</p></div>
      )}

      {state.status === "CREATED" && (
        <ConsentScreen state={state} busy={busy} onAgree={() => act(() => api.consent(token!))} />
      )}

      {state.status === "READY" && (
        <DeviceCheck busy={busy} onReady={() => act(() => api.start(token!))} />
      )}

      {state.status === "IN_PROGRESS" && state.processing && (
        <div className="panel">
          <h1>Обрабатываем ответ</h1>
          <p><Spinner text={STAGE_LABEL[state.processing.stage]} /></p>
          <p className="muted small">Не закрывайте вкладку, это занимает несколько секунд.</p>
        </div>
      )}

      {state.status === "IN_PROGRESS" && !state.processing && state.currentQuestion && (
        <QuestionScreen
          key={state.currentQuestion.id}
          token={token!}
          state={state}
          blockedBySecondScreen={antifraud.secondScreen}
          tabSwitches={antifraud.tabSwitches}
          onFinished={setState}
          onError={setError}
        />
      )}

      {done && (
        <div className="panel">
          <h1>Интервью отправлено</h1>
          <p>{state.message}</p>
          <p className="muted small">Эту вкладку можно закрыть.</p>
        </div>
      )}
    </div>
  );
}

function ConsentScreen({ state, busy, onAgree }: {
  state: CandidateState; busy: boolean; onAgree: () => void;
}) {
  const [agreed, setAgreed] = useState(false);
  return (
    <div className="panel">
      <h1>Здравствуйте, {state.candidateName}</h1>
      <p>
        Это асинхронное техническое интервью: вопросы задаёт система, собеседника
        на линии нет. Вопросов {state.planned}, займёт примерно {state.expectedDurationMinutes} минут.
      </p>
      <h3>Как это устроено</h3>
      <ul className="tight">
        {state.rules.map((rule, i) => <li key={i}>{rule}</li>)}
        {state.antifraudEnabled && (
          <li>
            Фиксируется уход со вкладки, копирование и вставка текста, а также
            подключённый второй экран.
          </li>
        )}
      </ul>
      <div className="panel" style={{ background: "#f9fafb" }}>
        <label className="row" style={{ alignItems: "flex-start", cursor: "pointer" }}>
          <input type="checkbox" style={{ width: "auto", marginTop: 3 }}
            checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
          <span className="grow small" style={{ color: "var(--text)" }}>{state.consentText}</span>
        </label>
      </div>
      <button className="primary" disabled={!agreed || busy} onClick={onAgree}>Продолжить</button>
    </div>
  );
}

function DeviceCheck({ busy, onReady }: { busy: boolean; onReady: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [denied, setDenied] = useState(false);
  const [level, setLevel] = useState(0);
  // null — браузер не поддерживает Window Management API, проверку пропускаем
  const [extended, setExtended] = useState<boolean | null>(detectSecondScreen());

  useEffect(() => {
    if (detectSecondScreen() === null) return;
    const check = () => setExtended(detectSecondScreen());
    const target = screen as unknown as EventTarget;
    target.addEventListener?.("change", check);
    const timer = setInterval(check, 2000);
    return () => {
      target.removeEventListener?.("change", check);
      clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    let active = true;
    let audioContext: AudioContext | null = null;
    navigator.mediaDevices.getUserMedia({ video: true, audio: true })
      .then((media) => {
        if (!active) { media.getTracks().forEach((t) => t.stop()); return; }
        setStream(media);
        if (videoRef.current) videoRef.current.srcObject = media;

        // Индикатор уровня: кандидат должен видеть, что микрофон живой
        audioContext = new AudioContext();
        const source = audioContext.createMediaStreamSource(media);
        const analyser = audioContext.createAnalyser();
        analyser.fftSize = 512;
        source.connect(analyser);
        const data = new Uint8Array(analyser.frequencyBinCount);
        const tick = () => {
          if (!active) return;
          analyser.getByteTimeDomainData(data);
          const peak = Math.max(...Array.from(data).map((v) => Math.abs(v - 128)));
          setLevel(Math.min(100, Math.round((peak / 64) * 100)));
          requestAnimationFrame(tick);
        };
        tick();
      })
      .catch(() => setDenied(true));

    return () => {
      active = false;
      audioContext?.close();
    };
  }, []);

  // Поток проверки останавливаем: запись откроет свой
  const proceed = () => {
    stream?.getTracks().forEach((t) => t.stop());
    onReady();
  };

  return (
    <div className="panel">
      <h1>Проверка камеры и микрофона</h1>
      {denied ? (
        <>
          <p className="error">Браузер не дал доступ к камере или микрофону.</p>
          <p className="small muted">
            Разрешите доступ в настройках сайта (значок замка слева от адреса)
            и обновите страницу. Без видео интервью пройти нельзя.
          </p>
          <button onClick={() => location.reload()}>Проверить снова</button>
        </>
      ) : (
        <>
          <video ref={videoRef} autoPlay muted playsInline />
          <div className="field" style={{ maxWidth: 520, marginTop: 12 }}>
            <label>Уровень звука — скажите что-нибудь</label>
            <Progress value={level} total={100} />
          </div>

          {extended === true && (
            <div className="blocker">
              <b>Обнаружен второй экран.</b>
              <p className="small" style={{ margin: "4px 0 0" }}>
                Отключите дополнительный монитор и дождитесь, пока это сообщение
                исчезнет. Интервью проходится на одном экране.
              </p>
            </div>
          )}

          <button className="primary" disabled={!stream || busy || extended === true} onClick={proceed}>
            Всё работает, начать интервью
          </button>
          {extended === null && (
            <p className="small muted" style={{ marginTop: 8 }}>
              Ваш браузер не сообщает о подключённых экранах — эта проверка пропущена.
            </p>
          )}
        </>
      )}
    </div>
  );
}

function QuestionScreen({ token, state, blockedBySecondScreen, tabSwitches, onFinished, onError }: {
  token: string;
  state: CandidateState;
  blockedBySecondScreen: boolean;
  tabSwitches: number;
  onFinished: (s: CandidateState) => void;
  onError: (message: string) => void;
}) {
  const question = state.currentQuestion!;
  const videoRef = useRef<HTMLVideoElement>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const [recording, setRecording] = useState(false);
  const [seconds, setSeconds] = useState(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    navigator.mediaDevices.getUserMedia({ video: true, audio: true }).then((media) => {
      if (!active) { media.getTracks().forEach((t) => t.stop()); return; }
      streamRef.current = media;
      if (videoRef.current) videoRef.current.srcObject = media;
    }).catch(() => onError("Нет доступа к камере. Разрешите его и обновите страницу."));
    return () => {
      active = false;
      streamRef.current?.getTracks().forEach((t) => t.stop());
    };
  }, [onError]);

  useEffect(() => {
    if (!recording) return;
    const timer = setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => clearInterval(timer);
  }, [recording]);

  // Мягкий лимит: дошли до предела — останавливаем сами, ответ не теряется
  useEffect(() => {
    if (recording && seconds >= state.maxAnswerDurationSec) stop();
  }, [seconds, recording]);

  // Второй экран подключили посреди ответа: запись завершаем, но не выбрасываем —
  // сказанное отправится, а событие уже зафиксировано
  useEffect(() => {
    if (recording && blockedBySecondScreen) stop();
  }, [blockedBySecondScreen, recording]);

  const start = () => {
    if (!streamRef.current) return;
    chunksRef.current = [];
    const mimeType = pickMimeType();
    const recorder = new MediaRecorder(streamRef.current, mimeType ? { mimeType } : undefined);
    recorder.ondataavailable = (e) => { if (e.data.size > 0) chunksRef.current.push(e.data); };
    recorder.onstop = () => { void send(recorder.mimeType); };
    recorderRef.current = recorder;
    recorder.start();
    setSeconds(0);
    setRecording(true);
  };

  const stop = () => {
    setRecording(false);
    recorderRef.current?.stop();
  };

  const send = async (mimeType: string) => {
    setBusy(true);
    try {
      const blob = new Blob(chunksRef.current, { type: mimeType });
      const contentType = mimeType.split(";")[0] || "video/webm";
      const upload = await api.startAnswer(token, question.id, contentType);
      await uploadMedia(upload, blob);
      onFinished(await api.completeAnswer(token, upload.answerId, seconds * 1000));
    } catch (e) {
      onError(`Не удалось отправить ответ: ${(e as Error).message}. Попробуйте записать ещё раз.`);
    } finally {
      setBusy(false);
    }
  };

  const skip = async () => {
    if (!confirm("Пропустить вопрос? Компетенция останется непроверенной.")) return;
    setBusy(true);
    try { onFinished(await api.skipQuestion(token, question.id)); }
    catch (e) { onError((e as Error).message); }
    finally { setBusy(false); }
  };

  const left = state.maxAnswerDurationSec - seconds;

  return (
    <div className="panel">
      <div className="row between">
        <span className="muted small">
          Вопрос {state.answered + 1} из {state.planned}
          {question.kind === "FOLLOWUP" && " · уточняющий"}
        </span>
        {recording && (
          <span className="small">
            <span className="rec-dot" />идёт запись · осталось {Math.floor(left / 60)}:{String(left % 60).padStart(2, "0")}
          </span>
        )}
      </div>
      <Progress value={state.answered} total={state.planned} />

      <h1 style={{ fontSize: 19, marginTop: 16 }}>{question.text}</h1>
      {question.requirementText && (
        <p className="small muted">Проверяем: {question.requirementText}</p>
      )}

      {/* Вопрос показывается текстом и озвучивается */}
      <audio src={question.audioUrl} autoPlay controls style={{ width: "100%", maxWidth: 520 }} />

      <div style={{ marginTop: 12 }}>
        <video ref={videoRef} autoPlay muted playsInline />
      </div>

      {blockedBySecondScreen && (
        <div className="blocker" style={{ marginTop: 12 }}>
          <b>Подключён второй экран.</b>
          <p className="small" style={{ margin: "4px 0 0" }}>
            Отключите дополнительный монитор, чтобы продолжить. Подключение
            зафиксировано и будет видно рекрутеру.
          </p>
        </div>
      )}

      {tabSwitches > 0 && (
        <p className="small" style={{ color: "var(--warn)", marginTop: 12 }}>
          Зафиксировано переключений на другое окно: {tabSwitches}
        </p>
      )}

      <div className="row" style={{ marginTop: 12 }}>
        {!recording ? (
          <button className="primary" onClick={start} disabled={busy || blockedBySecondScreen}>
            {busy ? <Spinner text="отправляем" /> : "Начать ответ"}
          </button>
        ) : (
          <button className="primary" onClick={stop}>Завершить ответ</button>
        )}
        <span className="grow" />
        <button onClick={skip} disabled={busy || recording}>Пропустить вопрос</button>
      </div>
      <p className="small muted" style={{ marginTop: 8 }}>
        Одна попытка на вопрос. Вернуться к предыдущему нельзя.
      </p>
    </div>
  );
}
