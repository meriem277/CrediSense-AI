# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npx ng build --configuration production

# ---- runtime (Angular SSR / Express) ----
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=4000
COPY --from=build /app/dist/frontend ./dist/frontend
COPY --from=build /app/package.json /app/package-lock.json ./
RUN npm ci --omit=dev
EXPOSE 4000
CMD ["node", "dist/frontend/server/server.mjs"]
