#!/usr/bin/env python3
"""Exercise real PHP endpoint, HTTP TMDB fixture, and SQLite cache. No live credentials."""
import json, os, pathlib, shutil, socket, subprocess, tempfile, time, urllib.request, urllib.error

HERE = pathlib.Path(__file__).resolve().parent
def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); return s.getsockname()[1]

with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp); api = root/'api'; api.mkdir()
    for name in ('tmdb.php','tmdb-match.php','tmdb-cast-match.php'):
        shutil.copy(HERE/'../tmdb'/name, api/name)
    shutil.copy(HERE/'tmdb-fixture/bootstrap.php', api/'bootstrap.php')
    db = root/'test.sqlite'
    env = dict(os.environ, FLIXTOWN_TEST_DB=str(db))
    def sql(statement):
        subprocess.run(['php','-r', '$d=new PDO("sqlite:".$argv[1]);$d->exec($argv[2]);',str(db),statement],check=True,env=env)
    sql('CREATE TABLE settings(name TEXT PRIMARY KEY,value TEXT); CREATE TABLE tmdb_cache(cache_key TEXT PRIMARY KEY,payload TEXT,expires_at TEXT); INSERT INTO settings VALUES(\'tmdb_key\',\'test\');')
    (root/'router.php').write_text('''<?php
    file_put_contents(__DIR__.'/calls', "request\\n", FILE_APPEND);
    $d=json_decode(file_get_contents(__DIR__.'/fixture.json'),true);
    $path=parse_url($_SERVER['REQUEST_URI'],PHP_URL_PATH);
    header('Content-Type: application/json');
    if(!empty($d['down'])){http_response_code(503);echo '{}';exit;}
    if(str_contains($path,'search/')){echo json_encode(['results'=>$d['results']]);exit;}
    if(str_ends_with($path,'/credits')){echo json_encode($d['credits']);exit;}
    if(str_ends_with($path,'/videos')){echo json_encode($d['videos']);exit;}
    echo json_encode($d['record']);
    ''')
    right={'id':101,'title':'The Beast','release_date':'2026-10-09'}
    wrong={'id':102,'title':'Heart of the Beast','release_date':'2026-09-01'}
    fixture={'results':[wrong,right], 'record':right,
             'credits':{'id':101,'cast':[{'id':1,'name':'Correct actor','profile_path':'/portrait.jpg'}]},
             'videos':{'id':101,'results':[{'site':'YouTube','type':'Trailer','key':'abcdefghijk'}]}}
    def write(): (root/'fixture.json').write_text(json.dumps(fixture))
    write(); upstream=port(); panel=port()
    env['FLIXTOWN_TMDB_BASE']=f'http://127.0.0.1:{upstream}/3/'
    log=open(root/'php.log','w')
    servers=[subprocess.Popen(['php','-S',f'127.0.0.1:{upstream}',str(root/'router.php')],env=env,stdout=log,stderr=log),
             subprocess.Popen(['php','-S',f'127.0.0.1:{panel}','-t',str(root)],env=env,stdout=log,stderr=log)]
    passed=0
    def request(query='kind=movie&title=The%20Beast&year=2026',method='GET'):
        req=urllib.request.Request(f'http://127.0.0.1:{panel}/api/tmdb.php?'+query,method=method)
        try:
            with urllib.request.urlopen(req,timeout=15) as r: return r.status,json.load(r)
        except urllib.error.HTTPError as e: return e.code,json.load(e)
    def check(condition,label):
        global passed
        if not condition: raise AssertionError(label)
        passed+=1; print('PASS',label)
    def clear(): sql('DELETE FROM tmdb_cache;')
    def calls(): return len((root/'calls').read_text().splitlines())
    try:
        for _ in range(60):
            try:
                with socket.create_connection(('127.0.0.1',panel),timeout=.1): break
            except OSError: time.sleep(.1)
        # Poison the legacy key. Endpoint must never serve it.
        sql("INSERT INTO tmdb_cache VALUES ('"+__import__('hashlib').sha256(b'movie|the beast|2026').hexdigest()+"','{\"cast\":[{\"name\":\"Wrong old actor\"}],\"trailer\":\"\"}','2099-01-01 00:00:00');")
        status,data=request();check(status==200 and data['cast'][0]['name']=='Correct actor','reject first fuzzy result and old poisoned cache')
        check(data['match']['tmdb_id']==101 and data['match_version']==2,'expose verified identity')
        n=calls();check(request()[1]==data and calls()==n,'new verified cache reused')
        clear();fixture['results']=[wrong];write();check(request()[1]['cast']==[],'no fuzzy fallback even without year filter')
        clear();fixture['results']=[right,dict(right,id=103)];write();check(request()[1]['match'] is None,'same-year duplicates withheld')
        clear();fixture['results']=[right,dict(right,id=104,release_date='2023-01-01')];write();check(request('kind=movie&title=The%20Beast')[1]['match'] is None,'no-year ambiguity withheld')
        clear();fixture['results']=[right];fixture['record']=wrong;write();check(request()[1]['cast']==[],'detail identity mismatch cannot attach cast')
        clear();fixture['record']=right;fixture['down']=True;write();check(request()[0]==503,'upstream outage reported')
        fixture['down']=False;write();check(request()[1]['cast'][0]['name']=='Correct actor','outage not cached')
        check(request('kind=bad&title=x')[0]==400,'invalid kind refused')
        check(request('kind=movie&title=x&year=oops')[0]==400,'invalid year refused')
        check(request(method='POST')[0]==405,'non-GET refused')
        clear();tv={'id':201,'name':'Example Series','first_air_date':'2020-01-01'}
        fixture.update(results=[tv],record=tv,credits={'id':201,'cast':[{'id':1,'name':'Series actor'}]},videos={'id':201,'results':[]});write()
        check(request('kind=series&title=Example%20Series&year=2020')[1]['cast'][0]['name']=='Series actor','series identities supported')
        clear();sql("UPDATE settings SET value='';");check(request()[1]['cast']==[],'missing key returns empty cast')
        print(f'cast HTTP: {passed} passed')
    except Exception:
        log.flush();print((root/'php.log').read_text());raise
    finally:
        for server in servers: server.terminate()
        for server in servers: server.wait(timeout=10)
        log.close()
