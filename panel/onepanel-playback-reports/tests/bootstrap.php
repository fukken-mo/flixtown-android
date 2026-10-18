<?php
// Test fixture only. Never makes live API calls. Must use a disposable *_test database.
$dsn=getenv('REPORTS_TEST_DSN')?:'';
if(!preg_match('/dbname=([a-zA-Z0-9_]+_test)(?:;|$)/',$dsn))throw new RuntimeException('A disposable *_test database is required.');
$pdo=new PDO($dsn,getenv('REPORTS_TEST_USER')?:'root',getenv('REPORTS_TEST_PASSWORD')?:'',[
 PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC,PDO::ATTR_EMULATE_PREPARES=>false]);
$config=['services'=>['playback_reports'=>['enabled'=>true,'session_secret'=>str_repeat('fixture-only-',4)],
 'flix_town'=>['base_url'=>'https://fixture.invalid'],'seerr'=>['customer_session_secret'=>str_repeat('fixture-only-',4)]]];
function flix_api_request($method,$path){
 $u=rawurldecode(basename($path));
 if($u==='outage')return ['ok'=>false,'status'=>503,'data'=>[]];
 return ['ok'=>true,'status'=>200,'data'=>['username'=>$u,'password'=>'fixture-password','enabled'=>$u!=='disabled','expires_at'=>null]];
}
$GLOBALS['tvq_player_http']=function($q){
 if(($q['action']??'')==='get_vod_info'&&(int)$q['vod_id']===101)return ['movie_data'=>['stream_id'=>101,'name'=>'Fixture Movie <script>']];
 if(($q['action']??'')==='get_series_info'&&(int)$q['series_id']===201)return ['info'=>['name'=>'Fixture Series'],'episodes'=>['1'=>[['id'=>'5001','title'=>'Episode One','season'=>1,'episode_num'=>1]]]];
 return [];
};
if(session_status()!==PHP_SESSION_ACTIVE)session_start();
function require_login(){if(empty($_SESSION['admin_id'])){http_response_code(401);exit('Sign in');}}
function e(string $s): string{return htmlspecialchars($s,ENT_QUOTES,'UTF-8');}
function csrf_token(): string {return $_SESSION['csrf']??($_SESSION['csrf']='fixture-csrf');}
function csrf_check(): void{if(!isset($_POST['_csrf'])||!hash_equals($_SESSION['csrf']??'',(string)$_POST['_csrf'])){http_response_code(419);exit('Session expired.');}}
