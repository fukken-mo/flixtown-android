#!/usr/bin/env python3
"""Builds small APK-shaped test files for the panel's inspector.

Each file has a real compiled (binary XML) AndroidManifest.xml and, optionally, an APK Signing
Block in the v2 layout carrying a real X.509 certificate, so package/version/signer parsing can be
tested without the Android SDK. Real Gradle-built APKs are tested in CI.

usage: make_fixture_apk.py OUT.apk PACKAGE VERSION_CODE VERSION_NAME [--utf16] [--cert cert.der]
"""
import struct, sys, zipfile, io, argparse

ANDROID_NS = "http://schemas.android.com/apk/res/android"
RES = {"versionCode": 0x0101021b, "versionName": 0x0101021c, "minSdkVersion": 0x0101020c}


def string_pool(strings, utf8):
    data = b""; offsets = []
    for s in strings:
        offsets.append(len(data))
        if utf8:
            b = s.encode("utf-8")
            def ln(n): return bytes([n]) if n < 0x80 else bytes([0x80 | (n >> 8), n & 0xff])
            data += ln(len(s)) + ln(len(b)) + b + b"\x00"
        else:
            u = s.encode("utf-16-le")
            data += struct.pack("<H", len(u) // 2) + u + b"\x00\x00"
    while len(data) % 4: data += b"\x00"
    header = 28
    strings_start = header + 4 * len(strings)
    body = b"".join(struct.pack("<I", o) for o in offsets) + data
    flags = 0x100 if utf8 else 0
    return struct.pack("<HHIIIIII", 0x0001, header, header + len(body), len(strings), 0, flags, strings_start, 0) + body


def start_element(idx, name, attrs):
    # attrs: list of (ns_index or 0xffffffff, name_index, raw_index or 0xffffffff, data_type, data)
    ext = struct.pack("<IIHHHHHH", 0xffffffff, idx[name], 20, 20, len(attrs), 0, 0, 0)
    body = ext + b"".join(struct.pack("<IIIHBBI", ns, n, raw, 8, 0, t, d) for ns, n, raw, t, d in attrs)
    return struct.pack("<HHIII", 0x0102, 16, 16 + len(body), 1, 0xffffffff) + body


def end_element(idx, name):
    return struct.pack("<HHIII", 0x0103, 16, 24, 1, 0xffffffff) + struct.pack("<II", 0xffffffff, idx[name])


def manifest(package, version_code, version_name, utf8=True, min_sdk=23):
    # Attribute names with resource IDs come first, matching the resource map (as aapt2 does).
    strings = ["versionCode", "versionName", "minSdkVersion", "android", ANDROID_NS, "package", "manifest", "uses-sdk", package, version_name]
    idx = {s: i for i, s in enumerate(strings)}
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + 12) + struct.pack("<III", RES["versionCode"], RES["versionName"], RES["minSdkVersion"])
    ns = idx[ANDROID_NS]
    ns_start = struct.pack("<HHIII", 0x0100, 16, 24, 1, 0xffffffff) + struct.pack("<II", idx["android"], ns)
    m = start_element(idx, "manifest", [
        (ns, idx["versionCode"], 0xffffffff, 0x10, version_code),
        (ns, idx["versionName"], idx[version_name], 0x03, idx[version_name]),
        (0xffffffff, idx["package"], idx[package], 0x03, idx[package]),
    ])
    u = start_element(idx, "uses-sdk", [(ns, idx["minSdkVersion"], 0xffffffff, 0x10, min_sdk)])
    chunks = string_pool(strings, utf8) + resmap + ns_start + m + u + end_element(idx, "uses-sdk") + end_element(idx, "manifest")
    return struct.pack("<HHI", 0x0003, 8, 8 + len(chunks)) + chunks


def lp(b): return struct.pack("<I", len(b)) + b


def signing_block(cert_der):
    signed_data = lp(b"") + lp(lp(cert_der)) + lp(b"")          # digests, certificates, attributes
    signer = lp(signed_data) + lp(b"") + lp(b"")                 # signed data, signatures, public key
    value = lp(lp(signer))                                       # signers sequence
    pair = struct.pack("<QI", len(value) + 4, 0x7109871a) + value
    size = len(pair) + 8 + 16
    return struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"


def build(out, package, version_code, version_name, utf8=True, cert=None, padding=0):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", manifest(package, version_code, version_name, utf8))
        z.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * (64 + padding))
    data = buf.getvalue()
    if cert:
        # Insert the signing block right before the central directory and fix the EOCD offset.
        eocd = data.rfind(b"PK\x05\x06")
        cd_offset = struct.unpack("<I", data[eocd + 16:eocd + 20])[0]
        block = signing_block(cert)
        data = data[:cd_offset] + block + data[cd_offset:]
        eocd += len(block)
        data = data[:eocd + 16] + struct.pack("<I", cd_offset + len(block)) + data[eocd + 20:]
    open(out, "wb").write(data)


if __name__ == "__main__":
    a = argparse.ArgumentParser()
    a.add_argument("out"); a.add_argument("package"); a.add_argument("version_code", type=int); a.add_argument("version_name")
    a.add_argument("--utf16", action="store_true"); a.add_argument("--cert"); a.add_argument("--padding", type=int, default=0)
    o = a.parse_args()
    build(o.out, o.package, o.version_code, o.version_name, not o.utf16, open(o.cert, "rb").read() if o.cert else None, o.padding)
