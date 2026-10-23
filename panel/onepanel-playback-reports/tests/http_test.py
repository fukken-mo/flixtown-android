"""Exercise the real PHP endpoint/admin page with fake auth/catalog and a disposable DB."""
import http.cookiejar,re,json,os,pathlib,shutil,socket,subprocess,tempfile,time,urllib.request,urllib.error,uuid
root=pathlib.Path(__file__).resolve().parents[1]
passed=0

def check(value,label):
 global passed
 if not value:raise AssertionError(label)
 passed+=1;print('PASS '+label,flush=True)

with tempfile.TemporaryDirectory() as tmp:
 site=pathlib.Path(tmp)
 for name in ('api','includes'):
  shutil.copytree(root/name,site/name)
 shutil.copy(root/'playback-reports.php',site/'playback-reports.php')
 shutil.copy(root/'tests/bootstrap.php',site/'includes/bootstrap.php')
 shutil.copy(root.parent/'onepanel-tv-requests/includes/tv_requests.php',site/'includes/tv_requests.php')
 (site/'includes/ui.php').write_text('''<?php
function op_head($x){return '<title>'.e($x).'</title>';}
function brand_lockup($x){return 'OnePanel';}
function icon($x,$c=''){return '';}
function theme_toggle(){return '';}
function op_asset($x){return $x;}
function flash_take(){return null;}
''')
 (site/'router.php').write_text('''<?php
if(parse_url($_SERVER['REQUEST_URI'],PHP_URL_PATH)==='/fixture-login') {
 require __DIR__.'/includes/bootstrap.php';$_SESSION['admin_id']=1;$_SESSION['admin_name']='Fixture';$_SESSION['csrf']='fixture-csrf';echo 'ok';return true;
} return false;
''')
 with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
 log=open(site/'server.log','w+')
 server=subprocess.Popen(['php','-S',f'127.0.0.1:{port}','-t',str(site),str(site/'router.php')],stdout=log,stderr=log)
 base=f'http://127.0.0.1:{port}'
 opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def call(path,data=None,method=None,client=opener,raw=None):
  body=raw if raw is not None else json.dumps(data).encode() if data is not None else None
  req=urllib.request.Request(base+path,data=body,method=method,headers={'Content-Type':'application/json'})
  try:
   with client.open(req,timeout=10) as r:return r.status,r.read().decode()
  except urllib.error.HTTPError as e:return e.code,e.read().decode()
 try:
  for i in range(50):
   try:call('/missing');break
   except urllib.error.URLError:time.sleep(.1)
  endpoint='/api/tv-playback-reports.php'
  check(call(endpoint)[0]==405,'GET rejected')
  check(call(endpoint,raw=b'{bad')[0]==400,'invalid JSON rejected')
  check(call(endpoint,raw=b'[]')[0]==400,'JSON array rejected')
  check(call(endpoint,raw=b'x'*8193)[0]==400,'oversize body rejected')
  check(call(endpoint,{'action':'submit'})[0]==401,'unauthenticated submission rejected')
  check(call(endpoint,{'action':'session','username':'http-user','password':'wrong'})[0]==401,'invalid login rejected')
  check(call(endpoint,{'action':'session','username':'disabled','password':'fixture-password'})[0]==403,'disabled login rejected')
  status,body=call(endpoint,{'action':'session','username':'http-user','password':'fixture-password'})
  check(status==200 and json.loads(body)['ok'],'session through real HTTP endpoint')
  token=json.loads(body)['session']
  p=dict(action='submit',session=token,type='movie',media_id=101,episode_id=0,issue='no_audio',position_ms=1234,version_code=10034,version_name='test',android_api=31,device='Fixture TV',request_id=str(uuid.uuid4()),username='victim',url='https://fake.invalid/secret',password='do-not-store')
  status,body=call(endpoint,p);check(status==200 and json.loads(body)['ok'],'report accepted through HTTP')
  check('victim' not in body and 'do-not-store' not in body and token not in body,'response leaks no identity override/credentials/session')
  check(json.loads(call(endpoint,p)[1])['duplicate'],'HTTP retry deduplicated')
  p['episode_id']=7;check(call(endpoint,p)[0]==400,'invalid movie episode rejected through HTTP')
  check(call('/playback-reports.php')[0]==401,'admin page requires login')
  call('/fixture-login')
  status,body=call('/playback-reports.php?status=all');check(status==200 and 'Playback Reports' in body,'admin page renders')
  check('&lt;script&gt;' in body and 'Fixture Movie <script>' not in body,'catalog text HTML-escaped')
  check('http-user' in body and 'victim' not in body and 'do-not-store' not in body,'only authenticated owner saved')
  report_id=re.search(r'name="id" value="(\d+)"',body).group(1)
  req=urllib.request.Request(base+'/playback-reports.php',data=f'id={report_id}&status=resolved'.encode(),headers={'Content-Type':'application/x-www-form-urlencoded'})
  try:opener.open(req);code=200
  except urllib.error.HTTPError as e:code=e.code
  check(code==419,'status updates require CSRF')
  req=urllib.request.Request(base+'/playback-reports.php?status=all',data=f'id={report_id}&status=investigating&_csrf=fixture-csrf'.encode(),headers={'Content-Type':'application/x-www-form-urlencoded'})
  with opener.open(req) as r:html=r.read().decode();check(r.status==200 and f'data-report-id="{report_id}" data-status="investigating"' in html,'authorized status update succeeds')
  check(call(endpoint,{'action':'resolved'})[0]==401,'unauthenticated resolution read rejected')
  check(json.loads(call(endpoint,{'action':'resolved','session':token})[1])['reports']==[],'investigating does not notify resolved')
  req=urllib.request.Request(base+'/playback-reports.php?status=all',data=f'id={report_id}&status=resolved&_csrf=fixture-csrf'.encode(),headers={'Content-Type':'application/x-www-form-urlencoded'})
  with opener.open(req) as r:check(r.status==200,'admin can resolve report')
  status,body=call(endpoint,{'action':'resolved','session':token,'username':'victim'})
  feed=json.loads(body)
  check(status==200 and len(feed['reports'])==1 and feed['reports'][0]['id']==report_id,'owner receives resolved report through HTTP')
  check(not any(x in body for x in ['device','position_ms','username','password','session','fixture-password']),'resolution response contains no private diagnostics or credentials')
  other=json.loads(call(endpoint,{'action':'session','username':'other-user','password':'fixture-password'})[1])['session']
  check(json.loads(call(endpoint,{'action':'resolved','session':other,'username':'http-user'})[1])['reports']==[],'forged owner cannot read another customer resolution')
  check(call(endpoint,{'action':'resolved','session':token,'before_id':-1})[0]==400,'negative resolution cursor rejected')
  req=urllib.request.Request(base+'/playback-reports.php',data=f'id={report_id}&status=delete&_csrf=fixture-csrf'.encode(),headers={'Content-Type':'application/x-www-form-urlencoded'})
  try:opener.open(req);code=200
  except urllib.error.HTTPError as e:code=e.code
  check(code==400,'unsupported admin action rejected')
 finally:
  server.terminate();server.wait(timeout=5);log.close()
 print(f'HTTP reports: {passed} passed, 0 failed')
