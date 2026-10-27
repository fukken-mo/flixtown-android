<?php
header('Content-Type: application/json; charset=utf-8');header('Cache-Control: no-store');header('X-Content-Type-Options: nosniff');
function fxs_out(array $d,int $s=200): void {http_response_code($s);echo json_encode($d,JSON_UNESCAPED_SLASHES);exit;}
if(($_SERVER['REQUEST_METHOD']??'')!=='POST')fxs_out(['ok'=>false,'error'=>'method'],405);
$raw=file_get_contents('php://input',false,null,0,65537);
if($raw===false||strlen($raw)>65536)fxs_out(['ok'=>false,'error'=>'invalid'],400);
$in=json_decode($raw,true);if(!is_array($in)||!is_object(json_decode($raw)))fxs_out(['ok'=>false,'error'=>'invalid'],400);
require __DIR__.'/../includes/bootstrap.php';require_once __DIR__.'/../includes/tv_requests.php';require_once __DIR__.'/../includes/account_sync.php';
if(session_status()===PHP_SESSION_ACTIVE)session_write_close();
try {
 if(($config['services']['progress_sync']['enabled']??false)!==true||strlen(fxs_secret())<32)fxs_fail(503,'unavailable');
 // Many customers can share one public address (carrier NAT, a proxy): the per-address limit is only a flood
 // ceiling. Sessions are limited per account; failed sign-ins per address and per username.
 $ip=(string)($_SERVER['REMOTE_ADDR']??'');if(!fxs_rate($pdo,'ip:'.$ip,6000,900))fxs_fail(429,'busy');
 if(($in['action']??'')==='session'){
  $u=$in['username']??null;$pw=$in['password']??null;$name=is_string($u)?substr($u,0,100):'';
  if(!fxs_rate_open($pdo,'fail-ip:'.$ip,30,900)||!fxs_rate_open($pdo,'fail-user:'.$name,100,900))fxs_fail(429,'busy');
  if(!fxs_username($u)||!is_string($pw)||$pw===''||strlen($pw)>100){fxs_rate($pdo,'fail-ip:'.$ip,30,900);fxs_fail(401,'auth');}
  $user=tvq_flix_login($u,$pw);
  if(!$user){fxs_rate($pdo,'fail-ip:'.$ip,30,900);fxs_rate($pdo,'fail-user:'.$u,100,900);fxs_fail(401,'auth');}
  if(!tvq_account_active($user))fxs_fail(403,'inactive');
  if(!fxs_rate($pdo,'session:'.$u,20,900))fxs_fail(429,'busy');
  fxs_out(['ok'=>true,'session'=>fxs_token($u,time()),'ttl'=>900]);
 }
 $u=fxs_user($in['session']??null,time());if($u===null)fxs_fail(401,'session');
 if(($in['action']??'')!=='exchange')fxs_fail(400,'invalid');
 if(!fxs_rate($pdo,'user:'.$u,180,900))fxs_fail(429,'busy');
 fxs_active($u);fxs_out(fxs_exchange($pdo,$u,$in));
}catch(FlixSyncError $e){fxs_out(['ok'=>false,'error'=>$e->reason],$e->status);
}catch(Throwable $e){error_log('account-sync failed: '.get_class($e));fxs_out(['ok'=>false,'error'=>'unavailable'],503);}
