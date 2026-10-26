# Jenkins for PayLedger (ADR 0022): JDK 21 for Maven, Node for the UI, the Docker CLI + compose for the stack,
# Chromium for the Playwright smoke test. Runs as root on purpose: it needs the host's Docker socket (local use only).
FROM jenkins/jenkins:lts-jdk21
USER root

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg python3 openssl xz-utils \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && echo "deb [signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian bookworm stable" > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
 && rm -rf /var/lib/apt/lists/*

ARG NODE_VERSION=22.11.0
RUN curl -fsSL https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz | tar -xJ -C /usr/local --strip-components=1

# Same version as @playwright/test in admin-ui/package.json
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
# Playwright's own dependency installer does not know Debian 13, so the libraries Chromium needs are listed here.
RUN apt-get update  && apt-get install -y --no-install-recommends fonts-liberation fonts-unifont libasound2t64 libatk-bridge2.0-0t64       libatk1.0-0t64 libcairo2 libcups2t64 libdbus-1-3 libdrm2 libgbm1 libnspr4 libnss3 libpango-1.0-0 libx11-6 libxcb1       libxcomposite1 libxdamage1 libxext6 libxfixes3 libxkbcommon0 libxrandr2 libxshmfence1  && rm -rf /var/lib/apt/lists/*  && npx --yes playwright@1.49.0 install chromium

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY jenkins.yaml /usr/share/jenkins/ref/jenkins.yaml

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false" \
    CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/jenkins.yaml
