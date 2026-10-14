"""Confirm the signed APK contains exactly the verified voice archive, or none for the normal app."""
import argparse
import hashlib
import json
import zipfile

ASSET = "assets/voice-packs/kokoro.tar.bz2"


def verify(apk, absent=False):
    with zipfile.ZipFile(apk) as package:
        if absent:
            if ASSET in package.namelist():
                raise ValueError("Normal JARVIS APK unexpectedly contains the Beta voice archive")
            return
        meta = json.loads(package.read("assets/neural-voice-pack.json"))
        info = package.getinfo(ASSET)
        if info.file_size != meta["bytes"] or info.compress_type != zipfile.ZIP_STORED:
            raise ValueError("Bundled voice archive size/compression is incorrect")
        with package.open(ASSET) as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != meta["sha256"]:
            raise ValueError("Bundled voice archive does not match verified metadata")
    print(f"Verified bundled offline voice archive in {apk}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--absent", action="store_true")
    args = parser.parse_args()
    verify(args.apk, args.absent)
