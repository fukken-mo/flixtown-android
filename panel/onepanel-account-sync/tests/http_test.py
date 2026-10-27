"""Real endpoint with fake identity adapter, disposable CI database, no live HTTP calls."""
import json, os, pathlib, shutil, socket, subprocess, tempfile, time, urllib.request, urllib.error
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
        check(call({'action':'exchange','session':alice,'operations':[dict(op,key='server')]})[0]==400,'arbitrary pref rejected')
      finally:
        proc.terminate();proc.wait(timeout=5)
    text=(dest/'server.log').read_text()
    assert 'fixture-password' not in text and 'fixture-only' not in text
    print('HTTP security:',count,'checks passed; log secret check passed')
