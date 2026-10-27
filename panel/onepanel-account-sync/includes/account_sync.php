<?php
/* Account-scoped progress only. No Seerr calls, media writes, admin actions or credentials stored. */
class FlixSyncError extends Exception {
    public $status; public $reason;
    function __construct(int $status,string $reason) {parent::__construct($reason);$this->status=$status;$this->reason=$reason;}
}
function fxs_fail(int $status,string $reason): void {throw new FlixSyncError($status,$reason);}
function fxs_secret(): string {
    global $config;
    return (string)($config['services']['progress_sync']['session_secret']??($config['services']['seerr']['customer_session_secret']??''));
}
function fxs_username($u): bool {return is_string($u)&&preg_match('/^[A-Za-z0-9._@-]{1,100}$/D',$u)===1;}
function fxs_b64(string $s): string {return rtrim(strtr(base64_encode($s),'+/','-_'),'=');}
function fxs_token(string $u,int $now): string {
    $body=fxs_b64(json_encode(['u'=>$u,'purpose'=>'flix_account_sync_v1','exp'=>$now+900]));
    return $body.'.'.fxs_b64(hash_hmac('sha256',$body,fxs_secret(),true));
}
function fxs_user($token,int $now): ?string {
    if(!is_string($token)||strlen($token)>512||substr_count($token,'.')!==1)return null;
    [$body,$sig]=explode('.',$token);
    if(!hash_equals(fxs_b64(hash_hmac('sha256',$body,fxs_secret(),true)),$sig))return null;
    $d=json_decode((string)base64_decode(strtr($body,'-_','+/'),true),true);
    return is_array($d)&&($d['purpose']??'')==='flix_account_sync_v1'&&is_int($d['exp']??null)&&$d['exp']>$now&&fxs_username($d['u']??null)?$d['u']:null;
}
function fxs_rate(PDO $db,string $key,int $limit,int $window): bool {
    $key=hash('sha256',$key);$now=time();$old=$now-$window;
    $q=$db->prepare('INSERT INTO tv_sync_rate(k,window_start,hits) VALUES(?,?,1) ON DUPLICATE KEY UPDATE hits=LAST_INSERT_ID(IF(window_start<=?,1,hits+1)),window_start=IF(window_start<=?,?,window_start)');
    $q->execute([$key,$now,$old,$old,$now]);
    return $q->rowCount()===1||(int)$db->query('SELECT LAST_INSERT_ID()')->fetchColumn()<=$limit;
}
function fxs_active(string $u): void {
    $r=flix_api_request('GET','/api/onepanel/users/'.rawurlencode($u));
    if(!$r['ok'])fxs_fail(503,'unavailable');
    $d=$r['data'];$user=is_array($d['user']??null)?$d['user']:$d;
    if(!is_array($user)||($user['username']??'')!==$u||!tvq_account_active($user))fxs_fail(403,'inactive');
}
function fxs_integer($v,int $max=9007199254740991): int {if(!is_int($v)||$v<0||$v>$max)fxs_fail(400,'invalid');return $v;}
function fxs_validate($in): array {
    if(!is_array($in))fxs_fail(400,'invalid');
    $key=$in['key']??null;
    if(!is_string($key)||!preg_match('/^(?:(?:resume|favorite):(movie|series):([1-9][0-9]{0,9})|watched:(movie):([1-9][0-9]{0,9})|watched:(series):([1-9][0-9]{0,9}):([1-9][0-9]{0,9}))$/D',$key))fxs_fail(400,'invalid');
    $op=$in['operation']??null;
    if(!is_string($op)||!preg_match('/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/D',$op))fxs_fail(400,'invalid');
    $v=$in['value']??null;if(!is_array($v)||!is_bool($v['present']??null))fxs_fail(400,'invalid');
    $clean=['present'=>$v['present']];
    if(strpos($key,'resume:')===0&&$v['present']) {
        $clean['position']=fxs_integer($v['position']??null,604800000);
        $clean['duration']=fxs_integer($v['duration']??null,604800000);
        foreach(['episode','next'] as $f){$s=$v[$f]??'';if(!is_string($s)||!preg_match('/^(?:[1-9][0-9]{0,9})?$/D',$s))fxs_fail(400,'invalid');$clean[$f]=$s;}
        if(strpos($key,'resume:series:')===0&&$clean['episode']==='')fxs_fail(400,'invalid');
        if(strpos($key,'resume:movie:')===0&&($clean['episode']!==''||$clean['next']!==''))fxs_fail(400,'invalid');
        foreach(['extension','next_ext'] as $f){$s=$v[$f]??'mp4';if(!is_string($s)||!preg_match('/^[a-zA-Z0-9]{1,10}$/D',$s))fxs_fail(400,'invalid');$clean[$f]=$s;}
        if(!is_bool($v['done']??null))fxs_fail(400,'invalid');$clean['done']=$v['done'];
        $label=$v['label']??'';
        if(!is_string($label)||strlen($label)>240||!preg_match('//u',$label)||preg_match('/[\x00-\x1f\x7f]|https?:|(?:password|token|session|username)\s*[=:]/i',$label))fxs_fail(400,'invalid');
        $clean['label']=$label;
    }
    return ['key'=>$key,'base'=>fxs_integer($in['base']??null),'operation'=>$op,'value'=>$clean];
}
function fxs_row(array $r): array {return ['key'=>$r['record_key'],'revision'=>(int)$r['revision'],'value'=>json_decode($r['payload'],true)];}
/* Account row lock serializes revisions/CAS across PHP workers, including empty-record races. */
function fxs_exchange(PDO $db,string $u,array $in): array {
    $after=fxs_integer($in['after']??0);$through=fxs_integer($in['through']??0);
    $raw=$in['operations']??[];if(!is_array($raw)||array_keys($raw)!==range(0,count($raw)-1)&&count($raw)!==0||count($raw)>40)fxs_fail(400,'invalid');
    $ops=[];$keys=[];foreach($raw as $o){$o=fxs_validate($o);if(isset($keys[$o['key']]))fxs_fail(400,'invalid');$keys[$o['key']]=true;$ops[]=$o;}
    if($through>0&&($ops||$after>$through))fxs_fail(400,'invalid');
    $account=hash('sha256','flixtown-sync:'.$u);$acks=[];
    $db->beginTransaction();
    try {
        $q=$db->prepare('INSERT IGNORE INTO tv_sync_accounts(account_id,revision) VALUES(?,0)');$q->execute([$account]);
        $q=$db->prepare('SELECT revision FROM tv_sync_accounts WHERE account_id=? FOR UPDATE');$q->execute([$account]);$rev=(int)$q->fetchColumn();
        if($after>$rev||$through>$rev)fxs_fail(409,'reset');
        foreach($ops as $o){
            $q=$db->prepare('SELECT * FROM tv_sync_records WHERE account_id=? AND record_key=?');$q->execute([$account,$o['key']]);$row=$q->fetch();
            if($row&&$row['operation_id']===$o['operation']){$acks[]=['operation'=>$o['operation'],'accepted'=>true,'record'=>fxs_row($row)];continue;}
            if(($row?(int)$row['revision']:0)!==$o['base']){
                $acks[]=['operation'=>$o['operation'],'accepted'=>false,'record'=>$row?fxs_row($row):['key'=>$o['key'],'revision'=>0,'value'=>['present'=>false]]];continue;
            }
            if(!$row){$q=$db->prepare('SELECT COUNT(*) FROM tv_sync_records WHERE account_id=?');$q->execute([$account]);if((int)$q->fetchColumn()>=10000)fxs_fail(429,'capacity');}
            $rev++;
            $q=$db->prepare('INSERT INTO tv_sync_records(account_id,record_key,revision,operation_id,payload) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),operation_id=VALUES(operation_id),payload=VALUES(payload)');
            $q->execute([$account,$o['key'],$rev,$o['operation'],json_encode($o['value'],JSON_UNESCAPED_UNICODE)]);
            $acks[]=['operation'=>$o['operation'],'accepted'=>true,'record'=>['key'=>$o['key'],'revision'=>$rev,'value'=>$o['value']]];
        }
        $q=$db->prepare('UPDATE tv_sync_accounts SET revision=? WHERE account_id=?');$q->execute([$rev,$account]);
        $ceiling=$through?:$rev;
        $q=$db->prepare('SELECT record_key,revision,payload FROM tv_sync_records WHERE account_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 101');$q->execute([$account,$after,$ceiling]);$rows=$q->fetchAll();
        $more=count($rows)>100;$rows=array_slice($rows,0,100);$out=array_map('fxs_row',$rows);
        $db->commit();
        return ['ok'=>true,'acks'=>$acks,'records'=>$out,'through'=>$ceiling,'after'=>$more?(int)end($rows)['revision']:$ceiling,'more'=>$more];
    }catch(Throwable $e){if($db->inTransaction())$db->rollBack();throw $e;}
}
