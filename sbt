#!/usr/bin/env bash
#
# A more capable sbt runner, coincidentally also called sbt.
# Author: Paul Phillips <paulp@improving.org>
# https://github.com/paulp/sbt-extras
#
# Generated from http://www.opensource.org/licenses/bsd-license.php
# Copyright (c) 2011, Paul Phillips. All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are
# met:
#
#     * Redistributions of source code must retain the above copyright
# notice, this list of conditions and the following disclaimer.
#     * Redistributions in binary form must reproduce the above copyright
# notice, this list of conditions and the following disclaimer in the
# documentation and/or other materials provided with the distribution.
#     * Neither the name of the author nor the names of its contributors
# may be used to endorse or promote products derived from this software
# without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
# "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
# LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
# A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
# HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
# SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
# TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
# PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
# LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
# NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
# SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

set -o pipefail

declare -r sbt_release_version="1.11.3"
declare -r sbt_unreleased_version="1.11.3"

declare -r latest_213="2.13.16"
declare -r latest_212="2.12.20"
declare -r latest_211="2.11.12"
declare -r latest_210="2.10.7"
declare -r latest_29="2.9.3"
declare -r latest_28="2.8.2"

declare -r buildProps="project/build.properties"

declare -r sbt_launch_ivy_release_repo="https://repo.typesafe.com/typesafe/ivy-releases"
declare -r sbt_launch_ivy_snapshot_repo="https://repo.scala-sbt.org/scalasbt/ivy-snapshots"
declare -r sbt_launch_mvn_release_repo="https://repo1.maven.org/maven2"
declare -r sbt_launch_mvn_snapshot_repo="https://repo.scala-sbt.org/scalasbt/maven-snapshots"

declare -r default_jvm_opts_common="-Xms512m -Xss2m -XX:MaxInlineLevel=18"
declare -r noshare_opts="-Dsbt.global.base=project/.sbtboot -Dsbt.boot.directory=project/.boot -Dsbt.ivy.home=project/.ivy -Dsbt.coursier.home=project/.coursier"

declare sbt_jar sbt_dir sbt_create sbt_version sbt_script sbt_new
declare sbt_explicit_version
declare verbose noshare batch trace_level

declare java_cmd="java"
declare sbt_launch_dir="$HOME/.sbt/launchers"
declare sbt_launch_repo

# pull -J and -D options to give to java.
declare -a java_args scalac_args sbt_commands residual_args

# args to jvm/sbt via files or environment variables
declare -a extra_jvm_opts extra_sbt_opts

echoerr() { echo >&2 "$@"; }
vlog()    { [[ -n "$verbose" ]] && echoerr "$@"; }
die()     {
  echo "Aborting: $*"
  exit 1
}

setTrapExit() {
  # save stty and trap exit, to ensure echo is re-enabled if we are interrupted.
  SBT_STTY="$(stty -g 2>/dev/null)"
  export SBT_STTY

  # restore stty settings (echo in particular)
  onSbtRunnerExit() {
    [ -t 0 ] || return
    vlog ""
    vlog "restoring stty: $SBT_STTY"
    stty "$SBT_STTY"
  }

  vlog "saving stty: $SBT_STTY"
  trap onSbtRunnerExit EXIT
}

# this seems to cover the bases on OSX, and someone will
# have to tell me about the others.
get_script_path() {
  local path="$1"
  [[ -L "$path" ]] || {
    echo "$path"
    return
  }

  local -r target="$(readlink "$path")"
  if [[ "${target:0:1}" == "/" ]]; then
    echo "$target"
  else
    echo "${path%/*}/$target"
  fi
}

script_path="$(get_script_path "${BASH_SOURCE[0]}")"
declare -r script_path
script_name="${script_path##*/}"
declare -r script_name

init_default_option_file() {
  local overriding_var="${!1}"
  local default_file="$2"
  if [[ ! -r "$default_file" && "$overriding_var" =~ ^@(.*)$ ]]; then
    local envvar_file="${BASH_REMATCH[1]}"
    if [[ -r "$envvar_file" ]]; then
      default_file="$envvar_file"
    fi
  fi
  echo "$default_file"
}

