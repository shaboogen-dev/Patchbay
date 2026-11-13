"""Search a jar's class files without unpacking it.

  python jarsearch.py <jar> strings <text>          classes whose constants contain <text>
  python jarsearch.py <jar> strings <text> --exact  classes with a constant that is exactly <text>
  python jarsearch.py <jar> callers <class> [<name> [<descriptor>]]
                                                    classes that refer to member <name> of <class>
                                                    (only with that descriptor, if given; without
                                                    <name>, every member of <class> they use)
  python jarsearch.py <jar> subclasses <class>      every class that extends or implements <class>,
                                                    directly or indirectly, with its superclass

Class names are written as in the jar: dWb, or com/bitwig/flt/app/CHR.
"""
import struct, sys, zipfile


def read_class(data):
    """Constant-pool strings, member references, and the class's own name, superclass and interfaces."""
    count = struct.unpack_from(">H", data, 8)[0]
    utf8, refs, nat, cls = {}, [], {}, {}
    i, p = 1, 10
    while i < count:
        tag = data[p]
        if tag == 1:
            n = struct.unpack_from(">H", data, p + 1)[0]
            utf8[i] = data[p + 3:p + 3 + n].decode("utf-8", "replace")
            p += 3 + n
        elif tag in (3, 4):
            p += 5
        elif tag in (5, 6):
            p += 9; i += 1
        elif tag in (7, 8, 16, 19, 20):
            if tag == 7:
                cls[i] = struct.unpack_from(">H", data, p + 1)[0]
            p += 3
        elif tag == 15:
            p += 4
        elif tag in (9, 10, 11):
            refs.append(struct.unpack_from(">HH", data, p + 1))
            p += 5
        elif tag == 12:
            nat[i] = struct.unpack_from(">HH", data, p + 1)
            p += 5
        elif tag in (17, 18):
            p += 5
        else:
            raise ValueError(f"constant tag {tag}")
        i += 1
    this, sup, n_if = struct.unpack_from(">HHH", data, p + 2)
    interfaces = [utf8[cls[struct.unpack_from(">H", data, p + 8 + 2 * k)[0]]] for k in range(n_if)]
    members = [(utf8[cls[c]], utf8[nat[t][0]], utf8[nat[t][1]]) for c, t in refs]
    return utf8, members, utf8[cls[this]], (utf8[cls[sup]] if sup else None), interfaces


def main():
    exact = "--exact" in sys.argv
    argv = [a for a in sys.argv[1:] if a != "--exact"]
    need = {"strings": 1, "callers": 1, "subclasses": 1}
    if len(argv) < 2 or argv[1] not in need or len(argv) - 2 < need[argv[1]]:
        sys.exit(__doc__)
    jar, mode, *args = argv
    parents = {}
    with zipfile.ZipFile(jar) as z:
        for entry in z.namelist():
            if not entry.endswith(".class"):
                continue
            utf8, members, name, sup, interfaces = read_class(z.read(entry))
            if mode == "strings":
                hits = sorted({s for s in utf8.values() if (s == args[0] if exact else args[0] in s)})
                if hits:
                    print(name, *hits[:5], sep="\t")
            elif mode == "callers":
                for owner, member, desc in members:
                    if owner == args[0] and (len(args) < 2 or member == args[1]) and (len(args) < 3 or desc == args[2]):
                        print(name, f"{owner}.{member} {desc}", sep="\t")
            elif mode == "subclasses":
                parents[name] = (sup, interfaces)
    if mode == "subclasses":
        found, todo = set(), [args[0]]
        while todo:
            base = todo.pop()
            for name, (sup, interfaces) in parents.items():
                if name not in found and (sup == base or base in interfaces):
                    found.add(name)
                    todo.append(name)
        for name in sorted(found):
            print(name, "extends " + str(parents[name][0]), sep="\t")


if __name__ == "__main__":
    main()
