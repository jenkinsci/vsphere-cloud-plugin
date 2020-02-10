:: Interrogates the machine Jenkins slave JNLP start-up parameters and then launches the slave as a JNLP agent.
:: The start-up parameters are expected to be injected in by the hypervisor for cloud-based machines.
:: e.g. We look for vSphere "userinfo", or a magic script file (as used by OpenStack).
::
@ECHO OFF
@SETLOCAL ENABLEEXTENSIONS
@SET SCRIPT_FOLDER=%~dp0
@SET SCRIPT_NAME=%~nx0
@SET SCRIPT_LOGFILE=%~dp0%~n0.out.log

:: We choose some default JVM options as follows
:: "-Xms256M -Xmx4096M"
::    Tell java to grab 256megs immediately, and limit itself to 4gigs.
::    By default java limits itself to an insufficient amount of memory to correctly process
::    all of the Jenkins post-build steps, as they are quite memory-hungry.
:: "-Dhudson.remoting.RemoteClassLoader.force=com.sun.jna.Native"
::    Ask Jenkins to load the com.sun.jna.Native class on startup.
::    The jna library has a class initialisation deadlock bug whereby if one thread tries to
::    load com.sun.jna.Native at the same time another thread tries to load com.sun.jna.Pointer,
::    they'll each start loading, then realise they need the other class to complete and wait
::    (forever) for the other thread to finish its loading.
::    In practice, it means the Jenkins SwapSpaceMonitor code can deadlock with Util.isSymlink
::    unless we get all that classloading out of the way before there are multiple threads.
:: We do not set "-Xrs" for a desktop-based slave because it isn't running "as a service"
:: and hence will get killed by logoff events.
:: We also create a logging configuration file and tell the slave to use it using the setting
:: "-Djava.util.logging.config.file=jenkins-slave.logging.properties"
:: and this is done outside the normal JVM_ARGS code because we're only doing it as a JVM ARG
:: as a workaround because --loggingConfig doesn't work.
@SET DEFAULT_SLAVE_JVM_ARGS=-Xms256M -Xmx4096M -Dhudson.remoting.RemoteClassLoader.force=com.sun.jna.Native

@SET countOfConsecutiveSlaveFailures=0
:start_slave
@CALL :getSlaveSettings
@CALL :runSlave
@IF NOT ERRORLEVEL 1 @(
    @SET countOfConsecutiveSlaveFailures=0
)
@SET /A countOfConsecutiveSlaveFailures=countOfConsecutiveSlaveFailures+1
@CALL :logMessage "WARNING" "This is slave failure count %countOfConsecutiveSlaveFailures% since bootup or last successful completion."
:: The java slave execution stopped, attempt to relaunch it in a short while.
@CALL :wait 30 "before attempting a slave relaunch"
@CALL :logBlankLine
@GOTO start_slave

::***************************************************************************************
:: 1. If %JENKINS_ENV_VARS% batch script exists, then is run to set environment variables that are used to configure the slave.
:: 2. If this fails then it attempts to read the configuration settings from the vSphere "guestinfo" data object.
:: Success is indicated when SLAVE_JNLP_URL is set. In this case SLAVE_HOME, SLAVE_JVM_ARGS, SLAVE_PARAMS and JAVA_HOME will also be set.
:: If the above both fail then they are retried in turn until one of them sets SLAVE_JNLP_URL.
::
:getSlaveSettings
@CALL :clearSlaveSettings
@CALL :getSettingsFromEnvVarsScript
@CALL :showSettings EnvVars
@IF "%SLAVE_JNLP_URL%" NEQ "" @GOTO :eof
@CALL :clearSlaveSettings
@CALL :getSettingsFromVMWareGuestInfo
@CALL :showSettings GuestInfo
@IF "%SLAVE_JNLP_URL%" NEQ "" @GOTO :eof
@CALL :wait 2 "before re-attempting to get startup parameters"
@CALL :logBlankLine
@GOTO getSlaveSettings

