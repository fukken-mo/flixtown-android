<?php
/* Authenticated account-owned profile metadata. No credential or media storage. */
function fxp_id($id): bool {return is_string($id)&&($id==='default'||preg_match('/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/D',$id)===1);}
function fxp_account(string $u): string {return hash('sha256','flixtown-profiles:'.$u);}
function fxp_clean($p): array {
 if(!is_array($p)||!fxp_id($p['id']??null)||!is_string($p['name']??null)||!is_string($p['avatar']??null)||($p['kids']??null)!==false)fxs_fail(400,'invalid');
 $name=trim($p['name']);
 if($name===''||!preg_match('//u',$name)||preg_match('/[\p{Cc}\p{Cf}\p{Cs}]/u',$name)||preg_match_all('/./us',$name)>24)fxs_fail(400,'name');
 if(!preg_match('/^avatar_(?:0[1-9]|1[0-9]|2[0-8])$/D',$p['avatar']))fxs_fail(400,'avatar');
 return ['id'=>$p['id'],'name'=>$name,'avatar'=>$p['avatar'],'kids'=>false];
}
function fxp_lock(PDO $db,string $account): int {
 $q=$db->prepare('INSERT INTO tv_profile_accounts(account_id,revision) VALUES(?,0) ON DUPLICATE KEY UPDATE revision=revision');$q->execute([$account]);
 $q=$db->prepare('SELECT revision FROM tv_profile_accounts WHERE account_id=? FOR UPDATE');$q->execute([$account]);return (int)$q->fetchColumn();
}
function fxp_rows(PDO $db,string $account): array {
 $q=$db->prepare('SELECT profile_id,payload FROM tv_profiles WHERE account_id=? AND deleted=0 ORDER BY (profile_id=\'default\') DESC,profile_id');$q->execute([$account]);$out=[];
 foreach($q->fetchAll() as $r)$out[]=json_decode($r['payload'],true);return $out;
}
/* One account lock serializes catalog CAS, creation limits, deletion and non-default progress writes. */
function fxp_catalog(PDO $db,string $u,array $in): array {
 $account=fxp_account($u);$kind=$in['mutation']??'list';if(!in_array($kind,['list','create','update','delete'],true))fxs_fail(400,'invalid');
 $base=$kind==='list'?0:fxs_integer($in['revision']??null);
 $db->beginTransaction();
 try {
  $rev=fxp_lock($db,$account);
  $q=$db->prepare('SELECT profile_id FROM tv_profiles WHERE account_id=? AND profile_id=?');$q->execute([$account,'default']);
  if($q->fetchColumn()===false){
   $rev=fxs_next_revision($rev);$p=['id'=>'default','name'=>'My profile','avatar'=>'avatar_01','kids'=>false];
   $q=$db->prepare('INSERT INTO tv_profiles(account_id,profile_id,revision,payload) VALUES(?,?,?,?)');$q->execute([$account,'default',$rev,json_encode($p)]);
  }
  if($kind!=='list'){
   if($base!==$rev)fxs_fail(409,'profile_conflict');
   if($kind==='delete'){$id=$in['id']??null;if(!fxp_id($id)||$id==='default')fxs_fail(400,'invalid');$clean=null;}
   else{$clean=fxp_clean($in['profile']??null);$id=$clean['id'];}
   $q=$db->prepare('SELECT deleted FROM tv_profiles WHERE account_id=? AND profile_id=?');$q->execute([$account,$id]);$old=$q->fetchColumn();
   if($kind==='create'){
    if($old!==false)fxs_fail(409,'profile_exists');
    $rows=fxp_rows($db,$account);if(count($rows)>=6)fxs_fail(409,'profile_limit');
    // Tombstones are retained. Bound metadata growth from repeated create/delete actions.
    $q=$db->prepare('SELECT COUNT(*) FROM tv_profiles WHERE account_id=?');$q->execute([$account]);if((int)$q->fetchColumn()>=1000)fxs_fail(409,'profile_capacity');
   }elseif($old===false||(int)$old!==0)fxs_fail(404,'profile_missing');
   $rev=fxs_next_revision($rev);
   if($kind==='delete'){$q=$db->prepare('UPDATE tv_profiles SET deleted=1,revision=?,payload=? WHERE account_id=? AND profile_id=?');$q->execute([$rev,'{}',$account,$id]);}
   else{$q=$db->prepare('INSERT INTO tv_profiles(account_id,profile_id,revision,payload) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),payload=VALUES(payload)');$q->execute([$account,$id,$rev,json_encode($clean,JSON_UNESCAPED_UNICODE)]);}
  }
  $q=$db->prepare('UPDATE tv_profile_accounts SET revision=? WHERE account_id=?');$q->execute([$rev,$account]);$rows=fxp_rows($db,$account);$db->commit();
  return ['ok'=>true,'profiles_version'=>1,'revision'=>$rev,'profiles'=>$rows,'kids_available'=>false];
 }catch(Throwable $e){if($db->inTransaction())$db->rollBack();throw $e;}
}
/* Called inside the history transaction, before reading or writing any non-default history. */
function fxp_authorize(PDO $db,string $u,string $id): void {
 if(!fxp_id($id)||$id==='default')fxs_fail(400,'profile');
 $account=fxp_account($u);fxp_lock($db,$account);
 $q=$db->prepare('SELECT payload FROM tv_profiles WHERE account_id=? AND profile_id=? AND deleted=0');$q->execute([$account,$id]);$raw=$q->fetchColumn();
 if($raw===false)fxs_fail(404,'profile_missing');
 $p=json_decode($raw,true);if(!is_array($p)||($p['kids']??null)!==false)fxs_fail(403,'profile_unavailable');
}
