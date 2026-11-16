#!/usr/bin/env python3
"""Xtra5s: merge official Xtra into custom-xtra5.

1. A normal merge. If it is clean, done.
2. If official changes clash with Xtra5s changes, each clashing spot is settled line by line:
   official Xtra's version of the code is taken, and the Xtra5s change is put back on top of it.
   This works when the two changed different lines (most often official Xtra edited a line right
   next to an Xtra5s hook, which Git itself refuses to merge). When both changed the very same
   lines (official Xtra rewrote or removed the code an Xtra5s change plugs into), there is no
   safe way to put the Xtra5s change back.
3. If any spot can't be settled, nothing is kept and the script fails, so the workflow
   publishes nothing.

Files whose Xtra5s changes were put back on top are listed in reapplied.txt (for the changelog).
The hook check, build and tests then decide whether the result is published.

Usage: xtra5s-merge.py <official-ref>   (run from the repository root, git user configured)
"""
import difflib
import os
import subprocess
import sys

MESSAGE = "Merge official Xtra changes"


def git(*args, check=True):
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.stderr.write(result.stdout + result.stderr)
        raise SystemExit(f"git {' '.join(args)} failed")
    return result


def edits(base, other):
    """Changes from base to other as (start, end, new lines), in base line numbers."""
    matcher = difflib.SequenceMatcher(None, base, other, autojunk=False)
    return [(i1, i2, other[j1:j2]) for tag, i1, i2, j1, j2 in matcher.get_opcodes() if tag != "equal"]


def overlap(a, b):
    """True if two changes touch the same base lines (or one inserts inside the other's range)."""
    a1, a2, _ = a
    b1, b2, _ = b
    if a1 == a2 and b1 == b2:
        return False  # two insertions: both kept (official's first)
    if a1 == a2:
        return b1 < a1 < b2
    if b1 == b2:
        return a1 < b1 < a2
    return a1 < b2 and b1 < a2


def touches(a, b):
    """True if two changes meet at a line boundary (one ends where the other starts)."""
    a1, a2, _ = a
    b1, b2, _ = b
    return a2 == b1 or b2 == a1 or (a1 == a2 and (a1 == b1 or a1 == b2)) or (b1 == b2 and (b1 == a1 or b1 == a2))


def light_edit(base, change):
    """An official change that only edits lines lightly (or just adds lines), not a rewrite."""
    start, end, lines = change
    if start == end:
        return True  # pure addition
    if not lines:
        return False  # lines deleted: an Xtra5s change next to them has lost its place
    old = "\n".join(base[start:end])
    new = "\n".join(lines)
    return difflib.SequenceMatcher(None, old, new, autojunk=False).ratio() >= 0.6


def settle(base, ours, theirs):
    """Official (theirs) and Xtra5s (ours) changes to one clashing spot, combined, or None."""
    mine = edits(base, ours)
    official = edits(base, theirs)
    for m in mine:
        for o in official:
            if overlap(m, o):
                return None
            # Right next to an Xtra5s change, official Xtra may tweak a line or add lines, but
            # if it rewrote or deleted that code the Xtra5s change has nothing safe to sit on.
            if touches(m, o) and not light_edit(base, o):
                return None
    result = list(base)
    # Apply from the end so line numbers stay valid; at the same spot, Xtra5s goes after official.
    changes = [(o[0], 0, o) for o in official] + [(m[0], 1, m) for m in mine]
    for _, _, (start, end, lines) in sorted(changes, key=lambda c: (c[0], c[1]), reverse=True):
        result[start:end] = lines
    return result


def resolve_file(path):
    """Settles every conflict block (diff3 style) in path. Returns False if any can't be."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    out, i = [], 0
    while i < len(lines):
        if not lines[i].startswith("<<<<<<< "):
            out.append(lines[i])
            i += 1
            continue
        ours, base, theirs, section = [], [], [], "ours"
        i += 1
        while i < len(lines) and not lines[i].startswith(">>>>>>> "):
            line = lines[i]
            if line.startswith("||||||| "):
                section = "base"
            elif line == "=======" and section in ("ours", "base"):
                section = "theirs"
            else:
                {"ours": ours, "base": base, "theirs": theirs}[section].append(line)
            i += 1
        if i >= len(lines):
            return False
        i += 1
        combined = settle(base, ours, theirs)
        if combined is None:
            return False
        out.extend(combined)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    return True


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    official = sys.argv[1]
    if os.path.exists("reapplied.txt"):
        os.remove("reapplied.txt")
    merge = git("-c", "merge.conflictStyle=diff3", "merge", "--no-edit", official, "-m", MESSAGE, check=False)
    if merge.returncode == 0:
        print("Official Xtra merged cleanly")
        return
    conflicted = [p for p in git("diff", "--name-only", "--diff-filter=U").stdout.split() if p]
    print("Official Xtra clashes with Xtra5s changes in:\n  " + "\n  ".join(conflicted))
    failed = []
    for path in conflicted:
        if not os.path.exists(path) or not resolve_file(path):
            failed.append(path)
    if failed or not conflicted:
        git("merge", "--abort", check=False)
        raise SystemExit("::error::These files have Xtra5s changes that can't be put back on top of the official "
                         "update (official Xtra rewrote, moved or removed that code):\n  " + "\n  ".join(failed or ["(unknown)"]) +
                         "\nResolve the merge manually. Nothing was published.")
    git("add", "--", *conflicted)
    git("commit", "--no-edit")
    with open("reapplied.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(conflicted) + "\n")
    print("Xtra5s changes put back on top of the official update in:\n  " + "\n  ".join(conflicted))


if __name__ == "__main__":
    main()