::***************************************************************************************
:showSettings
:: Logs the settings that we're going to try launching the slave with.
::
:: %1 = Where the settings came from
@CALL :logMessage "FINE" "%~1"
@CALL :logMessage "FINE" "SLAVE_JNLP_URL=%SLAVE_JNLP_URL%"
@CALL :logMessage "FINE" "SLAVE_JAR_URL=%SLAVE_JAR_URL%"
@CALL :logMessage "FINE" "SLAVE_HOME=%SLAVE_HOME%"
@CALL :logMessage "FINE" "JAVA_HOME=%JAVA_HOME%"
@CALL :logMessage "FINE" "SLAVE_JVM_ARGS=%SLAVE_JVM_ARGS%"
@CALL :logMessage "FINE" "SLAVE_PARAMS=%SLAVE_PARAMS%"
GOTO :eof

:clearSlaveSettings
@SET SLAVE_JNLP_URL=
@SET SLAVE_JAR_URL=
@SET SLAVE_HOME=
@SET JAVA_HOME=
@SET SLAVE_JVM_ARGS=
@SET SLAVE_PARAMS=
@GOTO :eof

::***************************************************************************************
:: Get settings from a an environment variable support script. (OpenStack uses this)
:: If %SCRIPT_FOLDER%jenkins_startup_vars.cmd does not exist it returns immediately with SLAVE_JNLP_URL left unset.
:: Otherwise the script is run and this start-up information is used to set:
::   SLAVE_JNLP_URL      to %SLAVE_JNLP_URL%
::   SLAVE_JAR_URL       to %SLAVE_JAR_URL% or, if this is not set, to the JNLP URL with /computer/... replaced with /jnlpJars/slave.jar
::   SLAVE_HOME          to %SLAVE_JENKINS_HOME% or if this is not set, %SCRIPT_FOLDER% with no trailing backslash.
::   SLAVE_JVM_ARGS      to %SLAVE_JVM_OPTIONS% or if this is not set, %DEFAULT_SLAVE_JVM_ARGS%.
::   SLAVE_PARAMS        to -secret "%SLAVE_JNLP_SECRET%" %SLAVE_PARAMETERS% if SLAVE_JNLP_SECRET is set, else just %SLAVE_PARAMETERS.
::   JAVA_HOME           to %SLAVE_HOME%\jre to ensure job Groovy steps run ok.
::
:getSettingsFromEnvVarsScript
@SET JENKINS_ENV_VARS=%SCRIPT_FOLDER%jenkins_startup_vars.cmd
@CALL :logMessage "FINEST" "Looking for a settings script '%JENKINS_ENV_VARS%'..."
@IF NOT EXIST "%JENKINS_ENV_VARS%" @(
    @CALL :logMessage "FINEST" "... %JENKINS_ENV_VARS%" not found."
    @GOTO :eof
)
@CALL :logMessage "FINEST" "... found settings script '%JENKINS_ENV_VARS%'"
@CALL "%JENKINS_ENV_VARS%"
@IF "%SLAVE_JNLP_URL%" =="" @GOTO :eof
@SET SLAVE_HOME=%SLAVE_JENKINS_HOME%
:: Hint: %SCRIPT_FOLDER:~0,-1% is %SCRIPT_FOLDER% without the trailing backslash
@IF "%SLAVE_HOME%"=="" @SET SLAVE_HOME=%SCRIPT_FOLDER:~0,-1%
@SET SLAVE_JVM_ARGS=%SLAVE_JVM_OPTIONS%
@IF "%SLAVE_JVM_ARGS%"=="" @SET SLAVE_JVM_ARGS=%DEFAULT_SLAVE_JVM_ARGS%
@IF "%SLAVE_JNLP_SECRET%" NEQ "" @(
    @IF "%SLAVE_PARAMETERS%" NEQ "" @(
        @SET SLAVE_PARAMS=-secret "%SLAVE_JNLP_SECRET%" %SLAVE_PARAMETERS%
    ) ELSE @(
        @SET SLAVE_PARAMS=-secret "%SLAVE_JNLP_SECRET%"
    )
) ELSE @(
    @SET SLAVE_PARAMS=%SLAVE_PARAMETERS%
)
@IF "%SLAVE_JAR_URL%"=="" @(
    @CALL :calcJenkinsSlaveJarUrlFromSlaveAgentUrl SLAVE_JNLP_URL SLAVE_JAR_URL
)
@SET JAVA_HOME=%SLAVE_HOME%\jre\
@GOTO :eof

