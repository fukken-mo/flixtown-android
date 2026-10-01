<?php
require_once __DIR__ . '/ApkInspector.php';

/**
 * Stores the update slots (main app, optional test build), downloads and inspects APKs and
 * decides when a stored result is stale.
 *
 * State lives in data/state.json, written atomically; inspections take a per-slot lock so two
 * requests never download the same APK at once.
 */
final class FlixUpdateStore
{
    const SLOTS = array('main' => 'Main app', 'test' => 'Test build');
    private $settings;
    private $dataDir;

    public function __construct(array $settings, $dataDir = null)
    {
        $this->settings = $settings;
        $this->dataDir = $dataDir ?: __DIR__ . '/data';
        if (!is_dir($this->dataDir)) @mkdir($this->dataDir, 0750, true);
    }

    public function setting($key, $default = null)
    {
        return array_key_exists($key, $this->settings) ? $this->settings[$key] : $default;
    }

    /* ---------------- State ---------------- */

    public function state()
    {
        $file = $this->dataDir . '/state.json';
        $state = is_file($file) ? json_decode((string)file_get_contents($file), true) : null;
        if (!is_array($state)) $state = array();
        if (!isset($state['slots']) || !is_array($state['slots'])) $state['slots'] = array();
        foreach (self::SLOTS as $id => $label) {
            $slot = isset($state['slots'][$id]) && is_array($state['slots'][$id]) ? $state['slots'][$id] : array();
            $state['slots'][$id] = array_merge(array('url' => '', 'notes' => '', 'required' => false,
                'detected' => null, 'previous' => null, 'error' => null, 'validators' => null,
                'checked_at' => 0, 'inspected_at' => 0), $slot);
        }
        return $state;
    }

    public function slot($id) { $s = $this->state(); return $s['slots'][$id]; }

