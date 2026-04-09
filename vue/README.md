# SqMusic Plus Frontend (Vue 3)

## Development

```bash
cd vue
npm install
npm run dev
```

Default dev server: `http://localhost:5173`

## API Proxy

- Dev: `/api` -> `http://localhost:8099` (configured in `vite.config.ts`)
- Prod image: `/api` -> `http://sqmusic_main:8099` (configured in `nginx.conf`)

## Build

```bash
cd vue
npm run build
```

## Build Docker Image

```bash
docker build -t simple_sq_music_plus_web:new ./vue
```