sbt_opts_file="$(init_default_option_file SBT_OPTS .sbtopts)"
sbtx_opts_file="$(init_default_option_file SBTX_OPTS .sbtxopts)"
jvm_opts_file="$(init_default_option_file JVM_OPTS .jvmopts)"

build_props_sbt() {
  [[ -r "$buildProps" ]] &&
    grep '^sbt\.version' "$buildProps" | tr '=\r' ' ' | awk '{ print $2; }'
}

set_sbt_version() {
  sbt_version="${sbt_explicit_version:-$(build_props_sbt)}"
  [[ -n "$sbt_version" ]] || sbt_version=$sbt_release_version
  export sbt_version
}

url_base() {
  local version="$1"

  case "$version" in
    0.7.*)     echo "https://storage.googleapis.com/google-code-archive-downloads/v2/code.google.com/simple-build-tool" ;;
    0.10.*)    echo "$sbt_launch_ivy_release_repo" ;;
    0.11.[12]) echo "$sbt_launch_ivy_release_repo" ;;
    0.*-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]) # ie "*-yyyymmdd-hhMMss"
      echo          "$sbt_launch_ivy_snapshot_repo" ;;
    0.*)       echo "$sbt_launch_ivy_release_repo" ;;
    *-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]) # ie "*-yyyymmddThhMMss"
      echo          "$sbt_launch_mvn_snapshot_repo" ;;
    *)         echo "$sbt_launch_mvn_release_repo" ;;
  esac
}

make_url() {
  local version="$1"

  local base="${sbt_launch_repo:-$(url_base "$version")}"

  case "$version" in
    0.7.*)     echo "$base/sbt-launch-0.7.7.jar" ;;
    0.10.*)    echo "$base/org.scala-tools.sbt/sbt-launch/$version/sbt-launch.jar" ;;
    0.11.[12]) echo "$base/org.scala-tools.sbt/sbt-launch/$version/sbt-launch.jar" ;;
    0.*)       echo "$base/org.scala-sbt/sbt-launch/$version/sbt-launch.jar" ;;
    *)         echo "$base/org/scala-sbt/sbt-launch/$version/sbt-launch-${version}.jar" ;;
  esac
}

addJava()      {
  vlog "[addJava] arg = '$1'"
  java_args+=("$1")
}
addSbt()       {
  vlog "[addSbt] arg = '$1'"
  sbt_commands+=("$1")
}
addScalac()    {
  vlog "[addScalac] arg = '$1'"
  scalac_args+=("$1")
}
addResidual()  {
  vlog "[residual] arg = '$1'"
  residual_args+=("$1")
}

addResolver() { addSbt "set resolvers += $1"; }

addDebugger() { addJava "-Xdebug" && addJava "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=$1"; }

setThisBuild() {
  vlog "[addBuild] args = '$*'"
  local key="$1" && shift
  addSbt "set $key in ThisBuild := $*"
}
setScalaVersion() {
  [[ "$1" == *"-SNAPSHOT" ]] && addResolver 'Resolver.sonatypeRepo("snapshots")'
  addSbt "++ $1"
}
setJavaHome() {
  java_cmd="$1/bin/java"
  setThisBuild javaHome "_root_.scala.Some(file(\"$1\"))"
  export JAVA_HOME="$1"
  export JDK_HOME="$1"
  export PATH="$JAVA_HOME/bin:$PATH"
}

