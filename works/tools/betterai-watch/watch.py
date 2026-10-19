"""Opponent-model watch table: every prediction the AI makes about the foe in front, against what the foe then did.

Usage: python watch.py <run dir>, the MOCK_OUT folder of an EmbeddedTeamBattle league run made with BETTERAI_MATCHUP_VIEW=1
(one <game>/decisions.jsonl.gz per game). Reads matchupView.intent / switchModel and memory.tendencies from each singles decision,
and the foe's first action of that turn from the public log."""
import json,gzip,re,collections,sys,glob,os
canon=lambda s: re.sub(r'[^a-z0-9]','',(s or '').lower())
recs=[]
for path in sorted(glob.glob(os.path.join(sys.argv[1],'*','decisions.jsonl.gz'))):
    rows=[json.loads(l) for l in gzip.open(path,'rt',encoding='utf8')]
    pl=[]
    for r in reversed(rows):
        p=(r.get('input') or {}).get('publicLog')
        if p: pl=p;break
    acted=collections.defaultdict(dict); t=0
    for l in pl:
        p=l.split('|')
        if len(p)>1 and p[1]=='turn': t=int(p[2])
        elif len(p)>3 and p[1]=='move' and '[from]' not in l: acted[t].setdefault(p[2][:2],('move',canon(p[3])))
        elif len(p)>3 and p[1]=='switch': acted[t].setdefault(p[2][:2],('switch',canon(p[3].split(',')[0])))
        # Knocked out before it acted: the switch after it is a replacement, not a choice; the turn shows no choice.
        elif len(p)>2 and p[1]=='faint': acted[t].setdefault(p[2][:2],None)
    for r in rows:
        mv=r.get('matchupView') or {}; intent=mv.get('intent')
        if intent is None or ',' in r['actionId']: continue
        # A replacement after our faint is not a turn the foe answers.
        if all(v.startswith('switch') for v in r['candidateMoves'].values()): continue
        did=acted[r['turn']].get('p2' if r['side']=='p1' else 'p1')
        if did is None: continue
        ko=max([float(re.search(r'ko1=([\d.]+)',x).group(1)) for x in mv.get('ours') or []],default=0)
        tend={x['response']:x['estimatedRate'] for x in (r.get('memory') or {}).get('tendencies',[]) if x['situation']=='GENERAL'}
        recs.append(dict(intent=intent,did=did,model=(mv.get('switchModel') or {}).get('chance'),
            sit=('we first' if (mv.get('firstMove') or 0)>=0.5 else 'we slower')+(' + our sure KO' if ko>=0.9 else ''),
            tend=tend.get('SWITCH'),
            # The chance the rules actually used (counted rates after the repeat record); singles has one foe in front.
            used=next(iter(((r.get('stages') or {}).get('opponentSwitchChances') or {}).values()),None), benchSeen=mv.get('switchModel') is not None,
            moveOpts=any(o['kind']!='SWITCH' for o in intent)))
allr=recs
noBench=[x for x in allr if not x['benchSeen']]
print(f'positions (foe free to act): {len(allr)}')
print(f'  no foe bench seen (no switch can be predicted): {len(noBench)}, of which the foe switched {sum(x["did"][0]=="switch" for x in noBench)/max(1,len(noBench)):.0%}')
noMoves=[x for x in allr if not x['moveOpts']]
print(f'  no foe move revealed yet (no move can be predicted): {len(noMoves)}')
recs=[x for x in allr if x['benchSeen'] and x['moveOpts']]
n=len(recs); print(f'  scored below (bench seen and a move revealed): {n}')
def brier(ps,ys): return sum((p-y)**2 for p,y in zip(ps,ys))/len(ps)
# 1. What kind of action, predicted share against actual share.
kinds=['ATTACK','STATUS','PROTECT','SETUP','SWITCH']
pred=collections.Counter(); act=collections.Counter(); unpred=0; maxmv=0
for x in recs:
    for o in x['intent']: pred[o['kind']]+=o['p']
    if x['did'][0]=='switch': act['SWITCH']+=1; continue
    m=x['did'][1]
    k=next((o['kind'] for o in x['intent'] if canon(o['move'])==m),None)
    if k: act[k]+=1
    elif m.startswith('max') or m.startswith('gmax'): maxmv+=1
    else: unpred+=1
