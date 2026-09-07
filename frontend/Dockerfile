# Build stage
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Run stage — nginx-unprivileged runs as a non-root user (uid 101) and listens on 8080, so a
# container escape does not run as root (Trivy DS002 / CIS Docker Benchmark). Trivy's DS002 check
# is static, so the USER instruction below is required in this Dockerfile even though the base
# image already drops privileges.
FROM nginxinc/nginx-unprivileged:alpine
USER root
COPY --from=build /app/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
