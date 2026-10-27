<?php
$dsn=getenv('SYNC_TEST_DSN')?:'';
if(!preg_match('/dbname=[a-zA-Z0-9_]+_test(?:;|$)/',$dsn))throw new RuntimeException('Disposable *_test database required');
$pdo=new PDO($dsn,getenv('SYNC_TEST_USER')?:'root',getenv('SYNC_TEST_PASSWORD')?:'',[
 PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC,PDO::ATTR_EMULATE_PREPARES=>false]);
$config=['services'=>['progress_sync'=>['enabled'=>true,'session_secret'=>str_repeat('fixture-only',4)]]];
require __DIR__.'/../includes/account_sync.php';
function tvq_account_active(array $u): bool{return $u['enabled'];}
function flix_api_request($m,$p): array{$u=rawurldecode(basename($p));return ['ok'=>$u!=='outage','data'=>['username'=>$u,'enabled'=>$u!=='disabled']];}
function operation(string $key,int $base=0,bool $present=true): array {
 $h=bin2hex(random_bytes(16));$id=substr($h,0,8).'-'.substr($h,8,4).'-4'.substr($h,13,3).'-8'.substr($h,17,3).'-'.substr($h,20);
 $v=['present'=>$present];if(strpos($key,'resume:')===0&&$present)$v+=['position'=>20000,'duration'=>90000,'episode'=>strpos($key,':series:')!==false?'10':'','next'=>'','extension'=>'mkv','next_ext'=>'mp4','label'=>'S1 E1','done'=>false];
 return ['key'=>$key,'base'=>$base,'operation'=>$id,'value'=>$v];
}
if(($argv[1]??'')==='worker'){while(microtime(true)<(float)getenv('SYNC_RACE_START'))usleep(1000);echo json_encode(fxs_exchange($pdo,'race',['operations'=>[operation('resume:movie:1')]]));exit;}
$n=0;function check($v,$s): void {global $n;if(!$v)throw new RuntimeException('FAIL '.$s);echo 'PASS '.$s."\n";$n++;}
function reject(callable $fn,int $status,string $s): void {try{$fn();}catch(FlixSyncError $e){check($e->status===$status,$s);return;}throw new RuntimeException('FAIL '.$s);}
$sql=file_get_contents(__DIR__.'/../sql/account-sync.sql');$pdo->exec($sql);$pdo->exec($sql);
foreach(['tv_sync_records','tv_sync_accounts','tv_sync_rate'] as $t)$pdo->exec('DELETE FROM '.$t);
check(true,'additive migration runs twice');
$t=fxs_token('alice',1000);check(fxs_user($t,1001)==='alice','token identity');check(fxs_user($t,1900)===null,'expiry');check(fxs_user($t.'x',1001)===null,'tamper');check(fxs_user([],1001)===null,'invalid token type');
$parts=explode('.',$t);$wrong=fxs_b64(json_encode(['u'=>'alice','purpose'=>'playback_reports','exp'=>1900]));check(fxs_user($wrong.'.'.fxs_b64(hash_hmac('sha256',$wrong,fxs_secret(),true)),1001)===null,'purpose isolation');
reject(function(){fxs_active('disabled');},403,'disabled rejected');reject(function(){fxs_active('outage');},503,'lookup outage rejected');
$p=operation('resume:series:9');$p['value']['url']='https://secret';$p['username']='victim';$v=fxs_validate($p);check(!isset($v['username'])&&!isset($v['value']['url']),'unknown private data stripped');
foreach(['key'=>'resume:movie:https://bad','base'=>-1,'operation'=>'x','value'=>['present'=>'true']] as $k=>$v){$p=operation('resume:movie:1');$p[$k]=$v;reject(function()use($p){fxs_validate($p);},400,'validation '.$k);}
$p=operation('resume:series:9');$p['value']['episode']='';reject(function()use($p){fxs_validate($p);},400,'series episode required');
$p=operation('resume:movie:1');$p['value']['label']='https://secret';reject(function()use($p){fxs_validate($p);},400,'URL not stored');
$a=operation('resume:movie:1');$r=fxs_exchange($pdo,'alice',['operations'=>[$a]]);check($r['acks'][0]['accepted']&&$r['after']===1,'first device upload');
$r2=fxs_exchange($pdo,'alice',['operations'=>[$a]]);check($r2['after']===1,'timeout retry idempotent');
check(count(fxs_exchange($pdo,'bob',[])['records'])===0,'customer isolation');
$r=fxs_exchange($pdo,'alice',[]);check($r['records'][0]['value']['position']===20000,'second device reads progress');
$b=operation('resume:movie:1',1);$b['value']['position']=50000;$r=fxs_exchange($pdo,'alice',['operations'=>[$b]]);check($r['after']===2,'second device progresses');
$stale=operation('resume:movie:1',1);$r=fxs_exchange($pdo,'alice',['operations'=>[$stale]]);check(!$r['acks'][0]['accepted']&&$r['acks'][0]['record']['value']['position']===50000,'offline stale progress cannot overwrite');
$del=operation('resume:movie:1',2,false);$r=fxs_exchange($pdo,'alice',['operations'=>[$del]]);check(!$r['records'][0]['value']['present'],'removal tombstone');
$r=fxs_exchange($pdo,'alice',['operations'=>[operation('resume:movie:1',2)]]);check(!$r['acks'][0]['accepted'],'stale device cannot resurrect removed item');
$ops=[operation('favorite:movie:1'),operation('favorite:series:9'),operation('watched:series:9:10')];$r=fxs_exchange($pdo,'alice',['operations'=>$ops]);check(count($r['acks'])===3,'independent my list and watched records');
$p=operation('favorite:movie:1',4,false);$r=fxs_exchange($pdo,'alice',['operations'=>[$p]]);check($r['acks'][0]['accepted'],'remove favorite independent of progress');
$upload=fxs_exchange($pdo,'upload-only',['pull'=>false,'operations'=>[operation('resume:movie:1')]]);check($upload['records']===[]&&$upload['after']===0,'playback uploads do not pull history or advance cursor');
check(count(fxs_exchange($pdo,'upload-only',[])['records'])===1,'history remains available after upload-only exchange');
$before=(int)$pdo->query('SELECT COUNT(*) FROM tv_sync_records')->fetchColumn();$ops=[operation('favorite:movie:7'),operation('invalid')];reject(function()use($pdo,$ops){fxs_exchange($pdo,'alice',['operations'=>$ops]);},400,'whole batch validated');check((int)$pdo->query('SELECT COUNT(*) FROM tv_sync_records')->fetchColumn()===$before,'invalid batch makes no changes');
for($i=1;$i<=125;$i++)fxs_exchange($pdo,'pages',['operations'=>[operation('watched:movie:'.$i)]]);
$r=fxs_exchange($pdo,'pages',[]);check(count($r['records'])===100&&$r['more'],'bounded page');$through=$r['through'];$after=$r['after'];
fxs_exchange($pdo,'pages',['operations'=>[operation('watched:movie:999')]]);
$r=fxs_exchange($pdo,'pages',['after'=>$after,'through'=>$through]);check(count($r['records'])===25&&!$r['more'],'snapshot ceiling pagination');
$r=fxs_exchange($pdo,'pages',['after'=>$through]);check(count($r['records'])===1,'concurrent later write on next delta');
reject(function()use($pdo){fxs_exchange($pdo,'pages',['after'=>999999]);},409,'restored database cursor reset');
$allow=0;for($i=0;$i<10;$i++)if(fxs_rate($pdo,'test',3,900))$allow++;check($allow===3,'rate limit exact');
putenv('SYNC_RACE_START='.(microtime(true)+1));$procs=[];
for($i=0;$i<4;$i++){$pipes=[];$p=proc_open([PHP_BINARY,__FILE__,'worker'],[1=>['pipe','w'],2=>['pipe','w']],$pipes);$procs[]=[$p,$pipes];}
$accepted=0;foreach($procs as [$p,$pipes]){$out=stream_get_contents($pipes[1]);$err=stream_get_contents($pipes[2]);fclose($pipes[1]);fclose($pipes[2]);check(proc_close($p)===0,'worker '.$err);$r=json_decode($out,true);if($r['acks'][0]['accepted'])$accepted++;}
check($accepted===1,'four devices CAS race has one winner');
check((int)$pdo->query("SELECT revision FROM tv_sync_accounts WHERE account_id='".hash('sha256','flixtown-sync:race')."'")->fetchColumn()===1,'race increments once');
// Expired limiter buckets can be removed during maintenance, never delete progress/tombstones automatically.
echo "Account sync: $n checks passed\n";
