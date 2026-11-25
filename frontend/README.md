# SavahBot dashboard

The frontend is the SavahBot web dashboard, built with TypeScript and Vite. Its package version is **2.0.0**, the dashboard/backend version line.

## Local development

From this directory:

```bash
npm install
npm run dev
```

Before publishing frontend changes, run:

```bash
npm run typecheck
npm test
npm run build
```

Deployment configuration is in `vercel.json`. Environment variables and deployment steps are documented in [`../docs/ENVIRONMENT.md`](../docs/ENVIRONMENT.md) and [`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md).

**Project status:** SavahBot 2.0.0 is the final documented dashboard release; active feature development is closed.
