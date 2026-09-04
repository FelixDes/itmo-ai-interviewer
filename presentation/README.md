# Презентация

Slidev. Слайды — `slides.md`, скриншоты — `public/images/`.

```bash
npm install
npm run dev      # откроется на http://localhost:3030
npm run build    # статика в dist/
npm run export   # PDF
```

Для экспорта в PDF нужен браузер, он ставится отдельно и только под эту задачу —
в обычную установку не тянется:

```bash
npm i -D playwright-chromium && npm run export
```

Управление показом: стрелки, `f` — полный экран, `o` — обзор всех слайдов,
`d` — тёмная тема, `e` — рисовать поверх слайда.

Скриншоты сейчас заглушки. Что снять и как назвать — `public/images/README.md`.
