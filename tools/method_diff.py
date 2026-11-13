"""usage: method_diff.py <want.class> <got.class> [method-substring]  -- per-method javap -c -v diff, cp indexes stripped"""
import re, subprocess, sys, difflib
J = r"C:\Program Files\Java\jdk-25.0.4.1\bin\javap"
JUMP = re.compile(r"^(\s*)(\d+): (if\w*|goto\w*|jsr\w*)(\s+)(\d+)(.*)$")
INSN = re.compile(r"^(\s*)(\d+): (\S+)(.*)$")

def renumber(lines):
    """Replace byte offsets by instruction indexes (so ldc vs ldc_w does not shift everything) and drop frame deltas."""
    index, n = {}, 0
    for ln in lines:
        m = INSN.match(ln)
        if m and m.group(3)[0].isalpha():
            index[m.group(2)] = n; n += 1
    out = []
    for ln in lines:
        ln = ln.replace("ldc_w ", "ldc ").replace("ldc2_w", "ldc2_w")
        m = JUMP.match(ln)
        if m:
            ln = f"{m.group(1)}@{index.get(m.group(2), m.group(2))}: {m.group(3)} @{index.get(m.group(5), m.group(5))}{m.group(6)}"
        else:
            m = INSN.match(ln)
            if m and m.group(3)[0].isalpha():
                ln = f"{m.group(1)}@{index[m.group(2)]}: {m.group(3)}{m.group(4)}"
        m = re.match(r"^(\s*)(\d+)\s+(\d+)\s+(\d+)\s+(.*)$", ln)
        if m and all(g in index or g.isdigit() for g in m.group(2, 3, 4)):
            ln = f"{m.group(1)}@{index.get(m.group(2), m.group(2))} @{index.get(m.group(3), m.group(3))} @{index.get(m.group(4), m.group(4))} {m.group(5)}"
        if "offset_delta" in ln:
            continue
        ln = re.sub(r"frame_type = \d+ /\* (\w+) \*/", r"frame_type \1", ln)
        out.append(ln)
    return out

def methods(p):
    out = subprocess.run([J, "-c", "-v", "-p", p], capture_output=True, text=True, errors="replace").stdout
    body = out.split("\n{\n", 1)[1] if "\n{\n" in out else out
    ms, cur, name = {}, [], None
    for ln in body.splitlines():
        if re.match(r"^  \S", ln) and not ln.startswith("   "):
            if name: ms[name] = cur
            name, cur = re.sub(r"lambda\$(\w+)\$\d+", r"lambda$\1$N", re.sub(r"\s+", " ", re.sub(r"#\d+", "#", ln.strip()))), []
        elif name:
            if re.search(r"line \d+: \d+$|LineNumberTable", ln): continue
            ln = re.sub(r"#\d+(,\s*\d+)?", "#", ln); ln = re.sub(r"lambda\$(\w+)\$\d+", r"lambda$\1$N", ln)
            cur.append(re.sub(r"\s+", " ", ln))
    if name: ms[name] = cur
    return {k: renumber(v) for k, v in ms.items()}
if __name__ == "__main__":
    a, b = methods(sys.argv[1]), methods(sys.argv[2])
    flt = sys.argv[3] if len(sys.argv) > 3 else ""
    for k in a:
        if flt and flt not in k: continue
        if k not in b: print("ONLY IN WANT:", k); continue
        if a[k] != b[k]:
            print("=== ", k)
            for d in difflib.unified_diff(a[k], b[k], "want", "got", n=2, lineterm=""): print(d)
    for k in b:
        if k not in a and (not flt or flt in k): print("ONLY IN GOT:", k)