getJavaVersion() {
  local -r str=$("$1" -version 2>&1 | grep -E -e '(java|openjdk) version' | awk '{ print $3 }' | tr -d '"')

  # java -version on java8 says 1.8.x
  # but on 9 and 10 it's 9.x.y and 10.x.y.
  if [[ "$str" =~ ^1\.([0-9]+)(\..*)?$ ]]; then
    echo "${BASH_REMATCH[1]}"
  # Fixes https://github.com/dwijnand/sbt-extras/issues/326
  elif [[ "$str" =~ ^([0-9]+)(\..*)?(-ea)?$ ]]; then
    echo "${BASH_REMATCH[1]}"
  elif [[ -n "$str" ]]; then
    echoerr "Can't parse java version from: $str"
  fi
}

checkJava() {
  # Warn if there is a Java version mismatch between PATH and JAVA_HOME/JDK_HOME

  [[ -n "$JAVA_HOME" && -e "$JAVA_HOME/bin/java"    ]] && java="$JAVA_HOME/bin/java"
  [[ -n "$JDK_HOME" && -e "$JDK_HOME/lib/tools.jar" ]] && java="$JDK_HOME/bin/java"

  if [[ -n "$java" ]]; then
    pathJavaVersion=$(getJavaVersion java)
    homeJavaVersion=$(getJavaVersion "$java")
    if [[ "$pathJavaVersion" != "$homeJavaVersion" ]]; then
      echoerr "Warning: Java version mismatch between PATH and JAVA_HOME/JDK_HOME, sbt will use the one in PATH"
      echoerr "  Either: fix your PATH, remove JAVA_HOME/JDK_HOME or use -java-home"
      echoerr "  java version from PATH:               $pathJavaVersion"
      echoerr "  java version from JAVA_HOME/JDK_HOME: $homeJavaVersion"
    fi
  fi
}

java_version() {
  local -r version=$(getJavaVersion "$java_cmd")
  vlog "Detected Java version: $version"
  echo "$version"
}

is_apple_silicon() { [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]]; }

# MaxPermSize critical on pre-8 JVMs but incurs noisy warning on 8+
default_jvm_opts() {
  local -r v="$(java_version)"
  if [[ $v -ge 17 ]]; then
    echo "$default_jvm_opts_common"
  elif [[ $v -ge 10 ]]; then
    if is_apple_silicon; then
      # As of Dec 2020, JVM for Apple Silicon (M1) doesn't support JVMCI
      echo "$default_jvm_opts_common"
    else
      echo "$default_jvm_opts_common -XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler"
    fi
  elif [[ $v -ge 8 ]]; then
    echo "$default_jvm_opts_common"
  else
    echo "-XX:MaxPermSize=384m $default_jvm_opts_common"
  fi
}

execRunner() {
  # print the arguments one to a line, quoting any containing spaces
  vlog "# Executing command line:" && {
    for arg; do
      if [[ -n "$arg" ]]; then
        if printf "%s\n" "$arg" | grep -q ' '; then
          printf >&2 "\"%s\"\n" "$arg"
        else
          printf >&2 "%s\n" "$arg"
        fi
      fi
    done
    vlog ""
  }

  setTrapExit

  if [[ -n "$batch" ]]; then
    "$@" </dev/null
  else
    "$@"
  fi
}

jar_url() { make_url "$1"; }

is_cygwin() { [[ "$(uname -a)" == "CYGWIN"* ]]; }

jar_file() {
  is_cygwin &&
    cygpath -w "$sbt_launch_dir/$1/sbt-launch.jar" ||
    echo "$sbt_launch_dir/$1/sbt-launch.jar"
}

download_url() {
  local url="$1"
  local jar="$2"

  mkdir -p "${jar%/*}" && {
    if command -v curl >/dev/null 2>&1; then
      curl --fail --silent --location "$url" --output "$jar"
    elif command -v wget >/dev/null 2>&1; then
      wget -q -O "$jar" "$url"
    fi
  } && [[ -r "$jar" ]]
}

