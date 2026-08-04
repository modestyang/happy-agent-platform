@REM Licensed to the Apache Software Foundation (ASF) under one or more
@REM contributor license agreements. See the NOTICE file distributed with
@REM this work for additional information regarding copyright ownership.
@echo off
setlocal
set "MAVEN_PROJECTBASEDIR=%~dp0"
if not defined JAVA_HOME goto findJavaFromPath
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
goto execute
:findJavaFromPath
set "JAVA_EXE=java.exe"
:execute
"%JAVA_EXE%" %MAVEN_OPTS% -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" -classpath "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*
endlocal