    private function saveSlot($id, array $slot)
    {
        $lock = fopen($this->dataDir . '/state.lock', 'c');
        if ($lock) flock($lock, LOCK_EX);
        try {
            $state = $this->state();
            $state['slots'][$id] = $slot;
            $tmp = $this->dataDir . '/state.' . getmypid() . '.tmp';
            file_put_contents($tmp, json_encode($state, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
            rename($tmp, $this->dataDir . '/state.json');
        } finally {
            if ($lock) { flock($lock, LOCK_UN); fclose($lock); }
        }
    }

    /** Saves the URL, notes and "required" flag; a changed URL clears the old detection. */
    public function configure($id, $url, $notes, $required)
    {
        $slot = $this->slot($id);
        $url = trim($url);
        if ($url !== '' && !preg_match('#^https?://[^\s]+$#i', $url)) throw new InvalidArgumentException('Enter a full http:// or https:// link to the APK.');
        if ($url !== $slot['url']) {
            if ($slot['detected']) $slot['previous'] = $slot['detected'];
            $slot['detected'] = null; $slot['validators'] = null; $slot['error'] = null;
            $slot['checked_at'] = 0; $slot['inspected_at'] = 0;
        }
        $slot['url'] = $url;
        $slot['notes'] = trim(str_replace("\r", '', $notes));
        $slot['required'] = (bool)$required;
        $this->saveSlot($id, $slot);
        return $slot;
    }

    /* ---------------- Inspection ---------------- */

    /**
     * Downloads the APK at the slot URL (never from a cache) and reads its manifest and signature.
     * On failure the previous good result is kept for reference but marked as not current.
     */
    public function inspectSlot($id)
    {
        $slot = $this->slot($id);
        if ($slot['url'] === '') throw new InvalidArgumentException('Enter the APK link first.');
        $lock = fopen($this->dataDir . '/inspect-' . $id . '.lock', 'c');
        if ($lock) flock($lock, LOCK_EX);
        $tmp = $this->dataDir . '/download-' . $id . '-' . getmypid() . '.apk';
        try {
            $response = $this->download($slot['url'], $tmp);
            $info = FlixApkInspector::inspect($tmp);
            $slot = $this->slot($id);                    // re-read: another save may have happened
            if ($slot['detected'] && $slot['detected']['sha256'] !== $info['sha256']) $slot['previous'] = $slot['detected'];
            $info['url'] = $slot['url'];
            $slot['detected'] = $info;
            $slot['validators'] = $response['validators'];
            $slot['error'] = null;
            $slot['checked_at'] = $slot['inspected_at'] = time();
            $this->saveSlot($id, $slot);
            return $slot;
        } catch (Exception $e) {
            $slot = $this->slot($id);
            $slot['error'] = $e->getMessage();
            $slot['checked_at'] = time();
            $this->saveSlot($id, $slot);
            throw $e;
        } finally {
            if (is_file($tmp)) @unlink($tmp);
            if ($lock) { flock($lock, LOCK_UN); fclose($lock); }
        }
    }

    /**
     * Makes sure the stored result still describes the file at the URL. Cheap when nothing
     * changed: one HEAD request at most every recheck_minutes. A replaced file (new ETag,
     * Last-Modified or size), or a server that sends no validators, triggers a full inspection.
     * Never blocks: if another request is already inspecting, the stored result is used.
     */
    public function revalidate($id)
    {
        $slot = $this->slot($id);
        if ($slot['url'] === '') return $slot;
        $interval = max(0, (int)$this->setting('recheck_minutes', 10)) * 60;
        $age = time() - (int)$slot['checked_at'];
        if ($slot['detected'] && !$slot['error'] && $age < $interval) return $slot;
        if ($slot['error'] && $age < min($interval, 60)) return $slot;     // a broken link is retried at most once a minute
        $lock = fopen($this->dataDir . '/inspect-' . $id . '.lock', 'c');
        if (!$lock || !flock($lock, LOCK_EX | LOCK_NB)) { if ($lock) fclose($lock); return $slot; }
        flock($lock, LOCK_UN); fclose($lock);
        try {
            if ($slot['detected'] && !$slot['error']) {
                $head = $this->head($slot['url']);
                $old = $slot['validators'] ?: array();
                $hasValidators = $head['etag'] !== '' || $head['last_modified'] !== '';
                $same = $hasValidators && $head['etag'] === (isset($old['etag']) ? $old['etag'] : '')
                    && $head['last_modified'] === (isset($old['last_modified']) ? $old['last_modified'] : '')
                    && $head['length'] === (isset($old['length']) ? $old['length'] : -1);
                if ($same) { $slot['checked_at'] = time(); $this->saveSlot($id, $slot); return $slot; }
            }
            return $this->inspectSlot($id);
        } catch (Exception $e) {
            return $this->slot($id);
        }
    }

    /* ---------------- HTTP ---------------- */

    private static function noCacheHeaders()
    {
        return array('Cache-Control: no-cache, no-store, max-age=0', 'Pragma: no-cache', 'User-Agent: FlixTown-Panel-Update/1.0');
    }

    private function bustedUrl($url)
    {
        return $url . (strpos($url, '?') === false ? '?' : '&') . '_ft=' . time() . mt_rand(1000, 9999);
    }

    /** @return array{validators:array} */
    private function download($url, $target)
    {
        $attempts = $this->setting('cache_bust', true) ? array($this->bustedUrl($url), $url) : array($url);
        $last = null;
        foreach ($attempts as $i => $attempt) {
            try { return $this->fetch($attempt, $target); }
            catch (FlixHttpStatus $e) {
                $last = $e;
                // Only a rejected cache-busting parameter is worth retrying without it.
                if ($i === 0 && count($attempts) > 1 && $e->status >= 400 && $e->status < 500) continue;
                throw new RuntimeException($e->getMessage());
            }
        }
        throw new RuntimeException($last ? $last->getMessage() : 'The APK could not be downloaded.');
    }

    private function fetch($url, $target)
    {
        $max = (int)$this->setting('max_apk_mb', 200) * 1048576;
        $out = fopen($target, 'wb');
        if (!$out) throw new RuntimeException('The panel could not write a temporary file (check that flixtown-update/data is writable).');
        $headers = array();
        try {
            if (function_exists('curl_init')) {
                $ch = curl_init($url);
                curl_setopt_array($ch, array(
                    CURLOPT_FILE => $out, CURLOPT_FOLLOWLOCATION => true, CURLOPT_MAXREDIRS => 5,
                    CURLOPT_CONNECTTIMEOUT => 15, CURLOPT_TIMEOUT => 180, CURLOPT_HTTPHEADER => self::noCacheHeaders(),
                    CURLOPT_FRESH_CONNECT => true, CURLOPT_FAILONERROR => false,
                    CURLOPT_HEADERFUNCTION => function ($ch, $line) use (&$headers) { self::collectHeader($headers, $line); return strlen($line); },
                    CURLOPT_NOPROGRESS => false,
                    CURLOPT_PROGRESSFUNCTION => function ($ch, $dlTotal, $dlNow) use ($max) { return ($dlTotal > $max || $dlNow > $max) ? 1 : 0; },
                ));
                if (defined('CURLOPT_PROTOCOLS')) curl_setopt($ch, CURLOPT_PROTOCOLS, CURLPROTO_HTTP | CURLPROTO_HTTPS);
                if (defined('CURLOPT_REDIR_PROTOCOLS')) curl_setopt($ch, CURLOPT_REDIR_PROTOCOLS, CURLPROTO_HTTP | CURLPROTO_HTTPS);
                $ok = curl_exec($ch);
                $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
                $error = curl_error($ch);
                curl_close($ch);
                if ($ok === false) throw new RuntimeException('The APK could not be downloaded: ' . ($error ?: 'network error') . '.');
            } else {
                $context = stream_context_create(array('http' => array('method' => 'GET', 'header' => implode("\r\n", self::noCacheHeaders()),
                    'follow_location' => 1, 'max_redirects' => 6, 'timeout' => 60, 'ignore_errors' => true)));
                $in = @fopen($url, 'rb', false, $context);
                if (!$in) throw new RuntimeException('The APK could not be downloaded (network error).');
                foreach (isset($http_response_header) ? $http_response_header : array() as $line) self::collectHeader($headers, $line);
                $copied = stream_copy_to_stream($in, $out, $max + 1);
                fclose($in);
                if ($copied > $max) throw new RuntimeException('The APK is larger than ' . (int)$this->setting('max_apk_mb', 200) . ' MB.');
                $status = isset($headers['status']) ? $headers['status'] : 0;
            }
        } finally {
            fclose($out);
        }
        if ($status !== 200) throw new FlixHttpStatus($status, 'The APK link returned HTTP ' . $status . '.');
        if (filesize($target) > $max) throw new RuntimeException('The APK is larger than ' . (int)$this->setting('max_apk_mb', 200) . ' MB.');
        return array('validators' => self::validators($headers));
    }

    /** @return array{etag:string,last_modified:string,length:int} */
    private function head($url)
    {
        $headers = array();
        if (function_exists('curl_init')) {
            $ch = curl_init($url);
            curl_setopt_array($ch, array(CURLOPT_NOBODY => true, CURLOPT_FOLLOWLOCATION => true, CURLOPT_MAXREDIRS => 5,
                CURLOPT_CONNECTTIMEOUT => 6, CURLOPT_TIMEOUT => 10, CURLOPT_HTTPHEADER => self::noCacheHeaders(), CURLOPT_RETURNTRANSFER => true,
                CURLOPT_HEADERFUNCTION => function ($ch, $line) use (&$headers) { self::collectHeader($headers, $line); return strlen($line); }));
            $ok = curl_exec($ch);
            curl_close($ch);
            if ($ok === false) throw new RuntimeException('HEAD failed');
        } else {
            $context = stream_context_create(array('http' => array('method' => 'HEAD', 'header' => implode("\r\n", self::noCacheHeaders()),
                'follow_location' => 1, 'max_redirects' => 6, 'timeout' => 10, 'ignore_errors' => true)));
            $lines = @get_headers($url, 0, $context);
            if (!$lines) throw new RuntimeException('HEAD failed');
            foreach ($lines as $line) self::collectHeader($headers, $line);
        }
        if ((isset($headers['status']) ? $headers['status'] : 0) !== 200) throw new RuntimeException('HEAD status');
        return self::validators($headers);
    }

    /** Keeps only the final response's headers when redirects are followed. */
    private static function collectHeader(array &$headers, $line)
    {
        $line = trim($line);
        if (preg_match('#^HTTP/\S+\s+(\d{3})#i', $line, $m)) { $headers = array('status' => (int)$m[1]); return; }
        $p = strpos($line, ':');
        if ($p !== false) $headers[strtolower(trim(substr($line, 0, $p)))] = trim(substr($line, $p + 1));
    }

    private static function validators(array $headers)
    {
        return array('etag' => isset($headers['etag']) ? $headers['etag'] : '',
            'last_modified' => isset($headers['last-modified']) ? $headers['last-modified'] : '',
            'length' => isset($headers['content-length']) ? (int)$headers['content-length'] : -1);
    }

    /* ---------------- API ---------------- */

    /** The slot whose APK is for this package; the main slot when the package is not given. */
    public function slotFor($package)
    {
        $state = $this->state();
        if ($package === null || $package === '') return array('main', $state['slots']['main']);
        foreach ($state['slots'] as $id => $slot) {
            if ($slot['detected'] && $slot['detected']['package'] === $package) return array($id, $slot);
        }
        // Not detected yet (first request after saving): try slots whose URL was never inspected.
        foreach ($state['slots'] as $id => $slot) {
            if ($slot['url'] !== '' && !$slot['detected'] && !$slot['error']) return array($id, $slot);
        }
        return array(null, null);
    }

    /**
     * Response for app-update.php. Field names match the update fields the app already read from
     * config.php, plus the checksum, version name, package and signing certificate.
     */
    public function apiResponse($package)
    {
        list($id, $slot) = $this->slotFor($package);
        if ($id === null) {
            return array('ok' => true, 'package' => (string)$package, 'update_version_code' => 0,
                'message' => 'No update is published for this app.');
        }
        $slot = $this->revalidate($id);
        $d = $slot['detected'];
        if ($slot['error'] || !$d) {
            return array('ok' => false, 'error' => 'The panel could not read the current APK: ' . rtrim($slot['error'] ?: 'not inspected yet', '.') . '.');
        }
        if ($package !== null && $package !== '' && $d['package'] !== $package) {
            return array('ok' => true, 'package' => (string)$package, 'update_version_code' => 0,
                'message' => 'No update is published for this app.');
        }
        return array(
            'ok' => true,
            'package' => $d['package'],
            'update_version_code' => $d['version_code'],
            'update_version_name' => $d['version_name'],
            'update_apk_url' => $slot['url'],
            'update_sha256' => $d['sha256'],
            'update_size' => $d['size'],
            'update_signer_sha256' => $d['signer_sha256'],
            'update_notes' => $slot['notes'],
            'update_required' => (bool)$slot['required'],
            'update_min_sdk' => $d['min_sdk'],
            'checked_at' => gmdate('c', (int)$slot['checked_at']),
        );
    }
}

final class FlixHttpStatus extends RuntimeException
{
    public $status;
    public function __construct($status, $message) { parent::__construct($message); $this->status = $status; }
}