acquire_sbt_jar() {
  {
    sbt_jar="$(jar_file "$sbt_version")"
    [[ -r "$sbt_jar" ]]
  } || {
    sbt_jar="$HOME/.ivy2/local/org.scala-sbt/sbt-launch/$sbt_version/jars/sbt-launch.jar"
    [[ -r "$sbt_jar" ]]
  } || {
    sbt_jar="$(jar_file "$sbt_version")"
    jar_url="$(make_url "$sbt_version")"

    echoerr "Downloading sbt launcher for ${sbt_version}:"
    echoerr "  From  ${jar_url}"
    echoerr "    To  ${sbt_jar}"

    download_url "${jar_url}" "${sbt_jar}"

    case "${sbt_version}" in
      0.*)
        vlog "SBT versions < 1.0 do not have published MD5 checksums, skipping check"
        echo ""
        ;;
      *)   verify_sbt_jar "${sbt_jar}" ;;
    esac
  }
}

verify_sbt_jar() {
  local jar="${1}"
  local md5="${jar}.md5"
  md5url="$(make_url "${sbt_version}").md5"

  echoerr "Downloading sbt launcher ${sbt_version} md5 hash:"
  echoerr "  From  ${md5url}"
  echoerr "    To  ${md5}"

  download_url "${md5url}" "${md5}" >/dev/null 2>&1

  if command -v md5sum >/dev/null 2>&1; then
    if echo "$(cat "${md5}")  ${jar}" | md5sum -c -; then
      rm -rf "${md5}"
      return 0
    else
      echoerr "Checksum does not match"
      return 1
    fi
  elif command -v md5 >/dev/null 2>&1; then
    if [ "$(md5 -q "${jar}")" == "$(cat "${md5}")" ]; then
      rm -rf "${md5}"
      return 0
    else
      echoerr "Checksum does not match"
      return 1
    fi
  elif command -v openssl >/dev/null 2>&1; then
    if [ "$(openssl md5 -r "${jar}" | awk '{print $1}')" == "$(cat "${md5}")" ]; then
      rm -rf "${md5}"
      return 0
    else
      echoerr "Checksum does not match"
      return 1
    fi
  else
    echoerr "Could not find an MD5 command"
    return 1
  fi
}

