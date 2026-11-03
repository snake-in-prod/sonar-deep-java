import csv, re, sys, collections
cat_rule = {"sqli": "TaintedSql", "cmdi": "TaintedCommand", "pathtraver": "TaintedPath", "ldapi": "TaintedLdap", "xpathi": "TaintedXPath"}
found = collections.defaultdict(set)
for line in open(sys.argv[1]):
    rule, path = line.split(",")[:2]
    m = re.search(r"(BenchmarkTest\d+)\.java$", path)
    if m:
        found[m.group(1)].add(rule)
stats = collections.defaultdict(lambda: [0, 0, 0, 0])  # tp fn fp tn
for row in csv.reader(open(sys.argv[2])):
    if row[0].startswith("#"):
        continue
    name, cat, real = row[0], row[1], row[2] == "true"
    if cat not in cat_rule:
        continue
    hit = cat_rule[cat] in found[name]
    s = stats[cat]
    s[0 if real and hit else 1 if real else 2 if hit else 3] += 1
print("category      TP   FN   FP   TN   TPR   FPR   score")
for cat, (tp, fn, fp, tn) in sorted(stats.items()):
    tpr = tp / (tp + fn) if tp + fn else 0
    fpr = fp / (fp + tn) if fp + tn else 0
    print(f"{cat:12} {tp:4} {fn:4} {fp:4} {tn:4}  {tpr:5.0%} {fpr:5.0%} {tpr - fpr:+6.0%}")
other = collections.Counter(r for n, rs in found.items() for r in rs)
print("all findings by rule:", dict(other))
