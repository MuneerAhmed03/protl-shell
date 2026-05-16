FROM eclipse-temurin:17-jdk-jammy

ARG DEBIAN_FRONTEND=noninteractive
ARG ANDROID_API_LEVEL=34
ARG ANDROID_BUILD_TOOLS=34.0.0
ARG ANDROID_CMDLINE_TOOLS=11076708
ARG ANDROID_CMDLINE_TOOLS_SHA256
ARG PORTL_BUILDER_VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="Portl Android Builder" \
      org.opencontainers.image.description="Containerized Android builder for Portl shell APK packaging" \
      org.opencontainers.image.version="${PORTL_BUILDER_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/MuneerAhmed03/portl"

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}
ENV GRADLE_USER_HOME=/home/portl/.gradle

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        jq \
        unzip \
        zip \
        openssl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 portl \
    && useradd --uid 10001 --gid 10001 --create-home --shell /bin/bash portl

RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" /opt/portl /work/input /work/output "${GRADLE_USER_HOME}"

RUN curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS}_latest.zip" -o /tmp/cmdline-tools.zip \
    && test -n "${ANDROID_CMDLINE_TOOLS_SHA256}" \
    && echo "${ANDROID_CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-${ANDROID_API_LEVEL}" \
        "build-tools;${ANDROID_BUILD_TOOLS}"

WORKDIR /opt/portl/portl-shell

# Copy the shell project so the image contains the Android build template.
COPY portl-shell/ /opt/portl/portl-shell/

# Point Gradle/AGP to the SDK path inside the image.
RUN printf "sdk.dir=%s\n" "${ANDROID_SDK_ROOT}" > /opt/portl/portl-shell/local.properties \
    && chmod +x /opt/portl/portl-shell/gradlew /opt/portl/portl-shell/builder/build-apk.sh

RUN chown -R portl:portl /opt/portl /work /home/portl

USER portl

RUN ./gradlew --no-daemon tasks >/dev/null

WORKDIR /work

CMD ["/opt/portl/portl-shell/builder/build-apk.sh"]