usage() {
  set_sbt_version
  cat <<EOM
Usage: $script_name [options]

Note that options which are passed along to sbt begin with -- whereas
options to this runner use a single dash. Any sbt command can be scheduled
to run first by prefixing the command with --, so --warn, --error and so on
are not special.

  -h | -help         print this message
  -v                 verbose operation (this runner is chattier)
  -d, -w, -q         aliases for --debug, --warn, --error (q means quiet)
  -x                 debug this script
  -trace <level>     display stack traces with a max of <level> frames (default: -1, traces suppressed)
  -debug-inc         enable debugging log for the incremental compiler
  -no-colors         disable ANSI color codes
  -sbt-create        start sbt even if current directory contains no sbt project
  -sbt-dir   <path>  path to global settings/plugins directory (default: ~/.sbt/<version>)
  -sbt-boot  <path>  path to shared boot directory (default: ~/.sbt/boot in 0.11+)
  -ivy       <path>  path to local Ivy repository (default: ~/.ivy2)
  -no-share          use all local caches; no sharing
  -offline           put sbt in offline mode
  -jvm-debug <port>  Turn on JVM debugging, open at the given port.
  -batch             Disable interactive mode
  -prompt <expr>     Set the sbt prompt; in expr, 's' is the State and 'e' is Extracted
  -script <file>     Run the specified file as a scala script

  # sbt version (default: sbt.version from $buildProps if present, otherwise $sbt_release_version)
  -sbt-version <version>  use the specified version of sbt (default: $sbt_release_version)
  -sbt-force-latest       force the use of the latest release of sbt: $sbt_release_version
  -sbt-dev                use the latest pre-release version of sbt: $sbt_unreleased_version
  -sbt-jar      <path>    use the specified jar as the sbt launcher
  -sbt-launch-dir <path>  directory to hold sbt launchers (default: $sbt_launch_dir)
  -sbt-launch-repo <url>  repo url for downloading sbt launcher jar (default: $(url_base "$sbt_version"))

  # scala version (default: as chosen by sbt)
  -28                        use $latest_28
  -29                        use $latest_29
  -210                       use $latest_210
  -211                       use $latest_211
  -212                       use $latest_212
  -213                       use $latest_213
  -scala-home <path>         use the scala build at the specified directory
  -scala-version <version>   use the specified version of scala
  -binary-version <version>  use the specified scala version when searching for dependencies

  # java version (default: java from PATH, currently $(java -version 2>&1 | grep version))
  -java-home <path>          alternate JAVA_HOME

  # passing options to the jvm - note it does NOT use JAVA_OPTS due to pollution
  # The default set is used if JVM_OPTS is unset and no -jvm-opts file is found
  <default>         $(default_jvm_opts)
  JVM_OPTS          environment variable holding either the jvm args directly, or
                    the reference to a file containing jvm args if given path is prepended by '@' (e.g. '@/etc/jvmopts')
                    Note: "@"-file is overridden by local '.jvmopts' or '-jvm-opts' argument.
  -jvm-opts <path>  file containing jvm args (if not given, .jvmopts in project root is used if present)
  -Dkey=val         pass -Dkey=val directly to the jvm
  -J-X              pass option -X directly to the jvm (-J is stripped)

  # passing options to sbt, OR to this runner
  SBT_OPTS          environment variable holding either the sbt args directly, or
                    the reference to a file containing sbt args if given path is prepended by '@' (e.g. '@/etc/sbtopts')
                    Note: "@"-file is overridden by local '.sbtopts' or '-sbt-opts' argument.
  -sbt-opts <path>  file containing sbt args (if not given, .sbtopts in project root is used if present)
  -S-X              add -X to sbt's scalacOptions (-S is stripped)

  # passing options exclusively to this runner
  SBTX_OPTS         environment variable holding either the sbt-extras args directly, or
                    the reference to a file containing sbt-extras args if given path is prepended by '@' (e.g. '@/etc/sbtxopts')
                    Note: "@"-file is overridden by local '.sbtxopts' or '-sbtx-opts' argument.
  -sbtx-opts <path> file containing sbt-extras args (if not given, .sbtxopts in project root is used if present)
EOM
  exit 0
}

