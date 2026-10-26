#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK installation}"
: "${KOTLIN_COMPILER_CLASSPATH:?Set KOTLIN_COMPILER_CLASSPATH to compiler and dependency jars (or kotlinc/lib/*)}"
: "${KOTLIN_STDLIB:?Set KOTLIN_STDLIB to kotlin-stdlib.jar}"
: "${SECURITY_TEST_DEPS:?Set dependency directory with okio.jar, coroutines.jar, serialization-core.jar}"
classpath="$KOTLIN_STDLIB:$SECURITY_TEST_DEPS/okio.jar:$SECURITY_TEST_DEPS/coroutines.jar:$SECURITY_TEST_DEPS/serialization-core.jar:$SECURITY_TEST_DEPS/junit.jar:$SECURITY_TEST_DEPS/hamcrest.jar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
common=core/src/commonMain/kotlin/io/legado/app/web/api/WebAccess.kt
"$JAVA_HOME/bin/java" -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -Xmulti-platform -Xcommon-sources="$common,data/src/commonMain/kotlin/io/legado/app/help/http/CookieScope.kt,data/src/commonMain/kotlin/io/legado/app/model/fileBook/Inflate.kt,data/src/commonMain/kotlin/io/legado/app/model/fileBook/ZipIOException.kt" -classpath "$classpath" -d "$work/tests.jar" \
  core/src/commonMain/kotlin/io/legado/app/web/api/WebApiRequest.kt \
  "$common" core/src/jvmAndAndroidMain/kotlin/io/legado/app/web/api/WebAccessToken.jvmAndAndroid.kt \
  data/src/commonMain/kotlin/io/legado/app/help/http/CookieScope.kt \
  data/src/commonMain/kotlin/io/legado/app/help/http/NativeProxyConfig.kt \
  data/src/commonMain/kotlin/io/legado/app/help/storage/ArchiveLimits.kt \
  data/src/commonMain/kotlin/io/legado/app/help/storage/ArchiveReadLimits.kt \
  data/src/commonMain/kotlin/io/legado/app/help/archive/TarReader.kt \
  data/src/commonMain/kotlin/io/legado/app/model/script/DeferredClose.kt \
  data/src/nativeMain/kotlin/io/legado/app/help/storage/NativeZipCodec.native.kt \
  data/src/commonMain/kotlin/io/legado/app/model/fileBook/Inflate.kt \
  data/src/jvmAndAndroidMain/kotlin/io/legado/app/model/fileBook/Inflate.jvmAndAndroid.kt \
  data/src/commonMain/kotlin/io/legado/app/model/fileBook/RemoteZipCore.kt \
  data/src/commonMain/kotlin/io/legado/app/model/fileBook/ZipStructure.kt \
  data/src/commonMain/kotlin/io/legado/app/model/fileBook/RangedSource.kt \
  data/src/commonMain/kotlin/io/legado/app/model/fileBook/ZipIOException.kt \
  data/src/jvmAndAndroidMain/kotlin/io/legado/app/model/fileBook/ZipIOException.jvmAndAndroid.kt \
  data/src/commonMain/kotlin/io/legado/app/help/compress/StreamDecompressor.kt \
  data/src/jvmAndAndroidTest/kotlin/io/legado/app/model/fileBook/ZipTestFixtures.kt \
  data/src/jvmAndAndroidTest/kotlin/io/legado/app/model/fileBook/RemoteZipCoreTest.kt \
  data/src/jvmAndAndroidTest/kotlin/io/legado/app/model/fileBook/ZipEntryReaderTest.kt \
  data/src/jvmAndAndroidTest/kotlin/io/legado/app/model/fileBook/InflateTest.kt \
  data/src/jvmAndAndroidTest/kotlin/io/legado/app/model/fileBook/ArchiveReadLimitsTest.kt \
  scripts/security-tests/stubs/*.kt scripts/security-tests/*Regression.kt
for test in ZipRegression BoundaryRegression ArchiveEntryRegression; do
  "$JAVA_HOME/bin/java" -Xmx256m -cp "$work/tests.jar:$classpath" "security.${test}Kt"
done

"$JAVA_HOME/bin/java" -Xmx256m -cp "$work/tests.jar:$classpath" org.junit.runner.JUnitCore \
  io.legado.app.model.fileBook.RemoteZipCoreTest \
  io.legado.app.model.fileBook.ZipEntryReaderTest \
  io.legado.app.model.fileBook.InflateTest
"$JAVA_HOME/bin/java" -Xmx256m -cp "$work/tests.jar:$classpath" org.junit.runner.JUnitCore \
  io.legado.app.model.fileBook.ArchiveReadLimitsTest
