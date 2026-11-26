#!/usr/bin/env python3
"""AltStore / SideStore source for the iPhone app.

  tools/altstore-source.py make --ipa dist/Tether-1.2.0-unsigned.ipa --version 1.2.0 --date 2026-11-25 \
      --notes-file notes.txt --download-url https://github.com/<owner>/<repo>/releases/download/v1.2.0/<file> \
      --out dist/altstore.json
  tools/altstore-source.py validate dist/altstore.json
  tools/altstore-source.py selftest          (used by tools/test-all.sh: builds a fake IPA, makes and validates)

The release workflow publishes the result as a release asset, so the source has the stable address
https://github.com/amirwmr/tether/releases/latest/download/altstore.json (docs/INSTALL.md). Bundle id, version,
build number and minimum iOS are read from the IPA's own Info.plist, so the source can never disagree with it.
Format: https://faq.altstore.io/developers/make-a-source (standard library only; runs on CI runners as is).
"""
import argparse, io, json, os, plistlib, re, sys, tempfile, zipfile

REPO = "https://github.com/amirwmr/tether"
RAW = "https://raw.githubusercontent.com/amirwmr/tether/main"
SCREENSHOTS = ["recents", "thread", "one-time-code", "contacts", "keypad", "in-call", "phone-tools", "messages-persian"]


def ipa_info(path):
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", n)]
        if len(names) != 1:
            raise SystemExit(f"{path}: expected one Payload/*.app/Info.plist, found {len(names)}")
        info = plistlib.loads(z.read(names[0]))
        app_dir = names[0].rsplit("/", 1)[0]
        has_icon = any(n.startswith(app_dir + "/AppIcon") or n == app_dir + "/Assets.car" for n in z.namelist())
    return info, has_icon


def make(a):
    info, has_icon = ipa_info(a.ipa)
    if not has_icon:
        raise SystemExit(f"{a.ipa}: no app icon in the bundle")
    if info.get("CFBundleShortVersionString") != a.version.split("-")[0]:
        raise SystemExit(f"IPA version {info.get('CFBundleShortVersionString')} is not {a.version}")
    notes = open(a.notes_file, encoding="utf-8").read().strip() if a.notes_file else f"Tether {a.version}"
    version = {
        "version": info["CFBundleShortVersionString"],
        "buildVersion": str(info["CFBundleVersion"]),
        "date": a.date,
        "localizedDescription": notes[:4000],
        "downloadURL": a.download_url,
        "size": os.path.getsize(a.ipa),
        "minOSVersion": info.get("MinimumOSVersion", "16.0"),
    }
    source = {
        "name": "Tether",
        "identifier": "app.tether.source",
        "subtitle": "Your Android phone's calls and texts on your iPhone",
        "description": "The iPhone app of Tether. It needs the Tether app on an Android phone that holds the SIM cards.",
        "iconURL": f"{RAW}/ios/Tether/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
        "website": REPO,
        "tintColor": "#1E88E5",
        "apps": [{
            "name": info.get("CFBundleDisplayName") or info.get("CFBundleName", "Tether"),
            "bundleIdentifier": info["CFBundleIdentifier"],
            "developerName": "The Tether authors",
            "subtitle": "Calls, texts and contacts of your Android phone",
            "localizedDescription": (
                "Tether puts an Android phone's calls, SMS, contacts and call history on your iPhone over an end-to-end "
                "encrypted Bluetooth link, with no server and no account. Install the Tether app on the Android phone "
                "with the SIM cards first, then pair with its QR code. Guide: " + REPO + "/blob/main/docs/INSTALL.md"),
            "iconURL": f"{RAW}/ios/Tether/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
            "tintColor": "#1E88E5",
            "category": "utilities",
            "screenshots": [f"{RAW}/docs/images/{s}.png" for s in SCREENSHOTS],
            "versions": [version],
            "appPermissions": {
                "entitlements": [],
                "privacy": {k: v for k, v in info.items() if k.startswith("NS") and k.endswith("UsageDescription")},
            },
        }],
        "news": [],
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(source, f, ensure_ascii=False, indent=2)
        f.write("\n")
    errors = validate_obj(source)
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"wrote {a.out}: {version['version']} ({version['buildVersion']}), {version['size']} bytes")


