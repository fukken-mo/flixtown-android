<?php
/**
 * Reads an APK the way Android does: the package ID and versions come from the compiled
 * (binary) AndroidManifest.xml inside the APK, never from the file name, and the signing
 * certificate comes from the APK Signature Scheme v2/v3 block.
 *
 * Self-contained: needs only PHP's zlib (gzinflate) and hash functions, not ZipArchive.
 * Works on PHP 7.2+.
 */
final class FlixApkInspector
{
    const MAX_MANIFEST_BYTES = 8388608;     // 8 MB, far above any real manifest
    const MAX_SIGNING_BLOCK_BYTES = 16777216;

    // android: attribute resource IDs (stable across Android versions)
    const ATTR_VERSION_CODE = 0x0101021b;
    const ATTR_VERSION_NAME = 0x0101021c;
    const ATTR_MIN_SDK = 0x0101020c;
    const ATTR_VERSION_CODE_MAJOR = 0x01010576;

    const SIG_V2 = 0x7109871a;
    const SIG_V3 = 0xf05368c0;
    const SIG_V31 = 0x1b93ad61;

    /**
     * @return array{package:string,version_code:int,version_name:string,min_sdk:?int,
     *               signer_sha256:?string,signature_scheme:?string,sha256:string,size:int}
     * @throws RuntimeException with a message suitable for the panel when the file is not a valid APK.
     */
    public static function inspect($path)
    {
        if (!is_file($path)) throw new RuntimeException('The APK file could not be read.');
        $size = filesize($path);
        $fh = fopen($path, 'rb');
        if (!$fh) throw new RuntimeException('The APK file could not be opened.');
        try {
            $cd = self::centralDirectory($fh, $size);
            $manifest = self::readEntry($fh, $cd, 'AndroidManifest.xml');
            if ($manifest === null) throw new RuntimeException('This file is not an Android app: it has no AndroidManifest.xml.');
            $info = self::parseManifest($manifest);
            $signer = self::signer($fh, $cd['offset']);
        } finally {
            fclose($fh);
        }
        return array(
            'package' => $info['package'],
            'version_code' => $info['version_code'],
            'version_name' => $info['version_name'],
            'min_sdk' => $info['min_sdk'],
            'signer_sha256' => $signer ? $signer['sha256'] : null,
            'signature_scheme' => $signer ? $signer['scheme'] : null,
            'sha256' => hash_file('sha256', $path),
            'size' => $size,
        );
    }

    /* ---------------- ZIP ---------------- */

    private static function readAt($fh, $offset, $length)
    {
        if ($length <= 0) return '';
        if (fseek($fh, $offset) !== 0) throw new RuntimeException('The APK file is truncated.');
        $out = '';
        while (strlen($out) < $length && !feof($fh)) {
            $chunk = fread($fh, min(1048576, $length - strlen($out)));
            if ($chunk === false || $chunk === '') break;
            $out .= $chunk;
        }
        if (strlen($out) !== $length) throw new RuntimeException('The APK file is truncated.');
        return $out;
    }

    private static function u16($s, $o) { $v = unpack('v', substr($s, $o, 2)); return $v[1]; }
    private static function u32($s, $o) { $v = unpack('V', substr($s, $o, 4)); return $v[1]; }
    private static function u64($s, $o)
    {
        $lo = self::u32($s, $o); $hi = self::u32($s, $o + 4);
        if ($hi > 0x7fffffff) throw new RuntimeException('The APK file is malformed.');
        return $hi * 4294967296 + $lo;
    }

