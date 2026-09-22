#!/usr/bin/env python3
"""
Validates every shader Shaders.kt assembles, using glslangValidator.

Shaders are built by concatenating Kotlin string fragments, so a shader that is never
written out in one piece can still be broken. This reassembles them exactly as the
Kotlin does and compiles each one.

Needs: glslangValidator  (apt install glslang-tools)
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "app/src/main/java/com/fractal/deepzoom/Shaders.kt")


def trim_indent(body):
    """
    Kotlin's trimIndent: drop leading/trailing blank lines, then remove the common
    indentation. Without this every shader keeps the newline that follows the opening
    triple quote, and #version is no longer the first thing in the file.
    """
    lines = body.split("\n")
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    indents = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    pad = min(indents) if indents else 0
    return "\n".join(l[pad:] if len(l) >= pad else l for l in lines)


def load_fragments(text):
    """Every `val NAME = \"\"\"...\"\"\"` fragment in the file."""
    out = {}
    for m in re.finditer(
        r'val\s+(\w+)\s*=\s*"""(.*?)"""\.trimIndent\(\)', text, re.S
    ):
        out[m.group(1)] = trim_indent(m.group(2))
    return out


def load_assembled(text, frags):
    """
    Complete shaders: both the `val NAME = "#version ...$A$B"` one-liners and the
    triple-quoted blocks that already start with #version, so nothing is skipped
    merely because of how it happens to be quoted.
    """
    out = {}
    for m in re.finditer(r'val\s+(\w+)\s*=\s*"(#version[^"]*)"', text):
        out[m.group(1)] = m.group(2)
    for name, body in frags.items():
        if body.lstrip().startswith("#version"):
            out[name] = body
    return out


def main():
    text = open(SRC).read()
    frags = load_fragments(text)
    assembled = load_assembled(text, frags)
    if not assembled:
        print("no assembled shaders found - has Shaders.kt changed shape?")
        return 1

    failures = 0
    for name, template in sorted(assembled.items()):
        body = template.replace("\\n", "\n")
        # Substitute $FRAGMENT references.
        def sub(m):
            key = m.group(1)
            if key not in frags:
                raise SystemExit(f"{name}: unknown fragment ${key}")
            return frags[key]
        body = re.sub(r"\$(\w+)", sub, body)

        # Vertex shaders declare no fragment output; pick stage by content.
        stage = "vert" if "gl_Position" in body and "fragColor" not in body else "frag"
        with tempfile.NamedTemporaryFile("w", suffix=f".{stage}", delete=False) as f:
            f.write(body)
            path = f.name
        try:
            r = subprocess.run(
                ["glslangValidator", "-S", stage, path],
                capture_output=True, text=True
            )
            if r.returncode != 0:
                failures += 1
                print(f"FAIL {name} ({stage})")
                print(r.stdout.strip()[:2000])
            else:
                print(f"ok   {name} ({stage}, {len(body.splitlines())} lines)")
        finally:
            os.unlink(path)

    if failures:
        print(f"\n{failures} shader(s) failed to compile")
        return 1
    print(f"\nall {len(assembled)} shaders compile")
    return 0


if __name__ == "__main__":
    sys.exit(main())
