FROM node:24-alpine AS build
WORKDIR /web
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# nginx serves the static files and forwards /api to the ledger.
# LEDGER_UPSTREAM is substituted into the template at start (e.g. ledger:8080).
FROM nginxinc/nginx-unprivileged:1.30-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /web/dist /usr/share/nginx/html
ENV LEDGER_UPSTREAM=ledger:8080
EXPOSE 8080
