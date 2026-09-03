import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Прокси, а не CORS: origin один, куки ведут себя как в проде.
    // Загрузка медиа идёт мимо — presigned URL ведёт прямо в MinIO.
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
