"""Juliet scoring: an issue inside a method whose name contains "bad" is a true positive for that test case,
inside a "good" method a false positive. Test cases are files grouped by name without the a/b/c... suffix."""
import collections, os, re, sys

root = sys.argv[1]          # Java/src
findings = sys.argv[2]      # rule,path,line,...
cwe_rules = {
  "CWE476": {"NullReturnDereference", "NullPassedToDereferencedParameter", "DereferenceBeforeNullCheck"},
  "CWE690": {"NullReturnDereference"},
  "CWE89": {"TaintedSql"}, "CWE78": {"TaintedCommand"}, "CWE23": {"TaintedPath"}, "CWE36": {"TaintedPath"},
  "CWE134": {"TaintedFormatString"}, "CWE129": {"TaintedSize"}, "CWE789": {"TaintedSize"}, "CWE606": {"TaintedSize"},
  "CWE400": {"TaintedSize"}, "CWE113": {"TaintedHeader"}, "CWE643": {"TaintedXPath"}, "CWE90": {"TaintedLdap"},
  "CWE601": {"TaintedRedirect"}, "CWE252": {"IgnoredResultStatistical"},
  "CWE404": {"ResourceFromHelperNotClosed"}, "CWE772": {"ResourceFromHelperNotClosed"},
  "CWE833": {"LockOrderCycle"}, "CWE667": {"LockNotReleasedAcrossMethods", "NonReentrantLockReacquired"},
}
methods = {}  # path -> [(start, end, name)]
def ranges(path):
    if path not in methods:
        lines = open(os.path.join(root, path), encoding="latin-1").read().split("\n")
        out, cur, depth = [], None, 0
        for i, l in enumerate(lines, 1):
            m = re.match(r"\s*(public|private|protected)[^=(]*\s(\w+)\s*\(", l)
            if m and depth == 1 and cur is None:
                cur = [i, None, m.group(2)]
            depth += l.count("{") - l.count("}")
            if cur and depth == 1 and "}" in l and i > cur[0]:
                cur[1] = i
                out.append(tuple(cur))
                cur = None
        methods[path] = out
    return methods[path]

hits = collections.defaultdict(lambda: collections.defaultdict(set))  # cwe -> case -> {"bad","good"}
for line in open(findings):
    parts = line.split(",")
    rule, path, ln = parts[0], parts[1], int(parts[2])
    m = re.search(r"testcases/(CWE\d+)_", path)
    if not m or rule not in cwe_rules.get(m.group(1), set()):
        continue
    case = re.sub(r"_\d+[a-e]?\.java$|[a-e]\.java$|\.java$", "", os.path.basename(path))
    case = re.sub(r"(_\d+)[a-e]$", r"\1", os.path.basename(path)[:-5])
    for s, e, name in ranges(path):
        if s <= ln <= e:
            kind = "bad" if "bad" in name.lower() else "good" if "good" in name.lower() else None
            if kind:
                hits[m.group(1)][case].add(kind)
cases = collections.defaultdict(set)
for dp, _, fs in os.walk(os.path.join(root, "testcases")):
    for f in fs:
        m = re.match(r"(CWE\d+)_.*?(_\d+)([a-e])?\.java$", f)
        if m and m.group(1) in cwe_rules:
            cases[m.group(1)].add(re.sub(r"(_\d+)[a-e]$", r"\1", f[:-5]))
print("CWE      cases   TP  TPR   FP  FPR")
for cwe in sorted(cases, key=lambda c: int(c[3:])):
    n = len(cases[cwe])
    tp = sum(1 for c in cases[cwe] if "bad" in hits[cwe][c])
    fp = sum(1 for c in cases[cwe] if "good" in hits[cwe][c])
    print(f"{cwe:8} {n:5} {tp:5} {tp / n:4.0%} {fp:4} {fp / n:4.0%}")
