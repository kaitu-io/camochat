#!/usr/bin/env python3
"""把 xcresulttool 导出的附件按 XCTAttachment 名字重命名到 <dir>/named/。"""
import json, os, re, shutil, sys
d = sys.argv[1]
out = os.path.join(d, "named"); os.makedirs(out, exist_ok=True)
for test in json.load(open(os.path.join(d, "manifest.json"))):
    for a in test["attachments"]:
        name = a["suggestedHumanReadableName"]
        src = os.path.join(d, a["exportedFileName"])
        if name.startswith("App UI hierarchy"):
            continue
        name = re.sub(r"_\d+_[0-9A-F-]{36}", "", name)
        if name.startswith("UI Snapshot") or name.startswith("Screenshot") or a.get("isAssociatedWithFailure"):
            name = "auto-" + re.sub(r"[^0-9A-Za-z.-]+", "_", name)
        ext = os.path.splitext(a["exportedFileName"])[1] or (".png" if os.path.isdir(src) is False else "")
        if os.path.isfile(src):
            base = name if os.path.splitext(name)[1] else name + ext
            shutil.copy(src, os.path.join(out, base))
print(out)
