<?php
/* Playback reports only. Reuses Flix account validation, never calls Seerr or changes media. */
const TVR_UNAVAILABLE = 'Reports are temporarily unavailable. Please try again later.';
const TVR_TTL = 900;
function tvr_issues(): array {
  return ['stutter'=>'Video stutters or plays in slow motion','no_audio'=>'No sound',
    'audio_sync'=>'Audio out of sync','subtitles'=>'Subtitle problem',
    'wrong_title'=>'Wrong movie or episode','wont_play'=>"Video won't play"];
}
function tvr_config(): array {
  global $config;
  $r=$config['services']['playback_reports'] ?? [];
  return ['enabled'=>($r['enabled']??false)===true,
    'secret'=>(string)($r['session_secret']??($config['services']['seerr']['customer_session_secret']??''))];
}
class TvrError extends Exception {
  public $http; public $reason;
  function __construct(int $http,string $reason,string $message) {parent::__construct($message);$this->http=$http;$this->reason=$reason;}
}
function tvr_fail(int $http,string $reason,string $message): void {throw new TvrError($http,$reason,$message);}
function tvr_token(string $username,?int $now=null): string {
  $body=tvq_b64(json_encode(['u'=>$username,'p'=>'playback_reports','exp'=>($now??time())+TVR_TTL]));
  return $body.'.'.tvq_b64(hash_hmac('sha256',$body,tvr_config()['secret'],true));
}
function tvr_user($token,?int $now=null): ?string {
  if(!is_string($token)||strlen($token)>400||substr_count($token,'.')!==1)return null;
  [$body,$sig]=explode('.',$token);
  if(!hash_equals(tvq_b64(hash_hmac('sha256',$body,tvr_config()['secret'],true)),$sig))return null;
  $d=json_decode((string)tvq_unb64($body),true);
  if(!is_array($d)||($d['p']??'')!=='playback_reports'||!is_int($d['exp']??null)||$d['exp']<=($now??time()))return null;
  return tvq_valid_username($d['u']??null)?$d['u']:null;
}
function tvr_int(array $in,string $key,int $min,int $max): int {
  $v=$in[$key]??null;
  if(!is_int($v)||$v<$min||$v>$max)tvr_fail(400,'invalid','Invalid report details.');
  return $v;
}
/* Only bounded display text, never free text, URLs, headers or raw player errors. */
function tvr_text($v,int $max): string {
  if(!is_string($v)||strlen($v)>$max*4||!preg_match('//u',$v)||preg_match('/[\x00-\x1F\x7F]|https?:\/\/|(?:password|token|session|username)\s*[=:]/i',$v))return '';
  return function_exists('mb_substr')?mb_substr(trim($v),0,$max):substr(trim($v),0,$max);
}
function tvr_validate(array $in): array {
  $type=$in['type']??null;$issue=$in['issue']??null;
  if(!is_string($type)||!in_array($type,['movie','series'],true)||!is_string($issue)||!isset(tvr_issues()[$issue]))tvr_fail(400,'invalid','Choose a valid problem.');
  $id=tvr_int($in,'media_id',1,2147483647);
  $ep=tvr_int($in,'episode_id',0,2147483647);
  if(($type==='movie'&&$ep!==0)||($type==='series'&&$ep===0))tvr_fail(400,'invalid','Invalid episode.');
  $rid=$in['request_id']??null;
  if(!is_string($rid)||!preg_match('/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/D',$rid))tvr_fail(400,'invalid','Invalid report.');
  $out=['type'=>$type,'media_id'=>$id,'episode_id'=>$ep,'issue'=>$issue,'request_id'=>$rid,
    'position_ms'=>tvr_int($in,'position_ms',0,604800000),
    'version_code'=>tvr_int($in,'version_code',1,2147483647),
    'android_api'=>tvr_int($in,'android_api',23,100)];
  foreach(['version_name'=>40,'device'=>120,'video_format'=>80,'audio_format'=>80,'error_code'=>80] as $k=>$n)$out[$k]=tvr_text($in[$k]??'',$n);
  return $out; // Any unknown keys (including URLs, credentials and user-supplied titles) are dropped.
}
function tvr_rate(PDO $db,string $key,int $max,int $window): bool {
  $now=time();$old=$now-$window;$key=hash('sha256',$key);
  $q=$db->prepare('INSERT INTO tv_playback_report_rate(k,window_start,hits) VALUES(?,?,1) ON DUPLICATE KEY UPDATE hits=LAST_INSERT_ID(IF(window_start<=?,1,hits+1)),window_start=IF(window_start<=?,?,window_start)');
  $q->execute([$key,$now,$old,$old,$now]);
  if($q->rowCount()===1)return true;
  return (int)$db->query('SELECT LAST_INSERT_ID()')->fetchColumn()<=$max;
}
function tvr_require_active(string $username): void {
  $r=flix_api_request('GET','/api/onepanel/users/'.rawurlencode($username));
  if(!$r['ok'])tvr_fail(503,'unavailable',TVR_UNAVAILABLE);
  $d=$r['data'];$u=is_array($d['user']??null)?$d['user']:$d;
  if(!is_array($u)||($u['username']??'')!==$username||!tvq_account_active($u))tvr_fail(403,'inactive','Please sign in with an active Flix Town account.');
}
/* Resolve server-owned names and episode numbers through the customer's own catalog scope. */
function tvr_media(string $username,array $r): array {
  $line=tvq_flix_line($username);
  if($r['type']==='movie') {
    $d=tvq_player($line,['action'=>'get_vod_info','vod_id'=>$r['media_id']]);
    $m=$d['movie_data']??[];
    if(!is_array($m)||(string)($m['stream_id']??'')!==(string)$r['media_id'])tvr_fail(404,'media','This title is no longer available.');
    return ['title'=>tvr_text($m['name']??'',255)?:'Movie #'.$r['media_id'],'episode_title'=>'','season'=>0,'episode'=>0];
  }
  $d=tvq_player($line,['action'=>'get_series_info','series_id'=>$r['media_id']]);
  $seasons=$d['episodes']??[];
  if(!is_array($seasons))tvr_fail(503,'unavailable',TVR_UNAVAILABLE);
  foreach($seasons as $season=>$episodes) {
    if(!is_array($episodes))continue;
    foreach($episodes as $ep)if(is_array($ep)&&(string)($ep['id']??'')===(string)$r['episode_id']) {
      return ['title'=>tvr_text($d['info']['name']??'',255)?:'Series #'.$r['media_id'],
        'episode_title'=>tvr_text($ep['title']??'',255),
        'season'=>max(0,min(10000,(int)($ep['season']??$season))),
        'episode'=>max(0,min(100000,(int)($ep['episode_num']??0)))];
    }
  }
  tvr_fail(404,'media','This episode is no longer available.');return [];
}
function tvr_submit(PDO $db,string $username,array $in): array {
  $r=tvr_validate($in);
  $key=hash('sha256',$username.'|'.$r['type'].'|'.$r['media_id'].'|'.$r['episode_id'].'|'.$r['issue']);
  $lock='tvr:'.substr($key,0,40);
  $q=$db->prepare('SELECT GET_LOCK(?, 5)');$q->execute([$lock]);$got=$q->fetchColumn();$q->closeCursor();
  if((int)$got!==1)tvr_fail(429,'busy','Please try again in a moment.');
  try {
    // A request ID is idempotent for this account, including after a network timeout/retry.
    $requestKey=hash('sha256',$username.'|'.$r['request_id']);
    $q=$db->prepare('SELECT id FROM tv_playback_reports WHERE request_key=? OR (dedupe_key=? AND created_at>=DATE_SUB(UTC_TIMESTAMP(), INTERVAL 24 HOUR)) LIMIT 1');
    $q->execute([$requestKey,$key]);
    if($q->fetchColumn())return ['ok'=>true,'duplicate'=>true,'message'=>'You already reported this problem. Thank you.'];
    if(!tvr_rate($db,'submit:'.$username,10,3600))tvr_fail(429,'busy','You have sent several reports. Please try again later.');
    tvr_require_active($username);
    $m=tvr_media($username,$r);
    $q=$db->prepare('INSERT INTO tv_playback_reports(request_key,dedupe_key,username,media_type,media_id,episode_id,title,episode_title,season_number,episode_number,issue,position_ms,version_code,version_name,android_api,device,video_format,audio_format,error_code,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP())');
    $q->execute([$requestKey,$key,$username,$r['type'],$r['media_id'],$r['episode_id'],$m['title'],$m['episode_title'],$m['season'],$m['episode'],$r['issue'],$r['position_ms'],$r['version_code'],$r['version_name'],$r['android_api'],$r['device'],$r['video_format'],$r['audio_format'],$r['error_code']]);
    return ['ok'=>true,'duplicate'=>false,'message'=>'Report sent. Thank you for letting us know.'];
  } finally {try{$q=$db->prepare('SELECT RELEASE_LOCK(?)');$q->execute([$lock]);$q->closeCursor();}catch(Throwable $e){}}
}
