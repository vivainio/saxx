"""Simple, fast and fun task runner, not unlike gulp / grunt (but zero dep)"""

import os
import shutil
import subprocess
import sys
import textwrap


# === TASKS (add your do_ functions here) ===


SAXON_VERSION = "12.9.1"
SAXON_JAR = f"Saxon-HE-fork-{SAXON_VERSION}.jar"
SAXON_URL = f"https://github.com/vivainio/Saxon-HE-fork/releases/download/v{SAXON_VERSION}/{SAXON_JAR}"


def do_deps(args) -> None:
    """Download Saxon-HE fork from GitHub releases and install to local Maven repo"""
    import urllib.request

    jar_path = f"target/{SAXON_JAR}"
    os.makedirs("target", exist_ok=True)

    if not os.path.exists(jar_path):
        emit(f"Downloading {SAXON_URL}")
        urllib.request.urlretrieve(SAXON_URL, jar_path)
    else:
        emit(f"Already downloaded: {jar_path}")

    c(f"mvn install:install-file -Dfile={jar_path} "
      f"-DgroupId=net.sf.saxon -DartifactId=Saxon-HE-fork "
      f"-Dversion={SAXON_VERSION} -Dpackaging=jar -q")
    emit("Saxon-HE fork installed to local Maven repo")


def do_build(args) -> None:
    """Build the project with Maven"""
    c("mvn package -DskipTests -q")


def do_clean(args) -> None:
    """Clean build artifacts"""
    c("mvn clean -q")


def do_test(args) -> None:
    """Run tests"""
    c("mvn test")


def do_run(args) -> None:
    """Run saxx with arguments: run <args>"""
    do_build([])
    c("./saxx " + " ".join(args))


def default() -> None:
    show_help()


# === LIBRARY FUNCTIONS ===

emit = print


def c(cmd):
    """Run command, raise on failure"""
    emit(">", cmd)
    subprocess.check_call(cmd, shell=True)


def c_ignore(cmd):
    """Run command, ignore failures"""
    emit(">", cmd)
    subprocess.call(cmd, shell=True)


def c_dir(cmd, dir):
    """Run command in specific directory"""
    emit("%s > %s" % (dir, cmd))
    subprocess.check_call(cmd, cwd=dir, shell=True)


def c_spawn(cmd, cwd=None):
    """Spawn command in background"""
    emit(">", cmd)
    subprocess.Popen(cmd, cwd=cwd, shell=True)


def copy_files(sources, destinations):
    """Copy each source to each destination"""
    for src in sources:
        for dest in destinations:
            src = os.path.abspath(src)
            dest = os.path.abspath(dest)
            emit("cp %s -> %s" % (src, dest))
            if not os.path.isdir(dest):
                emit("Directory not found", dest)
                continue
            shutil.copy(src, dest)


# === LAUNCHER (do not edit below) ===


def show_help() -> None:
    g = globals()
    emit(
        "Command not found, try",
        sys.argv[0],
        " | ".join([n[3:] for n in g if n.startswith("do_")]),
        "| <command> -h",
    )


def main() -> None:
    if len(sys.argv) < 2:
        default()
        return
    func = sys.argv[1]
    f = globals().get("do_" + func)
    if sys.argv[-1] == "-h":
        emit(
            textwrap.dedent(f.__doc__).strip()
            if f.__doc__
            else "No documentation for this command",
        )
        return
    if not f:
        show_help()
        return
    f(sys.argv[2:])


if __name__ == "__main__":
    main()