    /** Locates the central directory via the End Of Central Directory record (ZIP64 aware). */
    private static function centralDirectory($fh, $size)
    {
        if ($size < 22) throw new RuntimeException('This file is too small to be an APK.');
        $tailLength = min($size, 65557);
        $tail = self::readAt($fh, $size - $tailLength, $tailLength);
        $pos = strrpos($tail, "PK\x05\x06");
        if ($pos === false) throw new RuntimeException('This file is not a ZIP/APK archive.');
        $eocdOffset = $size - $tailLength + $pos;
        $entries = self::u16($tail, $pos + 10);
        $cdSize = self::u32($tail, $pos + 12);
        $cdOffset = self::u32($tail, $pos + 16);
        if ($cdOffset === 0xffffffff || $cdSize === 0xffffffff || $entries === 0xffff) {
            // ZIP64: the locator sits 20 bytes before the EOCD record.
            $locator = self::readAt($fh, $eocdOffset - 20, 20);
            if (substr($locator, 0, 4) !== "PK\x06\x07") throw new RuntimeException('The APK file is malformed (ZIP64).');
            $z64 = self::readAt($fh, self::u64($locator, 8), 56);
            if (substr($z64, 0, 4) !== "PK\x06\x06") throw new RuntimeException('The APK file is malformed (ZIP64).');
            $entries = self::u64($z64, 32);
            $cdSize = self::u64($z64, 40);
            $cdOffset = self::u64($z64, 48);
        }
        if ($cdOffset + $cdSize > $size) throw new RuntimeException('The APK file is truncated.');
        return array('offset' => $cdOffset, 'size' => $cdSize, 'entries' => $entries);
    }

    /** Returns the uncompressed bytes of one entry, or null when it does not exist. */
    private static function readEntry($fh, $cd, $wanted)
    {
        $dir = self::readAt($fh, $cd['offset'], $cd['size']);
        $p = 0; $n = strlen($dir);
        while ($p + 46 <= $n && substr($dir, $p, 4) === "PK\x01\x02") {
            $method = self::u16($dir, $p + 10);
            $compSize = self::u32($dir, $p + 20);
            $size = self::u32($dir, $p + 24);
            $nameLen = self::u16($dir, $p + 28);
            $extraLen = self::u16($dir, $p + 30);
            $commentLen = self::u16($dir, $p + 32);
            $local = self::u32($dir, $p + 42);
            $name = substr($dir, $p + 46, $nameLen);
            if ($name === $wanted) {
                if ($size > self::MAX_MANIFEST_BYTES || $compSize > self::MAX_MANIFEST_BYTES) throw new RuntimeException('The manifest is unexpectedly large.');
                $header = self::readAt($fh, $local, 30);
                if (substr($header, 0, 4) !== "PK\x03\x04") throw new RuntimeException('The APK file is malformed.');
                $dataStart = $local + 30 + self::u16($header, 26) + self::u16($header, 28);
                $raw = self::readAt($fh, $dataStart, $compSize);
                if ($method === 0) return $raw;
                if ($method === 8) {
                    $data = @gzinflate($raw);
                    if ($data === false) throw new RuntimeException('The manifest could not be decompressed.');
                    return $data;
                }
                throw new RuntimeException('The manifest uses an unsupported compression method.');
            }
            $p += 46 + $nameLen + $extraLen + $commentLen;
        }
        return null;
    }

    /* ---------------- Binary AndroidManifest.xml ---------------- */