::***************************************************************************************
:: Get settings from VMWare GuestInfo.
:: This is designed to read configuration settings from data that was passed to vSphere for this VM.
:: If no GuestInfo is present or has no JNLP URL it returns immediately with SLAVE_JNLP_URL left unset.
:: Otherwise sets:
::   SLAVE_JNLP_URL      to GuestInfo.SLAVE_JNLP_URL or, if this is not set, to GuestInfo.JNLPURL
::   SLAVE_JAR_URL       to GuestInfo.SLAVE_JAR_URL or, if this is not set, to the JNLP URL with /computer/... replaced with /jnlpJars/slave.jar
::   SLAVE_HOME          to GuestInfo.SLAVE_HOME or if this is not set, %SCRIPT_FOLDER% with no trailing backslash.
::   SLAVE_JVM_ARGS      to GuestInfo.SLAVE_JVM_ARGS or if this is not set, %DEFAULT_SLAVE_JVM_ARGS%.
::   SLAVE_PARAMS        to -secret GuestInfo.SLAVE_SECRET GuestInfo.SLAVE_PARAMETERS if GuestInfo.SLAVE_SECRET is set, else just GuestInfo.SLAVE_PARAMETERS.
::   JAVA_HOME           to %SLAVE_HOME%\jre to ensure job Groovy steps run ok.
::
:getSettingsFromVMWareGuestInfo
@CALL :logMessage "FINEST" "Looking for VMWare GuestInfo..."
@IF NOT EXIST "%ProgramFiles%\VMWare\VMWare Tools" @(
    @CALL :logMessage "FINEST" "... VMWare Tools not present."
    @GOTO :eof
)
@CALL :getGuestInfoProperty SLAVE_JNLP_URL SLAVE_JNLP_URL
@IF "%SLAVE_JNLP_URL%"=="" @(
    @CALL :getGuestInfoProperty JNLPURL SLAVE_JNLP_URL
)
@IF "%SLAVE_JNLP_URL%"=="" @(
    @CALL :logMessage "FINEST" "... neither vSphere guestinfo property SLAVE_JNLP_URL or JNLPURL are present."
    @GOTO :eof
)
@CALL :getGuestInfoProperty SLAVE_JAR_URL SLAVE_JAR_URL
@IF "%SLAVE_JAR_URL%"=="" @(
    @CALL :calcJenkinsSlaveJarUrlFromSlaveAgentUrl SLAVE_JNLP_URL SLAVE_JAR_URL
)
@CALL :getGuestInfoProperty SLAVE_HOME SLAVE_HOME
:: Hint: %SCRIPT_FOLDER:~0,-1% is %SCRIPT_FOLDER% without the trailing backslash
@IF "%SLAVE_HOME%"=="" @SET SLAVE_HOME=%SCRIPT_FOLDER:~0,-1%
@CALL :getGuestInfoProperty SLAVE_JVM_ARGS SLAVE_JVM_ARGS
@IF "%SLAVE_JVM_ARGS%"=="" @SET SLAVE_JVM_ARGS=%DEFAULT_SLAVE_JVM_ARGS%
@SET SLAVE_SECRET=
@CALL :getGuestInfoProperty SLAVE_SECRET SLAVE_SECRET
@SET SLAVE_PARAMS=
@CALL :getGuestInfoProperty SLAVE_PARAMETERS SLAVE_PARAMS
@IF "%SLAVE_SECRET%" NEQ "" @(
    @IF "%SLAVE_PARAMS%" NEQ "" @(
        @SET SLAVE_PARAMS=-secret "%SLAVE_SECRET%" %SLAVE_PARAMS%
    ) ELSE @(
        @SET SLAVE_PARAMS=-secret "%SLAVE_SECRET%"
    )
)
@SET JAVA_HOME=%SLAVE_HOME%\jre\
@GOTO :eof

:getGuestInfoProperty
::%1 = property name to read
::%2 = environment variable to set with the result
@PUSHD "%ProgramFiles%\VMWare\VMWare Tools" || EXIT /b 1
@FOR /f "tokens=*" %%i IN ('vmtoolsd --cmd "info-get guestinfo.%1"') DO @SET %2=%%i
@POPD
@EXIT /b 0

