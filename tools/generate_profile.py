#!/usr/bin/env python3
"""Writes docs/deep-java-profile.xml: Sonar way + the sonar-findbugs families of the spec (section 10) + Deep Java.

Usage: generate_profile.py <sonar-java-plugin.jar> <sonar-findbugs-plugin.jar> <deep-java rule keys file>
The XML can be restored in SonarQube (Quality Profiles > Restore). Rules missing on the server are skipped there.
"""
import re
import sys
import zipfile
from xml.sax.saxutils import escape

FINDBUGS_FAMILIES = ("NP_", "OS_", "ODR_", "OBL_", "RV_", "DMI_", "EC_", "SE_", "UWF_")


def sonar_way(jar):
    with zipfile.ZipFile(jar) as z:
        keys = {m.group(1) for n in z.namelist() if (m := re.match(r"profiles/Sonar_way/(S\d+)/?$", n))}
    return sorted(keys, key=lambda k: int(k[1:]))


def findbugs(jar):
    rules = []
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            m = re.search(r"rules-(findbugs|fbcontrib|findsecbugs)\.xml$", name)
            if not m:
                continue
            repo = {"findbugs": "findbugs", "fbcontrib": "fb-contrib", "findsecbugs": "findsecbugs"}[m.group(1)]
            for key in re.findall(r"<rule key=['\"]([^'\"]+)['\"]", z.read(name).decode("utf-8")):
                if repo != "findbugs" or key.startswith(FINDBUGS_FAMILIES) or key == "IS2_INCONSISTENT_SYNC":
                    rules.append((repo, key))
    return sorted(set(rules))


def main():
    java_jar, findbugs_jar, deep_keys = sys.argv[1:4]
    rules = [("java", k) for k in sonar_way(java_jar)] + findbugs(findbugs_jar)
    rules += [("deepjava", k.strip()) for k in open(deep_keys) if k.strip()]
    out = ["<?xml version='1.0' encoding='UTF-8'?>", "<profile>", "  <name>Deep Java</name>", "  <language>java</language>", "  <rules>"]
    for repo, key in rules:
        out.append(f"    <rule><repositoryKey>{escape(repo)}</repositoryKey><key>{escape(key)}</key></rule>")
    out += ["  </rules>", "</profile>", ""]
    open("docs/deep-java-profile.xml", "w").write("\n".join(out))
    counts = {}
    for repo, _ in rules:
        counts[repo] = counts.get(repo, 0) + 1
    print(counts)


if __name__ == "__main__":
    main()