    /** @return array{package:string,version_code:int,version_name:string,min_sdk:?int} */
    public static function parseManifest($x)
    {
        $len = strlen($x);
        if ($len < 8 || self::u16($x, 0) !== 0x0003) throw new RuntimeException('AndroidManifest.xml is not a compiled Android manifest.');
        $pos = self::u16($x, 2);
        $strings = array(); $resIds = array();
        $package = null; $versionCode = null; $versionCodeMajor = 0; $versionName = null; $minSdk = null;
        $sawManifest = false;
        while ($pos + 8 <= $len) {
            $type = self::u16($x, $pos);
            $headerSize = self::u16($x, $pos + 2);
            $chunkSize = self::u32($x, $pos + 4);
            if ($chunkSize < 8 || $pos + $chunkSize > $len) break;
            if ($type === 0x0001) {
                $strings = self::stringPool($x, $pos);
            } elseif ($type === 0x0180) {
                $count = intdiv($chunkSize - $headerSize, 4);
                for ($i = 0; $i < $count; $i++) $resIds[$i] = self::u32($x, $pos + $headerSize + 4 * $i);
            } elseif ($type === 0x0102) {
                $ext = $pos + $headerSize;
                $element = self::str($strings, self::u32($x, $ext + 4));
                if ($element === 'manifest' || $element === 'uses-sdk') {
                    $attrStart = self::u16($x, $ext + 8);
                    $attrSize = self::u16($x, $ext + 10);
                    $attrCount = self::u16($x, $ext + 12);
                    for ($i = 0; $i < $attrCount; $i++) {
                        $a = $ext + $attrStart + $i * $attrSize;
                        $nameIndex = self::u32($x, $a + 4);
                        $raw = self::u32($x, $a + 8);
                        $dataType = ord($x[$a + 15]);
                        $data = self::u32($x, $a + 16);
                        $resId = isset($resIds[$nameIndex]) ? $resIds[$nameIndex] : 0;
                        $attr = self::str($strings, $nameIndex);
                        $string = $raw !== 0xffffffff ? self::str($strings, $raw) : ($dataType === 0x03 ? self::str($strings, $data) : null);
                        $int = ($dataType >= 0x10 && $dataType <= 0x1f) ? $data : ($string !== null && ctype_digit($string) ? (int)$string : null);
                        if ($element === 'manifest') {
                            if ($attr === 'package' && $resId === 0) $package = $string;
                            elseif ($resId === self::ATTR_VERSION_CODE || ($resId === 0 && $attr === 'versionCode')) $versionCode = $int;
                            elseif ($resId === self::ATTR_VERSION_CODE_MAJOR || ($resId === 0 && $attr === 'versionCodeMajor')) $versionCodeMajor = (int)$int;
                            elseif ($resId === self::ATTR_VERSION_NAME || ($resId === 0 && $attr === 'versionName')) $versionName = $string !== null ? $string : ($int !== null ? (string)$int : null);
                        } elseif ($resId === self::ATTR_MIN_SDK || ($resId === 0 && $attr === 'minSdkVersion')) {
                            if ($int !== null) $minSdk = $int;
                        }
                    }
                    if ($element === 'manifest') $sawManifest = true;
                }
            }
            $pos += $chunkSize;
        }
        if (!$sawManifest) throw new RuntimeException('AndroidManifest.xml has no <manifest> element.');
        if ($package === null || $package === '') throw new RuntimeException('The manifest has no package ID.');
        if ($versionCode === null) throw new RuntimeException('The manifest has no versionCode.');
        if ($versionCodeMajor > 0) $versionCode = $versionCodeMajor * 4294967296 + $versionCode;
        return array('package' => $package, 'version_code' => $versionCode,
            'version_name' => $versionName === null ? '' : $versionName, 'min_sdk' => $minSdk);
    }

    private static function str($strings, $index) { return isset($strings[$index]) ? $strings[$index] : null; }

    private static function stringPool($x, $p)
    {
        $headerSize = self::u16($x, $p + 2);
        $count = self::u32($x, $p + 8);
        $flags = self::u32($x, $p + 16);
        $base = $p + self::u32($x, $p + 20);
        $utf8 = ($flags & 0x100) !== 0;
        $out = array();
        for ($i = 0; $i < $count; $i++) {
            $o = $base + self::u32($x, $p + $headerSize + 4 * $i);
            if ($utf8) {
                self::len8($x, $o);                       // length in characters (unused)
                $bytes = self::len8($x, $o);
                $out[$i] = substr($x, $o, $bytes);
            } else {
                $n = self::u16($x, $o); $o += 2;
                if ($n & 0x8000) { $n = (($n & 0x7fff) << 16) | self::u16($x, $o); $o += 2; }
                $out[$i] = self::utf16($x, $o, $n);
            }
        }
        return $out;
    }