::***************************************************************************************
:calcJenkinsSlaveJarUrlFromSlaveAgentUrl
:: Given the URL of the jnlp endpoint, calculates the URL of the slave.jar file.
:: i.e. Turns someurl/computer/somename/something into someurl/jnlpJars/slave.jar
::
:: %1 = variable name holding slave agent url
:: %2 = variable name to be set to base URL
@CALL :findSubstring "(1,1,500)" 17 "/slave-agent.jnlp" %1 cjbufsau_indexOfSlashSlaveAgent || EXIT /b 1
@IF "%cjbufsau_indexOfSlashSlaveAgent%"=="" EXIT /b 1
@SET /A cjbufsau_indexOfLastCharOfSlaveName=cjbufsau_indexOfSlashSlaveAgent - 1
@CALL :findSubstring "(1,1,%cjbufsau_indexOfLastCharOfSlaveName%)" 1 "/" %1 cjbufsau_indexOfEndOfComputer || EXIT /b 1
@IF "%cjbufsau_indexOfEndOfComputer%"=="" EXIT /b 1
@SET /A cjbufsau_indexOfSlashBeforeComputer=cjbufsau_indexOfEndOfComputer - 8
@CALL SET %2=%%%1:~0,%cjbufsau_indexOfSlashBeforeComputer%%%jnlpJars/slave.jar || EXIT /b 1
@EXIT /b 0

:findSubstring
:: %1 = (max index,step,min index)
:: %2 = length of thing to find
:: %3 = thing to find (in quotes)
:: %4 = name of variable to look in
:: %5 = name of variable to set with index of where it was found
:: Sets %5 to empty if not found, else to the index.
@SET %5=
@FOR /L %%i IN %~1 DO @(
  @CALL :substringIsAtThisIndex %%i %2 "%~3" %4
  @IF NOT ERRORLEVEL 1 @(
    @SET %5=%%i
  )
)
@EXIT /b 0

:substringIsAtThisIndex
:: %1 = index
:: %2 = length of thing to find
:: %3 = thing to find
:: %4 = name of variable to look in
:: returns 0 if it found something, 1 otherwise
@CALL SET substringIsAtThisIndex_str=%%%4:~%1,%2%%
@IF "%substringIsAtThisIndex_str%"=="%~3" @(
  @EXIT /b 0
)
@EXIT /b 1

::***************************************************************************************
:createLoggingConfigFile
:: Creates a Java properties file that configures java.util.logging.Logger
:: which is what the slave uses to log everything.
::
:: %1 = name of our script that's running right now.
:: %2 = name of the file to create
@CALL :outputLoggingConfigFile "%~1" "%~nx2" >"%~2"
@GOTO :eof

:outputLoggingConfigFile
:: Outputs a Java properties file that configures java.util.logging.Logger
:: to stdout.
:: %1 = name of our script that's running right now.
:: %2 = name of the file we're generating.
@ECHO.# Configuration file that controls the slave logging ... if we pass in a JVM
@ECHO.# arg -Djava.util.logging.config.file=%~2
@ECHO.
@ECHO.# Note: This file was generated by the script
@ECHO.# %~1
@ECHO.# at %TIME% on %DATE%.
@ECHO.# DO NOT EDIT THIS FILE MANUALLY.
@ECHO.
@ECHO.# Send output to file as well as console.
@ECHO.handlers=java.util.logging.FileHandler, java.util.logging.ConsoleHandler
@ECHO.
@ECHO.# Log everything equally
@ECHO.java.util.logging.FileHandler.level=ALL
@ECHO.java.util.logging.ConsoleHandler.level=ALL
@ECHO.java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
@ECHO.java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
:: The default log format is:
:: %1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp %2$s%n%4$s: %5$s%6$s%n
:: which results in a two-line output e.g.
:: Apr 25, 2018 12:55:04 PM org.jenkinsci.remoting.protocol.IOHub processScheduledTasks
:: FINEST: 0 scheduled tasks to process
:: We want ISO format date/time (yyyy-mm-ddThh:mm:ss.mmm+zzzz) as it is easier to sort,
:: so "%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp" becomes "%1$tFT%1$tT.%1$tL%1$tz".
:: We also want one line, not two, so "%2$s%n%4$s" becomes "%2$s %4$s",
:: but we want the log-level to happen before the log source, so we swap those around
:: thus "%2$s %4$s" becomes "%4$s %2$s"
:: Lastly, we have to turn % to %% in order to get the desired output from ECHO
@ECHO.java.util.logging.SimpleFormatter.format=%%1$tFT%%1$tT.%%1$tL%%1$tz %%4$s %%2$s: %%5$s%%6$s%%n
@ECHO.
@ECHO.# On the filesystem, we keep up to 9 logs of 50meg each.
@ECHO.java.util.logging.FileHandler.pattern=jenkins-slave.out.%%g.log
@ECHO.java.util.logging.FileHandler.encoding=UTF-8
@ECHO.java.util.logging.FileHandler.limit=52428800
@ECHO.java.util.logging.FileHandler.count=9
@ECHO.java.util.logging.FileHandler.append=true
@ECHO.
@ECHO.# Log everything ...
@ECHO..level= ALL
@ECHO.# ... except unwanted FINEST level messages
@ECHO.hudson.util.ProcessTree.level=FINER
@ECHO.org.jenkinsci.remoting.protocol.level=FINER
@ECHO.# ... or unwanted FINER level messages
@ECHO.hudson.remoting.FileSystemJarCache.level=FINE
@ECHO.hudson.remoting.PipeWindow.level=FINE
@ECHO.hudson.remoting.RemoteClassLoader.level=FINE
@ECHO.# ... or unwanted FINE level messages
@ECHO.hudson.remoting.Channel.level=CONFIG
@ECHO.hudson.remoting.ResourceImageDirect.level=CONFIG
@ECHO.org.apache.commons.digester3.level=CONFIG
@ECHO.org.apache.commons.beanutils.level=CONFIG
@ECHO.org.apache.http.client.protocol.level=CONFIG
@ECHO.org.apache.http.impl.level=CONFIG
@ECHO.
@GOTO :eof