process_args() {
  require_arg() {
    local type="$1"
    local opt="$2"
    local arg="$3"

    if [[ -z "$arg" ]] || [[ "${arg:0:1}" == "-" ]]; then
      die "$opt requires <$type> argument"
    fi
  }
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h |   -help) usage ;;
      -v)           verbose=true && shift ;;
      -d)           addSbt "--debug" && shift ;;
      -w)           addSbt "--warn"  && shift ;;
      -q)           addSbt "--error" && shift ;;
      -x)           shift ;; # currently unused
      -trace)       require_arg integer "$1" "$2" && trace_level="$2" && shift 2 ;;
      -debug-inc)   addJava "-Dxsbt.inc.debug=true" && shift ;;

      -no-colors)   addJava "-Dsbt.log.noformat=true" && addJava "-Dsbt.color=false" && shift ;;
      -sbt-create)  sbt_create=true && shift ;;
      -sbt-dir)     require_arg path "$1" "$2" && sbt_dir="$2" && shift 2 ;;
      -sbt-boot)    require_arg path "$1" "$2" && addJava "-Dsbt.boot.directory=$2" && shift 2 ;;
      -ivy)         require_arg path "$1" "$2" && addJava "-Dsbt.ivy.home=$2" && shift 2 ;;
      -no-share)    noshare=true && shift ;;
      -offline)     addSbt "set offline in Global := true" && shift ;;
      -jvm-debug)   require_arg port "$1" "$2" && addDebugger "$2" && shift 2 ;;
      -batch)       batch=true && shift ;;
      -prompt)      require_arg "expr" "$1" "$2" && setThisBuild shellPrompt "(s => { val e = Project.extract(s) ; $2 })" && shift 2 ;;
      -script)      require_arg file "$1" "$2" && sbt_script="$2" && addJava "-Dsbt.main.class=sbt.ScriptMain" && shift 2 ;;

      -sbt-version) require_arg version "$1" "$2" && sbt_explicit_version="$2" && shift 2 ;;
      -sbt-force-latest) sbt_explicit_version="$sbt_release_version" && shift ;;
      -sbt-dev)     sbt_explicit_version="$sbt_unreleased_version" && shift ;;
      -sbt-jar)     require_arg path "$1" "$2" && sbt_jar="$2" && shift 2 ;;
      -sbt-launch-dir) require_arg path "$1" "$2" && sbt_launch_dir="$2" && shift 2 ;;
      -sbt-launch-repo) require_arg path "$1" "$2" && sbt_launch_repo="$2" && shift 2 ;;

      -28)          setScalaVersion "$latest_28"  && shift ;;
      -29)          setScalaVersion "$latest_29"  && shift ;;
      -210)         setScalaVersion "$latest_210" && shift ;;
      -211)         setScalaVersion "$latest_211" && shift ;;
      -212)         setScalaVersion "$latest_212" && shift ;;
      -213)         setScalaVersion "$latest_213" && shift ;;

      -scala-version) require_arg version "$1" "$2" && setScalaVersion "$2" && shift 2 ;;
      -binary-version) require_arg version "$1" "$2" && setThisBuild scalaBinaryVersion "\"$2\"" && shift 2 ;;
      -scala-home)  require_arg path "$1" "$2" && setThisBuild scalaHome "_root_.scala.Some(file(\"$2\"))" && shift 2 ;;
      -java-home)   require_arg path "$1" "$2" && setJavaHome "$2" && shift 2 ;;
      -sbt-opts)    require_arg path "$1" "$2" && sbt_opts_file="$2" && shift 2 ;;
      -sbtx-opts) require_arg path "$1" "$2" && sbtx_opts_file="$2" && shift 2 ;;
      -jvm-opts)    require_arg path "$1" "$2" && jvm_opts_file="$2" && shift 2 ;;

      -D*)          addJava "$1" && shift ;;
      -J*)          addJava "${1:2}" && shift ;;
      -S*)          addScalac "${1:2}" && shift ;;

      new)          sbt_new=true && : "${sbt_explicit_version:=$sbt_release_version}" && addResidual "$1" && shift ;;

      *)            addResidual "$1" && shift ;;
    esac
  done
}

# process the direct command line arguments
process_args "$@"

# skip #-styled comments and blank lines
readConfigFile() {
  local end=false
  until $end; do
    read -r || end=true
    [[ $REPLY =~ ^# ]] || [[ -z $REPLY ]] || echo "$REPLY"
  done <"$1"
}

# if there are file/environment sbt_opts, process again so we
# can supply args to this runner
if [[ -r "$sbt_opts_file" ]]; then
  vlog "Using sbt options defined in file $sbt_opts_file"
  while read -r opt; do extra_sbt_opts+=("$opt"); done < <(readConfigFile "$sbt_opts_file")
elif [[ -n "$SBT_OPTS" && ! ("$SBT_OPTS" =~ ^@.*) ]]; then
  vlog "Using sbt options defined in variable \$SBT_OPTS"
  IFS=" " read -r -a extra_sbt_opts <<<"$SBT_OPTS"
else
  vlog "No extra sbt options have been defined"
fi

# if there are file/environment sbtx_opts, process again so we
# can supply args to this runner
if [[ -r "$sbtx_opts_file" ]]; then
  vlog "Using sbt options defined in file $sbtx_opts_file"
  while read -r opt; do extra_sbt_opts+=("$opt"); done < <(readConfigFile "$sbtx_opts_file")
elif [[ -n "$SBTX_OPTS" && ! ("$SBTX_OPTS" =~ ^@.*) ]]; then
  vlog "Using sbt options defined in variable \$SBTX_OPTS"
  IFS=" " read -r -a extra_sbt_opts <<<"$SBTX_OPTS"
else
  vlog "No extra sbt options have been defined"
fi

[[ -n "${extra_sbt_opts[*]}" ]] && process_args "${extra_sbt_opts[@]}"

# reset "$@" to the residual args
set -- "${residual_args[@]}"
argumentCount=$#

# set sbt version
set_sbt_version

checkJava

# only exists in 0.12+
setTraceLevel() {
  case "$sbt_version" in
    "0.7."* | "0.10."* | "0.11."*) echoerr "Cannot set trace level in sbt version $sbt_version" ;;
    *)                             setThisBuild traceLevel "$trace_level" ;;
  esac
}