print('\n[1] action kind: predicted share vs actual share')
for k in kinds: print(f'  {k:8} predicted {pred[k]/n:6.1%}  actual {act[k]/n:6.1%}')
print(f'  move not among the predicted options: {unpred/n:.1%}   max moves (not matched): {maxmv/n:.1%}')
# 2. Switch chance calibration: the intent (all switch options), the switch model, the in-battle tendency.
ys=[1.0 if x['did'][0]=='switch' else 0.0 for x in recs]; base=sum(ys)/n
ps=[sum(o['p'] for o in x['intent'] if o['kind']=='SWITCH') for x in recs]
print(f'\n[2] switch: actual {base:.1%}; Brier intent {brier(ps,ys):.4f}, always-the-base-rate {brier([base]*n,ys):.4f}')
ur=[(x['used'],y) for x,y in zip(recs,ys) if x['used'] is not None]
if ur: print(f'  used by the rules (counted rates): n={len(ur)} Brier {brier([a for a,_ in ur],[b for _,b in ur]):.4f}, base {brier([base]*len(ur),[b for _,b in ur]):.4f}, mean {sum(a for a,_ in ur)/len(ur):.1%}')
tr=[(x['tend'],y) for x,y in zip(recs,ys) if x['tend'] is not None]
if tr: print(f'  in-battle tendency (response model): n={len(tr)} Brier {brier([a for a,_ in tr],[b for _,b in tr]):.4f}, base {brier([base]*len(tr),[b for _,b in tr]):.4f}, mean {sum(a for a,_ in tr)/len(tr):.1%}')
print('  by predicted chance:')
for lo,hi in [(0,.05),(.05,.1),(.1,.2),(.2,.3),(.3,.5),(.5,1.01)]:
    s=[y for p,y in zip(ps,ys) if lo<=p<hi]
    if s: print(f'    {lo:.0%}-{min(hi,1):.0%}: n={len(s):4} actual {sum(s)/len(s):.0%}')
print('  by situation (predicted / actual):')
by=collections.defaultdict(list)
for x,p,y in zip(recs,ps,ys): by[x['sit']].append((p,y,x['used']))
for k,v in sorted(by.items()):
    used=[u for _,_,u in v if u is not None]
    print(f'    {k:24} n={len(v):4} model {sum(a for a,_,_ in v)/len(v):5.1%} used {sum(used)/len(used) if used else float("nan"):5.1%} actual {sum(b for _,b,_ in v)/len(v):5.1%}')
# 3. Which Pokemon comes in.
sw=[x for x in recs if x['did'][0]=='switch']
hit=[]; uni=[]; top=0; unseen=0
for x in sw:
    opts=[o for o in x['intent'] if o['kind']=='SWITCH']; tot=sum(o['p'] for o in opts)
    if not opts or tot<=0: continue
    by_sp=collections.Counter()
    for o in opts: by_sp[canon(o['switchIn'])]+=o['p']/tot
    # One not yet seen cannot be predicted (public information only); counted apart.
    if x['did'][1] not in by_sp: unseen+=1; continue
    hit.append(by_sp[x['did'][1]]); uni.append(1/len(by_sp)); top+=max(by_sp,key=by_sp.get)==x['did'][1]
print(f'\n[3] switch-in: {len(hit)+unseen} switches, {unseen} brought in a bench member not yet seen (not predictable)')
if hit: print(f'  seen ones: n={len(hit)}, chance given to the one that came in {sum(hit)/len(hit):.1%} (even split {sum(uni)/len(uni):.1%}), top pick right {top/len(hit):.1%}')
# 4. Which move.
mvs=[x for x in recs if x['did'][0]=='move']
got=[]; even=[]; top=0; cnt=0
for x in mvs:
    opts=collections.Counter()
    for o in x['intent']:
        if o['kind']!='SWITCH' and o['move']: opts[canon(o['move'])]+=o['p']
    tot=sum(opts.values())
    if tot<=0 or x['did'][1] not in opts: continue
    cnt+=1; got.append(opts[x['did'][1]]/tot); even.append(1/len(opts)); top+= max(opts,key=opts.get)==x['did'][1]
if cnt: print(f'\n[4] move (when it moved, move among options): n={cnt}, chance given to the move used {sum(got)/cnt:.1%} (even split {sum(even)/cnt:.1%}), top pick right {top/cnt:.1%}')
