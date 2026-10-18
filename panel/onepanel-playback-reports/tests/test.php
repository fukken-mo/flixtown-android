<?php
require __DIR__.'/bootstrap.php';
require __DIR__.'/../../onepanel-tv-requests/includes/tv_requests.php';
require __DIR__.'/../includes/playback_reports.php';
function uuid4(): string{$v=random_bytes(16);$v[6]=chr((ord($v[6])&15)|64);$v[8]=chr((ord($v[8])&63)|128);$h=bin2hex($v);return substr($h,0,8).'-'.substr($h,8,4).'-'.substr($h,12,4).'-'.substr($h,16,4).'-'.substr($h,20);}
function payload(): array {return ['type'=>'movie','media_id'=>101,'episode_id'=>0,'issue'=>'stutter','position_ms'=>12345,'version_code'=>10034,'version_name'=>'test','android_api'=>31,'device'=>'Fixture TV','video_format'=>'video/avc 1920x1080','audio_format'=>'audio/ac3 6ch','error_code'=>'','request_id'=>uuid4()];}
if(($argv[1]??'')==='worker') {while(microtime(true)<(float)getenv('REPORTS_RACE_START'))usleep(1000);echo json_encode(tvr_submit($pdo,'race-user',payload()));exit;}
$pass=0;
function check($v,$label): void {global $pass;if(!$v)throw new RuntimeException('FAIL '.$label);$pass++;echo 'PASS '.$label."\n";}
function rejects(callable $fn,int $status,string $label): void{try{$fn();}catch(TvrError $e){check($e->http===$status,$label);return;}throw new RuntimeException('FAIL '.$label);}
$sql=file_get_contents(__DIR__.'/../sql/migrations/2026-10-18-playback-reports.sql');
$pdo->exec($sql);$pdo->exec($sql);
$pdo->exec('DELETE FROM tv_playback_reports');$pdo->exec('DELETE FROM tv_playback_report_rate');
check(true,'migration re-runs without error');
$token=tvr_token('alice',1000);check(tvr_user($token,1001)==='alice','valid session');check(tvr_user($token,1900)===null,'expired session');
check(tvr_user($token.'x',1001)===null,'tampered session');check(tvr_user(tvq_session_issue('alice',1000),1001)===null,'request session cannot authorize reports');
check(tvq_session_user($token,1001)===null,'report session cannot authorize requests');check(tvr_user(['x'])===null,'array session refused');
check(!tvr_valid_username("alice\n"),'username trailing newline rejected');
check(strlen(tvr_text(str_repeat('é',200),120))===240,'Unicode truncation stays valid UTF-8');
check(tvq_flix_login('alice','bad')===null,'wrong credentials rejected');check(tvq_flix_login('alice','fixture-password')!==null,'existing Flix identity works');
$r=payload();$r['url']='https://fixture.invalid/secret';$r['username']='victim';$r['password']='secret';$r['title']='Forged';
$clean=tvr_validate($r);check(!array_intersect(['url','username','password','title'],array_keys($clean)),'untrusted fields stripped');
foreach(['type'=>'live','media_id'=>0,'episode_id'=>4,'issue'=>'hack','position_ms'=>-1,'version_code'=>'10034','android_api'=>500,'request_id'=>'abc'] as $key=>$value){$p=payload();$p[$key]=$value;rejects(function()use($p){tvr_validate($p);},400,'reject invalid '.$key);}
check(tvr_text('https://host/username/password',80)==='','URL rejected');check(tvr_text('token=secret',80)==='','credential text rejected');check(tvr_text("bad\ntext",80)==='','control characters rejected');check(tvr_text('Tést TV',120)==='Tést TV','Unicode device text allowed');
$p=payload();$m=tvr_media('alice',$p);check($m['title']==='Fixture Movie <script>','title resolved from catalog');
$p['media_id']=999;rejects(function()use($p){tvr_media('alice',$p);},404,'unavailable movie refused');
$p=payload();$p['type']='series';$p['media_id']=201;$p['episode_id']=5001;$m=tvr_media('alice',$p);check($m['season']===1&&$m['episode']===1,'episode metadata resolved');
$p['episode_id']=5002;rejects(function()use($p){tvr_media('alice',$p);},404,'episode outside customer catalog refused');
rejects(function(){tvr_require_active('disabled');},403,'disabled account refused');rejects(function(){tvr_require_active('outage');},503,'account lookup outage fails closed');
$p=payload();$a=tvr_submit($pdo,'alice',$p);check($a['ok']&&!$a['duplicate'],'report stored');
check(tvr_submit($pdo,'alice',$p)['duplicate'],'same request id is idempotent');
check(tvr_submit($pdo,'alice',payload())['duplicate'],'same issue within 24 hours deduplicated');
check(!tvr_submit($pdo,'bob',payload())['duplicate'],'another customer can report same title');
$rows=$pdo->query('SELECT * FROM tv_playback_reports ORDER BY id')->fetchAll();check(count($rows)===2&&$rows[0]['username']==='alice'&&$rows[1]['username']==='bob','authenticated identity stored, no forged owner');
check(!isset($rows[0]['password'],$rows[0]['url'],$rows[0]['session']),'schema stores no credentials or URLs');
$pdo->exec("UPDATE tv_playback_reports SET created_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 25 HOUR) WHERE username='alice'");
check(!tvr_submit($pdo,'alice',payload())['duplicate'],'new report permitted after 24 hours');
$allowed=0;for($i=0;$i<15;$i++)if(tvr_rate($pdo,'test-rate',10,3600))$allowed++;
check($allowed===10,'rate limit enforced exactly');
// Concurrent submissions use separate PHP processes/connections against the same DB.
putenv('REPORTS_RACE_START='.(microtime(true)+1));
$procs=[];for($i=0;$i<4;$i++){$pipes=[];$proc=proc_open([PHP_BINARY,__FILE__,'worker'],[1=>['pipe','w'],2=>['pipe','w']],$pipes);$procs[]=[$proc,$pipes];}
$created=0;$duplicate=0;
foreach($procs as [$proc,$pipes]){$out=stream_get_contents($pipes[1]);$err=stream_get_contents($pipes[2]);fclose($pipes[1]);fclose($pipes[2]);$exit=proc_close($proc);check($exit===0,'concurrent worker completed '.$err);$d=json_decode($out,true);if(!empty($d['duplicate']))$duplicate++;elseif(!empty($d['ok']))$created++;}
check($created===1&&$duplicate===3,'concurrent same-customer submissions create exactly one row');
check((int)$pdo->query("SELECT COUNT(*) FROM tv_playback_reports WHERE username='race-user'")->fetchColumn()===1,'database confirms one race report');
echo "Backend reports: $pass passed, 0 failed\n";