def validate_obj(s):
    e = []
    def need(obj, key, typ, where):
        if not isinstance(obj.get(key), typ) or obj.get(key) in ("", []):
            e.append(f"{where}.{key}: missing or not {typ.__name__}")
    url = re.compile(r"^https://\S+$")
    for k in ("name", "identifier"):
        need(s, k, str, "source")
    need(s, "apps", list, "source")
    for k in ("iconURL", "website"):
        if k in s and not url.match(str(s[k])):
            e.append(f"source.{k}: not an https URL")
    for i, app in enumerate(s.get("apps") or []):
        w = f"apps[{i}]"
        for k in ("name", "bundleIdentifier", "developerName", "localizedDescription", "iconURL"):
            need(app, k, str, w)
        if not url.match(str(app.get("iconURL", ""))):
            e.append(f"{w}.iconURL: not an https URL")
        if not re.fullmatch(r"[A-Za-z0-9.-]+", str(app.get("bundleIdentifier", ""))):
            e.append(f"{w}.bundleIdentifier: not a bundle id")
        for sc in app.get("screenshots", []):
            if not url.match(str(sc if isinstance(sc, str) else sc.get("imageURL", ""))):
                e.append(f"{w}.screenshots: {sc!r} is not an https URL")
        need(app, "versions", list, w)
        for j, v in enumerate(app.get("versions") or []):
            vw = f"{w}.versions[{j}]"
            for k in ("version", "date", "downloadURL"):
                need(v, k, str, vw)
            if not isinstance(v.get("size"), int) or v.get("size", 0) <= 0:
                e.append(f"{vw}.size: must be a positive integer (bytes)")
            if not re.fullmatch(r"\d+(\.\d+){0,2}", str(v.get("version", ""))):
                e.append(f"{vw}.version: {v.get('version')!r} is not like 1.2.0")
            if "buildVersion" in v and not re.fullmatch(r"\d+(\.\d+)*", str(v["buildVersion"])):
                e.append(f"{vw}.buildVersion: not numeric")
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}(T[\d:.]+(Z|[+-]\d{2}:?\d{2})?)?", str(v.get("date", ""))):
                e.append(f"{vw}.date: not ISO 8601")
            if not url.match(str(v.get("downloadURL", ""))) or not str(v.get("downloadURL")).endswith(".ipa"):
                e.append(f"{vw}.downloadURL: not an https URL of an .ipa")
            mo = str(v.get("minOSVersion", "16.0"))
            if not re.fullmatch(r"\d+(\.\d+){0,2}", mo) or int(mo.split(".")[0]) > 16:
                e.append(f"{vw}.minOSVersion: {mo!r} (the app supports iOS 16)")
        perms = app.get("appPermissions", {})
        if not isinstance(perms.get("entitlements", []), list) or not isinstance(perms.get("privacy", {}), dict):
            e.append(f"{w}.appPermissions: entitlements must be a list, privacy a dictionary")
    return e


def validate(a):
    with open(a.file, encoding="utf-8") as f:
        errors = validate_obj(json.load(f))
    if errors:
        print("\n".join(errors), file=sys.stderr); sys.exit(1)
    print(f"{a.file}: valid AltStore source")


def selftest(_):
    with tempfile.TemporaryDirectory() as d:
        ipa = os.path.join(d, "Tether-9.8.7-unsigned.ipa")
        with zipfile.ZipFile(ipa, "w") as z:
            z.writestr("Payload/Tether.app/Info.plist", plistlib.dumps({
                "CFBundleIdentifier": "com.example.tether", "CFBundleShortVersionString": "9.8.7",
                "CFBundleVersion": "9080799", "MinimumOSVersion": "16.0", "CFBundleDisplayName": "Cellular",
                "NSBluetoothAlwaysUsageDescription": "Bluetooth"}))
            z.writestr("Payload/Tether.app/AppIcon60x60@2x.png", b"png")
        notes = os.path.join(d, "n.txt")
        open(notes, "w").write("Test notes")
        out = os.path.join(d, "altstore.json")
        sys.argv = ["x", "make", "--ipa", ipa, "--version", "9.8.7-rc.1", "--date", "2026-11-25", "--notes-file", notes,
                    "--download-url", "https://example.com/Tether-9.8.7-unsigned.ipa", "--out", out]
        main()
        s = json.load(open(out))
        v = s["apps"][0]["versions"][0]
        assert v["buildVersion"] == "9080799" and v["size"] > 0 and v["minOSVersion"] == "16.0", v
        assert s["apps"][0]["appPermissions"]["privacy"] == {"NSBluetoothAlwaysUsageDescription": "Bluetooth"}
        bad = json.loads(json.dumps(s)); bad["apps"][0]["versions"][0]["size"] = "big"
        bad["apps"][0]["versions"][0]["downloadURL"] = "http://x/y.zip"
        assert len(validate_obj(bad)) == 2, validate_obj(bad)
    print("selftest ok")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("--ipa", required=True); m.add_argument("--version", required=True)
    m.add_argument("--date", required=True); m.add_argument("--notes-file")
    m.add_argument("--download-url", required=True); m.add_argument("--out", required=True)
    m.set_defaults(fn=make)
    v = sub.add_parser("validate"); v.add_argument("file"); v.set_defaults(fn=validate)
    sub.add_parser("selftest").set_defaults(fn=selftest)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
