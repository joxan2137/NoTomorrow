# syntax=docker/dockerfile:1

# ---- build -----------------------------------------------------------------
FROM node:22-slim AS build
WORKDIR /app
ENV NODE_ENV=development
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

# ---- runtime ---------------------------------------------------------------
FROM node:22-slim AS runtime
ENV NODE_ENV=production TZ=UTC PORT=8080
WORKDIR /app
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node package.json ./
COPY --chown=node:node migrations ./migrations
COPY --chown=node:node data ./data
USER node
EXPOSE 8080
CMD ["node", "dist/index.js"]