::***************************************************************************************
:logMessage
:: Logs a message, rotating the logfile as required.
:: Has hard-coded log size (10meg) and number (9).
::
:: %SCRIPT_LOGFILE% = logfile to log to, or empty to just log to stdout.
:: %1 = log level, e.g. INFO, WARNING etc.
:: %2 = log message
@IF NOT "%SCRIPT_LOGFILE%"=="" @CALL :ensureLogExistsAndIsRotated "%SCRIPT_LOGFILE%" 9 10485760
@CALL :writeToLog "%SCRIPT_LOGFILE%" "%~1" "%~2"
@GOTO :eof

:logBlankLine
:: As :logMessage, but logs a blank line.
::
:: %SCRIPT_LOGFILE% = logfile to log to, or empty to just log to stdout.
@IF NOT "%SCRIPT_LOGFILE%"=="" @CALL :ensureLogExistsAndIsRotated "%SCRIPT_LOGFILE%" 9 10485760
@CALL :writeBlankLineToLog "%SCRIPT_LOGFILE%"
@GOTO :eof

:ensureLogExistsAndIsRotated
:: Ensures that the logfile exists,
:: and rotates the logfiles if the logfile is too large.
::
:: %1 = logfile to log to, or empty for just logging to stdout
:: %2 = number of rotated logs to keep
:: %3 = maximum size of one logfile in bytes
@IF NOT EXIST "%~1" @(
    @IF NOT EXIST "%~dp1" @MKDIR "%~dp1" || @EXIT /b 1
    @COPY NUL "%~1" >NUL || @EXIT /b 1
)
@IF %~z1 GEQ %~3 @(
    @PUSHD "%~dp1"
    @CALL :rotateLogs "%~nx1" %2
    @POPD
)
@GOTO :eof

