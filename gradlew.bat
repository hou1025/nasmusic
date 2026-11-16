@echo off
setlocal
set APP_HOME=%~dp0
set JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%JAR%" (
  echo Missing gradle\wrapper\gradle-wrapper.jar
  echo Open this project with Android Studio to regenerate the wrapper.
  exit /b 1
)
if defined JAVA_HOME (set JAVACMD=%JAVA_HOME%\bin\java.exe) else (set JAVACMD=java.exe)
"%JAVACMD%" -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