# set scalacOptions if we were given any -S opts
[[ ${#scalac_args[@]} -eq 0 ]] || addSbt "set scalacOptions in ThisBuild += \"${scalac_args[*]}\""

[[ -n "$sbt_explicit_version" && -z "$sbt_new" ]] && addJava "-Dsbt.version=$sbt_explicit_version"
vlog "Detected sbt version $sbt_version"

if [[ -n "$sbt_script" ]]; then
  residual_args=("$sbt_script" "${residual_args[@]}")
else
  # no args - alert them there's stuff in here
  ((argumentCount > 0)) || {
    vlog "Starting $script_name: invoke with -help for other options"
    residual_args=(shell)
  }
fi

# verify this is an sbt dir, -create was given or user attempts to run a scala script
[[ -r ./build.sbt || -d ./project || -n "$sbt_create" || -n "$sbt_script" || -n "$sbt_new" ]] || {
  cat <<EOM
$(pwd) doesn't appear to be an sbt project.
If you want to start sbt anyway, run:
  $0 -sbt-create

EOM
  exit 1
}

# pick up completion if present; todo
# shellcheck disable=SC1091
[[ -r .sbt_completion.sh ]] && source .sbt_completion.sh

# directory to store sbt launchers
[[ -d "$sbt_launch_dir" ]] || mkdir -p "$sbt_launch_dir"
[[ -w "$sbt_launch_dir" ]] || sbt_launch_dir="$(mktemp -d -t sbt_extras_launchers.XXXXXX)"

# no jar? download it.
[[ -r "$sbt_jar" ]] || acquire_sbt_jar || {
  # still no jar? uh-oh.
  echo "Could not download and verify the launcher. Obtain the jar manually and place it at $sbt_jar"
  exit 1
}

if [[ -n "$noshare" ]]; then
  for opt in ${noshare_opts}; do
    addJava "$opt"
  done
else
  case "$sbt_version" in
    "0.7."* | "0.10."* | "0.11."* | "0.12."*)
      [[ -n "$sbt_dir" ]] || {
        sbt_dir="$HOME/.sbt/$sbt_version"
        vlog "Using $sbt_dir as sbt dir, -sbt-dir to override."
      }
      ;;
  esac

  if [[ -n "$sbt_dir" ]]; then
    addJava "-Dsbt.global.base=$sbt_dir"
  fi
fi

if [[ -r "$jvm_opts_file" ]]; then
  vlog "Using jvm options defined in file $jvm_opts_file"
  while read -r opt; do extra_jvm_opts+=("$opt"); done < <(readConfigFile "$jvm_opts_file")
elif [[ -n "$JVM_OPTS" && ! ("$JVM_OPTS" =~ ^@.*) ]]; then
  vlog "Using jvm options defined in \$JVM_OPTS variable"
  IFS=" " read -r -a extra_jvm_opts <<<"$JVM_OPTS"
else
  vlog "Using default jvm options"
  IFS=" " read -r -a extra_jvm_opts <<<"$( default_jvm_opts)"
fi

# traceLevel is 0.12+
[[ -n "$trace_level" ]] && setTraceLevel

execRunner "$java_cmd" \
  "${extra_jvm_opts[@]}" \
  "${java_args[@]}" \
  -jar "$sbt_jar" \
  "${sbt_commands[@]}" \
  "${residual_args[@]}"