:writeToLog
:: Logs a message with a timestamp to the console and optionally to a file as well.
::
:: %1 = logfile to log to, or empty for just logging to stdout
:: %2 = log level, e.g. INFO, WARNING etc.
:: %3 = log message
::
:: Get time as
::   YYYYMMDDhhmmss.ssssss+zzz
:: where .ssssss is microseconds and zzz is offset in minutes from GMT.
@FOR /F %%A IN ('WMIC OS GET LocalDateTime ^| FINDSTR \.') DO @SET writeToLog_LDT=%%A
:: So:
::   YYYY is offset 0 length 4
::   MM is offset 4 length 2
::   DD is offset 6 length 2
::   hh is offset 8 length 2
::   mm is offset 10 length 2
::   ss is offset 12 length 2
::   milliseconds is offset 15 length 3
::   + or - is offset 21 length 1
::   zzz is offset 22 length 3
:: We want
::   YYYY-MM-DD'T'hh:mm:ss.sss+ZZzz
:: where sss is milliseconds and ZZzz is hhmm offset from GMT.
:: So first we work out ZZzz
@SET writeToLog_LDTzzz=%writeToLog_LDT:~22,3%
:: Strip off leading zeros from zzz otherwise SET will think it is octal
@IF "%writeToLog_LDTzzz:~0,1%"=="0" @SET writeToLog_LDTzzz=%writeToLog_LDTzzz:~1,2%
@IF "%writeToLog_LDTzzz:~0,1%"=="0" @SET writeToLog_LDTzzz=%writeToLog_LDTzzz:~1,1%
:: Split zzz into hours and minutes, albeit unpadded
@SET /A writeToLog_tsTzmm="writeToLog_LDTzzz %% 60"
@SET /A writeToLog_tsTzhh="writeToLog_LDTzzz / 60"
:: Pad hh and mm with leading zero if they need it
@IF %writeToLog_tsTzmm% LSS 10 @SET writeToLog_tsTzmm=0%writeToLog_tsTzmm%
@IF %writeToLog_tsTzhh% LSS 10 @SET writeToLog_tsTzhh=0%writeToLog_tsTzhh%
:: Now construct the full timestamp
@SET writeToLog_tsPlusOrMinusZZzz=%writeToLog_LDT:~21,1%%writeToLog_tsTzhh%%writeToLog_tsTzmm%
@SET writeToLog_tsYYYYMMDD=%writeToLog_LDT:~0,4%-%writeToLog_LDT:~4,2%-%writeToLog_LDT:~6,2%
@SET writeToLog_tshhmmssmilliseconds=%writeToLog_LDT:~8,2%:%writeToLog_LDT:~10,2%:%writeToLog_LDT:~12,2%.%writeToLog_LDT:~15,3%
@SET writeToLog_ts=%writeToLog_tsYYYYMMDD%T%writeToLog_tshhmmssmilliseconds%%writeToLog_tsPlusOrMinusZZzz%
@ECHO.%writeToLog_ts% %~2 %~3
@IF NOT "%~1"=="" @ECHO.%writeToLog_ts% %~2 %~3>>%~1
@GOTO :eof

:writeBlankLineToLog
:: Logs a blank line to the console and optionally to a file as well.
::
:: %1 = logfile to log to, or empty for just logging to stdout
@ECHO.
@IF NOT "%~1"=="" @ECHO.>>%~1
@GOTO :eof

:rotateLogs
:: Does a "rotate" on a set of logfiles.  i.e. log foo.log becomes foo.1.log, but foo.1.log becomes foo.2.log etc, up to a maxiumum.
:: If there are "gaps" in the numbers then we do not rotate any more than necessary.
:: Note: This assumes that the logfiles are in the current directory.
::
:: %1 = base name of the file to "rotate"
:: %2 = maximum number to keep
@CALL :rotateLog "%~1" 0 %2
@GOTO :eof

:rotateLog
:: When we process the first one, we always rotate on the assumption that we are about to be overwritten if we do not.
:: When we process the rest, we stop if we find a gap.
:: If we "process" the last one, we delete that "max" one.
:: %1 = base name of the file to "rotate"
:: %2 = number we are processing here
:: %3 = max we should go up to
@CALL :getRotatedLogfileName "%~1" %2 rotateLog_currentName
@IF NOT EXIST "%rotateLog_currentName%" @GOTO :eof
@IF "%2"=="%3" (
    @DEL "%rotateLog_currentName%"
    @GOTO :eof
)
@SET rotateLog_currentNumber=%2
@SET /A rotateLog_prevNumber=rotateLog_currentNumber-1
@CALL :getRotatedLogfileName "%~1" %rotateLog_prevNumber% rotateLog_prevName
@IF NOT "%rotateLog_prevName%"=="" (
    @IF NOT EXIST "%rotateLog_prevName%" @(
        GOTO :eof
    )
)
@SET /A rotateLog_nextNumber=rotateLog_currentNumber+1
@CALL :rotateLog "%~1" %rotateLog_nextNumber% %3
::That will have overwritten our variables, so re-set them.
@SET rotateLog_currentNumber=%2
@SET /A rotateLog_nextNumber=rotateLog_currentNumber+1
@CALL :getRotatedLogfileName "%~1" %rotateLog_currentNumber% rotateLog_currentName
@CALL :getRotatedLogfileName "%~1" %rotateLog_nextNumber% rotateLog_nextName
@REN "%rotateLog_currentName%" "%rotateLog_nextName%"
@GOTO :eof

