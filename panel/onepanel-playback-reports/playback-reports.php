<?php
require __DIR__.'/includes/bootstrap.php';require_login();
require_once __DIR__.'/includes/tv_requests.php';
require_once __DIR__.'/includes/playback_reports.php';
require __DIR__.'/includes/layout.php';
$states=['new'=>'New','investigating'=>'Investigating','resolved'=>'Resolved'];
$tab=is_string($_GET['status']??null)?$_GET['status']:'new';
if(!isset($states[$tab])&&$tab!=='all')$tab='new';
$issues=tvr_issues();$problem='';
if(($_SERVER['REQUEST_METHOD']??'')==='POST') {
  csrf_check();
  $id=$_POST['id']??'';$next=$_POST['status']??'';
  if(!is_string($id)||!preg_match('/^[1-9][0-9]{0,17}$/D',$id)||!is_string($next)||!isset($states[$next])) {http_response_code(400);exit('Invalid report update.');}
  try {$q=$pdo->prepare('UPDATE tv_playback_reports SET status=?,updated_at=UTC_TIMESTAMP() WHERE id=?');$q->execute([$next,$id]);}
  catch(Throwable $e){http_response_code(503);exit('Reports are temporarily unavailable.');}
  header('Location: playback-reports.php?status='.rawurlencode($tab));exit;
}
$page=ctype_digit((string)($_GET['page']??''))?max(1,min(10000,(int)$_GET['page'])):1;
$limit=25;$offset=($page-1)*$limit;$rows=[];$total=0;$counts=[];
try {
  foreach($pdo->query('SELECT status,COUNT(*) AS n FROM tv_playback_reports GROUP BY status') as $c)$counts[$c['status']]=(int)$c['n'];
  $where=$tab==='all'?'':'WHERE r.status=?';$args=$tab==='all'?[]:[$tab];
  $q=$pdo->prepare('SELECT COUNT(*) FROM tv_playback_reports r '.$where);$q->execute($args);$total=(int)$q->fetchColumn();
  $q=$pdo->prepare('SELECT r.*,g.report_count,g.customer_count FROM tv_playback_reports r JOIN
    (SELECT media_type,media_id,episode_id,issue,COUNT(*) report_count,COUNT(DISTINCT username) customer_count FROM tv_playback_reports GROUP BY media_type,media_id,episode_id,issue) g
    ON g.media_type=r.media_type AND g.media_id=r.media_id AND g.episode_id=r.episode_id AND g.issue=r.issue '.$where.' ORDER BY r.created_at DESC,r.id DESC LIMIT '.$limit.' OFFSET '.$offset);
  $q->execute($args);$rows=$q->fetchAll();
} catch(Throwable $e){$problem='Playback reports are not ready. Apply the supplied database migration, then refresh this page.';}
page_top('Playback Reports','playback-reports',['subtitle'=>'Customer-reported video, audio and subtitle problems. Reports never change or replace your media.','actions'=>'<a class="btn btn--ghost" href="playback-reports.php?status='.e($tab).'">Refresh</a>']);
?>
<section class="card" style="padding:20px">
<nav class="segmented" aria-label="Report status">
<?php foreach($states+['all'=>'All'] as $k=>$label):?><a class="seg<?=$tab===$k?' is-active':''?>" href="playback-reports.php?status=<?=e($k)?>"><?=e($label)?> (<?=$k==='all'?array_sum($counts):($counts[$k]??0)?>)</a><?php endforeach;?>
</nav>
<?php if($problem!==''):?><p role="alert"><?=e($problem)?></p>
<?php elseif(!$rows):?><p>No reports in this view.</p>
<?php else:?>
<p><?=$total?> report(s). Repeated problems show their total reports and distinct customers.</p>
<?php foreach($rows as $r):?>
<article data-report-id="<?=(int)$r['id']?>" data-status="<?=e($r['status'])?>" style="border-top:1px solid var(--border,#ddd);padding:20px 0;overflow-wrap:anywhere">
<h2><?=e($r['title'])?><?php if($r['media_type']==='series'):?> · S<?=(int)$r['season_number']?> E<?=(int)$r['episode_number']?><?php endif;?></h2>
<?php if($r['episode_title']!==''):?><p><?=e($r['episode_title'])?></p><?php endif;?>
<p><strong><?=e($issues[$r['issue']]??'Playback problem')?></strong> · <?=e($states[$r['status']]??$r['status'])?></p>
<p><?=(int)$r['report_count']?> report(s) from <?=(int)$r['customer_count']?> customer(s) for this title/episode and issue.</p>
<details><summary>Report details</summary>
<dl>
<dt>Customer account</dt><dd><?=e($r['username'])?></dd>
<dt>Reported at (UTC)</dt><dd><?=e($r['created_at'])?></dd>
<dt>Playback position</dt><dd><?=gmdate('H:i:s',(int)floor($r['position_ms']/1000))?></dd>
<dt>Catalog IDs</dt><dd><?=e($r['media_type'])?> <?=(int)$r['media_id']?><?php if($r['episode_id']):?> / episode <?=(int)$r['episode_id']?><?php endif;?></dd>
<dt>App / Android</dt><dd><?=e($r['version_name'])?> (<?=(int)$r['version_code']?>) / API <?=(int)$r['android_api']?></dd>
<dt>Device (customer-reported)</dt><dd><?=e($r['device'])?></dd>
<dt>Video / audio (customer-reported)</dt><dd><?=e($r['video_format'])?> / <?=e($r['audio_format'])?></dd>
<dt>Player error code</dt><dd><?=e($r['error_code'])?></dd>
</dl></details>
<form method="post" action="playback-reports.php?status=<?=e($tab)?>" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:12px">
<input type="hidden" name="_csrf" value="<?=e(csrf_token())?>"><input type="hidden" name="id" value="<?=(int)$r['id']?>">
<label>Status <select name="status"><?php foreach($states as $k=>$label):?><option value="<?=e($k)?>"<?=$r['status']===$k?' selected':''?>><?=e($label)?></option><?php endforeach;?></select></label>
<button class="btn btn--primary" type="submit">Save status</button>
</form></article>
<?php endforeach;endif;?>
<nav aria-label="Report pages" style="display:flex;gap:20px;margin-top:16px">
<?php if($page>1):?><a href="?status=<?=e($tab)?>&amp;page=<?=$page-1?>">Previous</a><?php endif;?>
<?php if($offset+$limit<$total):?><a href="?status=<?=e($tab)?>&amp;page=<?=$page+1?>">Next</a><?php endif;?>
</nav></section>
<?php page_bottom(); ?>
