#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK installation}"
: "${KOTLIN_COMPILER_CLASSPATH:?Set KOTLIN_COMPILER_CLASSPATH to compiler and dependency jars (or kotlinc/lib/*)}"
: "${KOTLIN_STDLIB:?Set KOTLIN_STDLIB to kotlin-stdlib.jar}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
common=core/src/commonMain/kotlin/io/legado/app/web/api/WebAccess.kt
"$JAVA_HOME/bin/java" -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -Xmulti-platform -Xcommon-sources="$common,data/src/commonMain/kotlin/io/legado/app/help/http/CookieScope.kt" -classpath "$KOTLIN_STDLIB" -d "$work/tests.jar" \
  "$common" core/src/jvmAndAndroidMain/kotlin/io/legado/app/web/api/WebAccessToken.jvmAndAndroid.kt \
  data/src/commonMain/kotlin/io/legado/app/help/http/CookieScope.kt \
  data/src/commonMain/kotlin/io/legado/app/help/http/NativeProxyConfig.kt \
  data/src/commonMain/kotlin/io/legado/app/help/storage/ArchiveLimits.kt \
  data/src/commonMain/kotlin/io/legado/app/help/archive/TarReader.kt \
  data/src/commonMain/kotlin/io/legado/app/model/script/DeferredClose.kt \
  data/src/nativeMain/kotlin/io/legado/app/help/storage/NativeZipCodec.native.kt \
  scripts/security-tests/stubs/*.kt scripts/security-tests/*Regression.kt
for test in ZipRegression BoundaryRegression; do
  "$JAVA_HOME/bin/java" -Xmx256m -cp "$work/tests.jar:$KOTLIN_STDLIB" "security.${test}Kt"
done
