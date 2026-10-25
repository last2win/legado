#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${KOTLIN_COMPILER_CLASSPATH:?Set compiler classpath}"
: "${SECURITY_TEST_DEPS:?Directory with stdlib.jar, coroutines.jar, nanohttpd.jar, nanohttpd-websocket.jar, okio.jar, serialization-json.jar, serialization-core.jar}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
classpath="$(printf '%s:' "$SECURITY_TEST_DEPS"/*.jar)"
common=core/src/commonMain/kotlin/io/legado/app/web/api/WebAccess.kt
"$JAVA_HOME/bin/java" -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -Xmulti-platform -Xcommon-sources="$common,data/src/commonMain/kotlin/io/legado/app/help/http/CookieScope.kt" \
  -classpath "$classpath" -d "$work/tests.jar" \
  "$common" data/src/commonMain/kotlin/io/legado/app/help/http/CookieScope.kt \
  core/src/commonMain/kotlin/io/legado/app/web/api/WebApi.kt \
  core/src/commonMain/kotlin/io/legado/app/web/api/WebApiRequest.kt \
  core/src/commonMain/kotlin/io/legado/app/web/api/WebApiResponse.kt \
  core/src/commonMain/kotlin/io/legado/app/web/utils/AssetsWeb.kt \
  core/src/jvmAndAndroidMain/kotlin/io/legado/app/web/api/WebAccessToken.jvmAndAndroid.kt \
  core/src/jvmAndAndroidMain/kotlin/io/legado/app/web/HttpServer.kt \
  core/src/jvmAndAndroidMain/kotlin/io/legado/app/web/WebSocketServer.kt \
  data/src/jvmAndAndroidMain/kotlin/io/legado/app/help/http/SSLHelper.kt \
  scripts/security-tests/transport-stubs/*.kt scripts/security-tests/transport/*.kt
"$JAVA_HOME/bin/java" -cp "$work/tests.jar:$classpath" security.TransportRegressionKt

for identity in trusted untrusted; do
  "$JAVA_HOME/bin/keytool" -genkeypair -alias test -keyalg RSA -keysize 2048 -validity 1 \
    -dname CN=localhost -ext SAN=dns:localhost -keystore "$work/$identity.p12" \
    -storetype PKCS12 -storepass test-only -keypass test-only -noprompt >/dev/null 2>&1
done
"$JAVA_HOME/bin/java" -cp "$work/tests.jar:$classpath" security.TlsRegressionKt "$work/trusted.p12" "$work/untrusted.p12"
