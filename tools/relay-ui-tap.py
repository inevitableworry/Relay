#!/usr/bin/env python3
"""Inspect/tap an accessible Relay UI row over an already-connected ADB serial."""
import argparse
import re
import subprocess
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument("serial")
parser.add_argument("--text",default="")
parser.add_argument("--contains",action="store_true")
parser.add_argument("--tap",action="store_true")
args=parser.parse_args()

def adb(*parts):
    return subprocess.run(["adb","-s",args.serial,*parts],check=True,
                          stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)

adb("shell","uiautomator","dump","/sdcard/relay-ui-a11y.xml")
source=adb("exec-out","cat","/sdcard/relay-ui-a11y.xml").stdout
tree=ET.fromstring(source)
rows=[]
for node in tree.iter("node"):
    text=node.attrib.get("text","").strip()
    description=node.attrib.get("content-desc","").strip()
    bounds=node.attrib.get("bounds","")
    match=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",bounds)
    if not match: continue
    if args.text and not any(
        args.text.casefold() in field.casefold() if args.contains
        else args.text.casefold()==field.casefold()
        for field in (text,description)
    ):
        continue
    if not args.text and not text and not description: continue
    l,t,r,b=map(int,match.groups())
    rows.append((text,description,(l+r)//2,(t+b)//2,bounds))

if args.tap:
    if len(rows)!=1:
        print(f"Expected one match for {args.text!r}; found {len(rows)}.")
        for row in rows[:12]: print(row)
        raise SystemExit(2)
    text,description,x,y,bounds=rows[0]
    adb("shell","input","tap",str(x),str(y))
    print(f"Tapped {text or description}: ({x},{y})")
else:
    for text,description,x,y,bounds in rows[:100]:
        print(f"{(text or description)[:100]!r} {bounds} center=({x},{y})")
    print(f"Shown {min(len(rows),100)} of {len(rows)} matching nodes")
