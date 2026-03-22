# Build stage: dependencies install in their own layer so that source-only
# changes do not reinstall the world on every rebuild.
FROM node:20-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite inlines this at build time - it is baked into the bundle, not read at
# runtime - so it has to be a build argument. The default is relative because
# production serves the API from this same origin through the proxy below;
# an absolute URL is only needed when the two are deployed apart.
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
