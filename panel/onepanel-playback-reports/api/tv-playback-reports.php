<?php
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');header('X-Content-Type-Options: nosniff');
function tvr_out(array $data,int $status=200): void {http_response_code($status);echo json_encode($data,JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE);exit;}
if(($_SERVER['REQUEST_METHOD']??'')!=='POST')tvr_out(['ok'=>false,'error'=>'method','message'=>'Use POST.'],405);
$raw=file_get_contents('php://input',false,null,0,8193);
if($raw===false||strlen($raw)>8192)tvr_out(['ok'=>false,'error'=>'invalid','message'=>'Invalid report.'],400);
$in=json_decode($raw,true);
if(!is_array($in)||!is_object(json_decode($raw)))tvr_out(['ok'=>false,'error'=>'invalid','message'=>'Send a JSON object.'],400);
require __DIR__.'/../includes/bootstrap.php';
require_once __DIR__.'/../includes/tv_requests.php';
require_once __DIR__.'/../includes/playback_reports.php';
if(session_status()===PHP_SESSION_ACTIVE)session_write_close();
try {
  $cfg=tvr_config();
  if(!$cfg['enabled']||strlen($cfg['secret'])<32)tvr_fail(503,'unavailable',TVR_UNAVAILABLE);
  $ip=(string)($_SERVER['REMOTE_ADDR']??'');
  if(!tvr_rate($pdo,'ip:'.$ip,600,900))tvr_fail(429,'busy','Please wait a few minutes.');
  $action=$in['action']??null;
  if($action==='session') {
    if(!tvr_rate($pdo,'login:'.$ip,20,900))tvr_fail(429,'busy','Please wait a few minutes.');
    $u=$in['username']??null;$pw=$in['password']??null;
    if(!tvr_valid_username($u)||!is_string($pw)||$pw===''||strlen($pw)>100)tvr_fail(401,'auth','Please sign in again.');
    $user=tvq_flix_login($u,$pw);
    if(!$user)tvr_fail(401,'auth','Please sign in again.');
    if(!tvq_account_active($user))tvr_fail(403,'inactive','Please sign in with an active Flix Town account.');
    tvr_out(['ok'=>true,'session'=>tvr_token($u),'session_ttl'=>TVR_TTL]);
  }
  $u=tvr_user($in['session']??null);
  if($u===null)tvr_fail(401,'session','Please sign in again.');
  if($action==='resolved')tvr_out(tvr_resolved($pdo,$u,$in));
  if($action!=='submit')tvr_fail(400,'invalid','Unknown action.');
  tvr_out(tvr_submit($pdo,$u,$in));
} catch(TvrError $e) {tvr_out(['ok'=>false,'error'=>$e->reason,'message'=>$e->getMessage()],$e->http);
} catch(Throwable $e) {error_log('tv-playback-reports failed: '.get_class($e));tvr_out(['ok'=>false,'error'=>'unavailable','message'=>TVR_UNAVAILABLE],503);}
