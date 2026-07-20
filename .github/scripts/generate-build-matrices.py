import itertools
import json
import os
import re
import subprocess
import sys
from pathlib import Path

EXTENSION_REGEX = re.compile(r"^src/(?P<lang>\w+)/(?P<extension>\w+)")
MULTISRC_LIB_REGEX = re.compile(r"^lib-multisrc/(?P<multisrc>\w+)")
LIB_REGEX = re.compile(r"^lib/(?P<lib>\w+)")
MODULE_REGEX = re.compile(r"^:src:(?P<lang>\w+):(?P<extension>\w+)$")
CORE_FILES_REGEX = re.compile(
    r"^(common/|compiler/|core/|gradle/|build\.gradle\.kts|gradle\.properties|settings\.gradle\.kts|.github/scripts)"
)

def run_command(command: str) -> str:
    result = subprocess.run(command, capture_output=True, text=True, shell=True)
    if result.returncode != 0:
        print(result.stderr.strip())
        sys.exit(result.returncode)
    return result.stdout.strip()


def batched(iterable, n: int):
    if hasattr(itertools, "batched"):
        yield from itertools.batched(iterable, n)
        return

    iterator = iter(iterable)
    while batch := tuple(itertools.islice(iterator, n)):
        yield batch


def resolve_dependent_libs(libs: set[str]) -> set[str]:
    """
    returns all libs which depend on any of the passed libs (/lib),
    recursively resolving transitive dependencies
    """
    if not libs:
        return set()

    all_dependent_libs = set()
    to_process = set(libs)

    while to_process:
        current_libs = to_process
        to_process = set()

        lib_dependency = re.compile(
            rf"project\([\"']:(?:lib):({'|'.join(map(re.escape, current_libs))})[\"']\)"
        )

        for lib in Path("lib").iterdir():
            if lib.name in all_dependent_libs or lib.name in libs:
                continue

            build_file = lib / "build.gradle.kts"
            if not build_file.is_file():
                continue

            content = build_file.read_text("utf-8")

            if lib_dependency.search(content):
                all_dependent_libs.add(lib.name)
                to_process.add(lib.name)

    return all_dependent_libs


def resolve_multisrc_lib(libs: set[str]) -> set[str]:
    """
    returns all multisrc which depend on any of the
    passed libs (/lib)
    """
    if not libs:
        return set()

    lib_dependency = re.compile(
        rf"project\([\"']:(?:lib):({'|'.join(map(re.escape, libs))})[\"']\)"
    )

    multisrcs = set()

    for multisrc in Path("lib-multisrc").iterdir():
        build_file = multisrc / "build.gradle.kts"
        if not build_file.is_file():
            continue

        content = build_file.read_text("utf-8")

        if (lib_dependency.search(content)):
            multisrcs.add(multisrc.name)

    return multisrcs

def resolve_ext(multisrcs: set[str], libs: set[str]) -> set[tuple[str, str]]:
    """
    returns all extensions which depend on any of the
    passed multisrcs or libs
    """
    if not multisrcs and not libs:
        return set()

    multisrc_pattern = '|'.join(map(re.escape, multisrcs)) if multisrcs else None
    lib_pattern = '|'.join(map(re.escape, libs)) if libs else None

    patterns = []
    if multisrc_pattern:
        patterns.append(rf"theme\s*=\s*['\"]({multisrc_pattern})['\"]")
    if lib_pattern:
        patterns.append(rf"project\([\"']:(?:lib):({lib_pattern})[\"']\)")

    regex = re.compile('|'.join(patterns))

    extensions = set()

    for lang in Path("src").iterdir():
        for extension in lang.iterdir():
            build_file = extension / "build.gradle.kts"
            if not build_file.is_file():
                continue

            content = build_file.read_text("utf-8")

            if regex.search(content):
                extensions.add((lang.name, extension.name))

    return extensions

def get_changed_modules(ref: str) -> tuple[list[str], list[str]]:
    diff_output = run_command(f"git diff --name-status {ref}").splitlines()

    changed_files = [
        file
        for line in diff_output
        for file in line.split("\t", 2)[1:]
    ]

    modules = set()
    multisrcs = set()
    libs = set()
    deleted = set()
    core_files_changed = False

    for file in map(lambda x: Path(x).as_posix(), changed_files):
        if CORE_FILES_REGEX.search(file):
            core_files_changed = True
        elif match := EXTENSION_REGEX.search(file):
            lang = match.group("lang")
            extension = match.group("extension")
            if Path("src", lang, extension).is_dir():
                modules.add(f":src:{lang}:{extension}")
            deleted.add(f"{lang}.{extension}")
        elif match := MULTISRC_LIB_REGEX.search(file):
            multisrc = match.group("multisrc")
            if Path("lib-multisrc", multisrc).is_dir():
                multisrcs.add(multisrc)
        elif match := LIB_REGEX.search(file):
            lib = match.group("lib")
            if Path("lib", lib).is_dir():
                libs.add(lib)

    if core_files_changed:
        all_modules, all_deleted = get_all_modules()
        modules.update(all_modules)
        deleted.update(all_deleted)
        return sorted(modules), sorted(deleted)

    libs.update(resolve_dependent_libs(libs))
    multisrcs.update(resolve_multisrc_lib(libs))
    extensions = resolve_ext(multisrcs, libs)
    modules.update([f":src:{lang}:{extension}" for lang, extension in extensions])
    deleted.update([f"{lang}.{extension}" for lang, extension in extensions])

    return sorted(modules), sorted(deleted)


def get_modules(ref: str, mode: str) -> tuple[list[str], list[str]]:
    if mode == "all":
        return get_all_modules()
    if mode == "changed":
        return get_changed_modules(ref)
    raise ValueError(f"Unsupported mode: {mode}")


def get_all_modules() -> tuple[list[str], list[str]]:
    modules = []
    deleted = []
    for lang in sorted(Path("src").iterdir(), key=lambda path: path.name):
        for extension in sorted(lang.iterdir(), key=lambda path: path.name):
            modules.append(f":src:{lang.name}:{extension.name}")
            deleted.append(f"{lang.name}.{extension.name}")
    return modules, deleted


def chunk_modules(modules: list[str], build_type: str) -> dict[str, list[dict[str, object]]]:
    chunk_size = int(os.getenv("CI_CHUNK_SIZE", 65))
    gradle_tasks = [f"{module}:assemble{build_type}" for module in modules]
    return {
        "chunk": [
            {"number": i + 1, "modules": chunk}
            for i, chunk in enumerate(batched(gradle_tasks, chunk_size))
        ]
    }


def main() -> None:
    _, ref, build_type, mode = sys.argv
    modules, deleted = get_modules(ref, mode)
    chunked = chunk_modules(modules, build_type)

    print(
        "Module chunks to build:\n"
        f"{json.dumps(chunked, indent=2)}\n\n"
        "Module to delete:\n"
        f"{json.dumps(deleted, indent=2)}"
    )

    if os.getenv("CI") == "true":
        with open(os.getenv("GITHUB_OUTPUT"), "a", encoding="utf-8") as out_file:
            out_file.write(f"matrix={json.dumps(chunked)}\n")
            out_file.write(f"delete={json.dumps(deleted)}\n")


if __name__ == "__main__":
    main()
