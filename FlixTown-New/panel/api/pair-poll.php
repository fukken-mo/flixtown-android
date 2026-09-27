<?php
require __DIR__ . '/bootstrap.php';
$input = postData();
$code = $input['code'] ?? '';
$verifier = $input['verifier'] ?? '';
if (!preg_match('/^[0-9]{8}$/D', $code) || !preg_match('/^[a-f0-9]{64}$/Di', $verifier)) response(['error' => 'Invalid request'], 400);
$db->beginTransaction();
$q = $db->prepare('SELECT id,device_hash,status,credentials FROM pairings WHERE code=? AND expires_at > UTC_TIMESTAMP() FOR UPDATE');
$q->execute([$code]);
$row = $q->fetch();
if (!$row || !hash_equals($row['device_hash'], hash('sha256', $verifier))) {
    $db->rollBack();
    response(['error' => 'Code expired or invalid'], 404);
}
if ($row['status'] === 'approved' && $row['credentials']) {
    $credentials = decryptCredentials($row['credentials'], $config['app_key']);
    $db->prepare("UPDATE pairings SET status='redeemed',credentials=NULL WHERE id=?")->execute([$row['id']]);
    $db->commit();
    response(['status' => 'approved', 'account' => $credentials]);
}
$db->commit();
response(['status' => $row['status']]);