    private static function len8($x, &$o)
    {
        $b = ord($x[$o]);
        if ($b & 0x80) { $n = (($b & 0x7f) << 8) | ord($x[$o + 1]); $o += 2; return $n; }
        $o += 1; return $b;
    }

    /** UTF-16LE to UTF-8 without relying on mbstring or iconv. */
    private static function utf16($x, $o, $units)
    {
        $s = '';
        for ($i = 0; $i < $units; $i++) {
            $c = self::u16($x, $o + 2 * $i);
            if ($c >= 0xd800 && $c <= 0xdbff && $i + 1 < $units) {
                $d = self::u16($x, $o + 2 * ($i + 1));
                if ($d >= 0xdc00 && $d <= 0xdfff) { $c = 0x10000 + (($c - 0xd800) << 10) + ($d - 0xdc00); $i++; }
            }
            if ($c < 0x80) $s .= chr($c);
            elseif ($c < 0x800) $s .= chr(0xc0 | ($c >> 6)) . chr(0x80 | ($c & 0x3f));
            elseif ($c < 0x10000) $s .= chr(0xe0 | ($c >> 12)) . chr(0x80 | (($c >> 6) & 0x3f)) . chr(0x80 | ($c & 0x3f));
            else $s .= chr(0xf0 | ($c >> 18)) . chr(0x80 | (($c >> 12) & 0x3f)) . chr(0x80 | (($c >> 6) & 0x3f)) . chr(0x80 | ($c & 0x3f));
        }
        return $s;
    }

    /* ---------------- APK Signing Block (v2/v3) ---------------- */

    /** SHA-256 of the first signer's certificate, or null for an APK without a v2/v3 signature. */
    private static function signer($fh, $cdOffset)
    {
        if ($cdOffset < 32) return null;
        $footer = self::readAt($fh, $cdOffset - 24, 24);
        if (substr($footer, 8, 16) !== 'APK Sig Block 42') return null;
        $blockSize = self::u64($footer, 0);
        if ($blockSize < 24 || $blockSize > self::MAX_SIGNING_BLOCK_BYTES || $blockSize + 8 > $cdOffset) return null;
        $start = $cdOffset - $blockSize - 8;
        $block = self::readAt($fh, $start, $blockSize + 8);
        if (self::u64($block, 0) !== $blockSize) return null;
        $pairs = array(); $p = 8; $end = strlen($block) - 24;
        while ($p + 12 <= $end) {
            $length = self::u64($block, $p);
            if ($length < 4 || $p + 8 + $length > $end) break;
            $pairs[self::u32($block, $p + 8)] = substr($block, $p + 12, $length - 4);
            $p += 8 + $length;
        }
        foreach (array(self::SIG_V31 => 'v3.1', self::SIG_V3 => 'v3', self::SIG_V2 => 'v2') as $id => $scheme) {
            if (!isset($pairs[$id])) continue;
            $cert = self::firstCertificate($pairs[$id]);
            if ($cert !== null) return array('sha256' => hash('sha256', $cert), 'scheme' => $scheme);
        }
        return null;
    }

    /** signers → first signer → signed data → certificates → first certificate (DER). */
    private static function firstCertificate($value)
    {
        $signers = self::lengthPrefixed($value, 0);
        if ($signers === null) return null;
        $signer = self::lengthPrefixed($signers, 0);
        if ($signer === null) return null;
        $signedData = self::lengthPrefixed($signer, 0);
        if ($signedData === null) return null;
        $digests = self::lengthPrefixed($signedData, 0);
        if ($digests === null) return null;
        $certificates = self::lengthPrefixed($signedData, 4 + strlen($digests));
        if ($certificates === null) return null;
        $cert = self::lengthPrefixed($certificates, 0);
        return ($cert === null || $cert === '') ? null : $cert;
    }

    private static function lengthPrefixed($s, $o)
    {
        if ($o + 4 > strlen($s)) return null;
        $n = self::u32($s, $o);
        if ($o + 4 + $n > strlen($s)) return null;
        return substr($s, $o + 4, $n);
    }
}
