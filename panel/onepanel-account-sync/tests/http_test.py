"""Real endpoint with fake identity adapter, disposable CI database, no live HTTP calls."""
import json, os, pathlib, re, shutil, socket, subprocess, tempfile, time, urllib.request, urllib.error
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as folder:
    dest=pathlib.Path(folder)
    shutil.copytree(root/'api',dest/'api');shutil.copytree(root/'includes',dest/'includes')
    (dest/'includes'/'tv_requests.php').write_text('''<?php
function tvq_account_active(array $u): bool {return $u['enabled'];}
function tvq_flix_login(string $u,string $p): ?array {return $p==='fixture-password'?['enabled'=>$u!=='disabled']:null;}
function flix_api_request($m,$p): array {$u=rawurldecode(basename($p));return ['ok'=>$u!=='outage','data'=>['username'=>$u,'enabled'=>$u!=='disabled']];}
''')
    (dest/'includes'/'bootstrap.php').write_text('''<?php
$pdo=new PDO(getenv('SYNC_TEST_DSN'),getenv('SYNC_TEST_USER'),getenv('SYNC_TEST_PASSWORD'),[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC,PDO::ATTR_EMULATE_PREPARES=>false]);
$config=['services'=>['progress_sync'=>['enabled'=>true,'session_secret'=>str_repeat('fixture-only',4)]]];
''')
    # Disposable *_test database only, emptied first: this test counts sign-ins and failures exactly.
    assert re.search(r'dbname=[A-Za-z0-9_]+_test(;|$)',os.environ.get('SYNC_TEST_DSN','')),'Disposable *_test database required'
    subprocess.run(['php','-r',"$p=new PDO(getenv('SYNC_TEST_DSN'),getenv('SYNC_TEST_USER'),getenv('SYNC_TEST_PASSWORD'));$p->exec(file_get_contents($argv[1]));foreach(['tv_sync_records','tv_sync_accounts','tv_sync_rate'] as $t)$p->exec('DELETE FROM '.$t);",str(root/'sql'/'account-sync.sql')],check=True)
    sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1];sock.close()
    with open(dest/'server.log','wb') as log:
      proc=subprocess.Popen(['php','-S',f'127.0.0.1:{port}','-t',str(dest)],stdout=log,stderr=log)
      try:
        time.sleep(.5)
        def call(body=None,method='POST',raw=None):
            data=raw if raw is not None else json.dumps(body or {}).encode()
            req=urllib.request.Request(f'http://127.0.0.1:{port}/api/tv-account-sync.php',data=data if method=='POST' else None,method=method,headers={'Content-Type':'application/json'})
            try:
                with urllib.request.urlopen(req,timeout=5) as r:return r.status,json.load(r)
            except urllib.error.HTTPError as e:return e.code,json.load(e)
        count=0
        def check(ok,name):
            global count
            assert ok,name
            count+=1;print('PASS',name)
        check(call(method='GET')[0]==405,'POST only')
        check(call(raw=b'[]')[0]==400,'object required')
        check(call(raw=b'x'*65537)[0]==400,'request size bounded')
        check(call({'action':'exchange'})[0]==401,'unauthenticated refused')
        check(call({'action':'session','username':'alice','password':'wrong'})[0]==401,'wrong password refused')
        check(call({'action':'session','username':'disabled','password':'fixture-password'})[0]==403,'disabled login refused')
        status,r=call({'action':'session','username':'alice','password':'fixture-password'});check(status==200 and r['ok'],'existing identity session')
        alice=r['session'];status,r=call({'action':'session','username':'bob','password':'fixture-password'});bob=r['session']
        check(call({'action':'exchange','session':alice+'x'})[0]==401,'tampered session refused')
        import uuid
        op={'key':'favorite:movie:333','base':0,'operation':str(uuid.uuid4()),'value':{'present':True}}
        status,r=call({'action':'exchange','session':alice,'username':'bob','operations':[op]});check(status==200 and r['acks'][0]['accepted'],'token binds owner despite forged username')
        status,r=call({'action':'exchange','session':bob});check(not r['records'],'other account cannot read record')
        status,r=call({'action':'exchange','session':alice});check(any(x['key']==op['key'] for x in r['records']),'owner can read record')
        check(not any(x in json.dumps(r) for x in ['fixture-password','fixture-only','session_secret']),'responses have no credentials or secret')
        check(call({'action':'approve','session':alice})[0]==400,'no admin action')
        status,r=call({'action':'exchange','session':alice,'operations':[dict(op,key='server',operation=str(uuid.uuid4()))]})
        check(status==200 and r['acks'][0]['error']=='invalid' and r['acks'][0]['record'] is None,'arbitrary pref refused on its own')
        status,r=call({'action':'exchange','session':alice});check(not any(x['key']=='server' for x in r['records']),'refused record stored nowhere')
        # Many customers behind one public address (carrier NAT, a proxy) all get sessions: limits are per account.
        ok=[call({'action':'session','username':'user%02d'%i,'password':'fixture-password'})[0] for i in range(40)]
        check(ok.count(200)==40,'40 accounts sharing one address all sign in')
        carol=[call({'action':'session','username':'carol','password':'fixture-password'})[0] for i in range(21)]
        check(carol[:20].count(200)==20 and carol[20]==429,'sessions limited per account (20 per 15 minutes)')
        # Failed sign-ins are limited per address: guessing passwords stops, sessions already issued keep working.
        fails=[call({'action':'session','username':'mallory','password':'guess%d'%i})[0] for i in range(30)]
        check(fails.count(401)>=29 and fails[-1] in (401,429),'failed sign-ins counted')
        check(call({'action':'session','username':'dave','password':'fixture-password'})[0]==429,'address blocked after 30 failed sign-ins')
        check(call({'action':'exchange','session':alice})[0]==200,'existing sessions unaffected by failed sign-ins')
      finally:
        proc.terminate();proc.wait(timeout=5)
    text=(dest/'server.log').read_text()
    assert 'fixture-password' not in text and 'fixture-only' not in text
    print('HTTP security:',count,'checks passed; log secret check passed')
