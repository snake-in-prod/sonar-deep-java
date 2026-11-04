#!/usr/bin/env python3
"""Writes docs/deep-java-profile.xml: the sonar-findbugs families of the spec (section 10) + the Deep Java rules.

Usage: generate_profile.py <sonar-findbugs-plugin.jar> <deep-java rule keys file>
Restore the XML in SonarQube, then make "Sonar way" its parent, so the profile = Sonar way + these rules and
follows Sonar way when the Java plugin is upgraded (see docs/adr/0001-installation-and-profile.md).
Rules of plugins that are not installed are skipped by the restore.
"""
import re
import sys
import zipfile
from xml.sax.saxutils import escape

FINDBUGS_FAMILIES = ("NP_", "OS_", "ODR_", "OBL_", "RV_", "DMI_", "EC_", "SE_", "UWF_")


def findbugs(jar):
    rules = []
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            m = re.search(r"rules-(findbugs|fbcontrib|findsecbugs)\.xml$", name)
            if not m:
                continue
            repo = {"findbugs": "findbugs", "fbcontrib": "fb-contrib", "findsecbugs": "findsecbugs"}[m.group(1)]
            for key in re.findall(r"<rule key=['\"]([^'\"]+)['\"]", z.read(name).decode("utf-8")):
                if repo != "findbugs" or key.startswith(FINDBUGS_FAMILIES) or key in ("IS2_INCONSISTENT_SYNC", "GC_UNRELATED_TYPES"):
                    rules.append((repo, key))
    return sorted(set(rules))


def main():
    findbugs_jar, deep_keys = sys.argv[1:3]
    rules = findbugs(findbugs_jar)
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
