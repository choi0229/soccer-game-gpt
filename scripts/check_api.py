"""Integration check against a running Compose stack, without third-party Python packages."""
import json,sys,urllib.request,urllib.error
from concurrent.futures import ThreadPoolExecutor
base=sys.argv[1] if len(sys.argv)>1 else 'http://localhost:5187/api'
def request(path,body=None):
 data=None if body is None else json.dumps(body).encode()
 req=urllib.request.Request(base+path,data=data,headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=30) as response: return json.load(response)
def command(run,action): return request(f"/runs/{run['id']}/actions",{'expectedSeq':run['state']['seq'],'action':action})
a=request('/runs',{'seed':'12345'});b=request('/runs',{'seed':'12345'})
while not a['state']['completed']:
 if a['event']:
  action={'kind':'event','choice':0}
 else:
  action={'kind':'day','changes':{'dawn':'sleep','class':'teacher'}}
  if a['weekday']==6: action['sundayAction']='coach'
 a=command(a,action);b=command(b,action)
 assert a['state']==b['state'],'same seed/action divergence'
assert a['state']['day']==336
assert sum(m['competition'].startswith('주말리그') for m in a['state']['matches'])==14
assert request(f"/runs/{a['id']}/replay")['identical']
assert request(f"/runs/{b['id']}/replay")['identical']
assert request(f"/runs/{a['id']}")['state']==a['state'],'snapshot mismatch'
conflict=request('/runs',{'seed':55})
def concurrent(_):
 try: command(conflict,{'kind':'day','changes':{}});return 200
 except urllib.error.HTTPError as ex: return ex.code
with ThreadPoolExecutor(max_workers=2) as pool: statuses=sorted(pool.map(concurrent,range(2)))
assert statuses==[200,409],statuses
assert request(f"/runs/{conflict['id']}/replay")['identical']
print(f"PASS: two full 48-week seasons, {a['state']['seq']} choices each, replay, snapshots, concurrent 200/409")
print(f"Run IDs: {a['id']} {b['id']}")