:getRotatedLogfileName
:: %1 = base name of the file to "rotate", e.g. foo.log
:: %2 = number we are processing here
:: %3 = name of the variable to be set to the logfile name
@IF "%2"=="0" @(
    @SET %3=%~1
) ELSE @(
    @IF "%2"=="-1" @(
        @SET %3=
    ) ELSE @(
        @SET %3=%~n1.%2%~x1
    )
)
@GOTO :eof

::***************************************************************************************
:: Create the Jenkins home folder if it does not exist.
:: If we have been told where to get slave.jar from then we download it.
:: If not, and the Jenkins home folder does not already contain a slave jar, then fail.
:: Finally, we run the Jenkins JNLP start-up command.
:: We only return once the slave has exited, i.e. crashed, died, disconnected etc.
:: If we managed to run the slave and it exited with code zero then we return 0.
:: If we ran the slave and it exited with a non-zero code then we return 1.
:: If we did not manage to start it, we return 1.
::
:runSlave
@IF NOT EXIST "%JAVA_HOME%" @(
    @CALL :logMessage "ERROR" "JAVA_HOME folder %JAVA_HOME% does not exist."
    @EXIT /b 1
)
@IF EXIST "%SLAVE_HOME%" @(
    @CALL :logMessage "FINER" "... %SLAVE_HOME% already exists."
) ELSE @(
    @CALL :logMessage "INFO" "... creating folder %SLAVE_HOME% ..."
    @MD "%SLAVE_HOME%" || EXIT /b 1
)
@CALL :createLoggingConfigFile "%SCRIPT_FOLDER%%SCRIPT_NAME%" "%SLAVE_HOME%\jenkins-slave.logging.properties" || EXIT /b 1
@IF NOT "%SLAVE_JAR_URL%"=="" @(
    @CALL :logMessage "INFO" "... Downloading slave.jar from %SLAVE_JAR_URL%"
    @IF EXIST "%SLAVE_HOME%\slave.jar" @(
        @DEL "%SLAVE_HOME%\slave.jar" >NUL 2>&1
    )
    @powershell -Command "[System.Net.ServicePointManager]::SecurityProtocol = [System.Enum]::GetValues([System.Net.SecurityProtocolType]); (new-object System.Net.WebClient).DownloadFile('%SLAVE_JAR_URL%','%SLAVE_HOME%\slave.jar')"
    @IF ERRORLEVEL 1 @(
        @CALL :logMessage "ERROR" "... Download of %SLAVE_JAR_URL% failed."
        @DEL "%SLAVE_HOME%\slave.jar" >NUL 2>&1
    )
)
@IF NOT EXIST "%SLAVE_HOME%\slave.jar" @(
    @CALL :logMessage "ERROR" "We need slave JAR file %SLAVE_HOME%\slave.jar to proceed."
    @EXIT /b 1
)
@CALL :logMessage "INFO" "PUSHD %SLAVE_HOME%"
@PUSHD "%SLAVE_HOME%" || EXIT /b 1
@SET _cmdLine=%JAVA_HOME%bin\java -Djava.util.logging.config.file=jenkins-slave.logging.properties %SLAVE_JVM_ARGS% -jar slave.jar -jnlpUrl %SLAVE_JNLP_URL% %SLAVE_PARAMS%
@CALL :logMessage "INFO" "%_cmdLine%"
@%_cmdLine%
@CALL :logMessage "INFO" "... slave has exited with code %ERRORLEVEL%"
@IF NOT ERRORLEVEL 1 @(
    @POPD
    @EXIT /b 0
) ELSE @(
    @POPD
    @EXIT /b 1
)
@GOTO :eof

::***************************************************************************************
:: Output a message and wait for the given number of seconds.
:wait
@SET seconds=%1
@SET msg=%~2
@SET /A secondsPlusOne=seconds+1
@CALL :logMessage "FINER" "Waiting %seconds% seconds %msg%..."
@ping -n %secondsPlusOne% -4 127.0.0.1 >NUL
@CALL :logMessage "FINER" "...Wait is over."
@GOTO :eof
