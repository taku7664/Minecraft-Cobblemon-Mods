"""Translate enabled spawn resources, including Cobblemon 1.8 preset semantics.

Keep source IDs and each herd member's own properties in the generated data. Never
guess a new condition: an unsupported field stops generation instead of publishing
an incomplete requirement. This is a resource listing, not an in-game spawn simulator.
"""
import copy
import json
import re
import warnings
from collections import defaultdict

from biomes import STRUCTURES, TAGS
from pokedex_sources import load_resources, resource_entries

BUCKETS = {'common':'흔함', 'uncommon':'보통', 'rare':'드묾', 'ultra-rare':'매우 드묾', 'boss':'우두머리 무리'}
POSITIONS = {'grounded':'땅 위', 'surface':'물 위', 'submerged':'물속', 'seafloor':'바다 밑바닥', 'lavafloor':'용암 바닥', 'fishing':'낚시', 'area':'공중'}
DIMENSIONS = {'minecraft:overworld':'오버월드', 'minecraft:the_nether':'네더', 'minecraft:the_end':'엔드',
              'cobblemon_dimensions:ancient':'고대 차원', 'cobblemon_dimensions:future':'미래 차원', 'cobblemon_dimensions:ultra_space':'울트라스페이스'}
TIMES = {'day':'낮', 'night':'밤', 'dawn':'새벽', 'dusk':'해 질 녘', 'morning':'아침', 'noon':'한낮', 'afternoon':'오후', 'midnight':'한밤중', 'twilight':'해 질 녘·새벽'}
MOON = ['보름달','기우는 볼록달','하현달','그믐달','삭','초승달','상현달','차오르는 볼록달']
BLOCK_TAGS = {
    'natural':'자연 바닥 블록', 'trees':'나무 블록', 'concrete_blocks':'콘크리트 계열 블록',
    'redstone_blocks':'레드스톤 장치', 'trail_ruins_blocks':'오솔길 폐허 블록', 'ancient_city_blocks':'고대 도시 블록',
    'desert_pyramid_blocks':'사막 사원 블록', 'end_city_blocks':'엔드 시티 블록', 'jungle_pyramid_blocks':'정글 사원 블록',
    'mansion_blocks':'삼림 대저택 블록', 'mansion_bedroom_blocks':'삼림 대저택 침실 블록', 'mansion_dining_blocks':'삼림 대저택 식당 블록',
    'nether_structure_blocks':'네더 구조물 블록', 'ocean_ruin_blocks':'바다 폐허 블록', 'pillager_outpost_blocks':'약탈자 전초기지 블록',
    'ruined_portal_blocks':'폐허가 된 차원문 블록', 'apricorns':'규토리', 'berries':'열매', 'gemstones':'보석 블록',
    'saccharine_trees':'달콤한 나무', 'dead_coral':'죽은 산호', 'coral_blocks':'산호 블록', 'corals':'산호',
    'flowers':'꽃', 'blue_flowers':'파란 꽃', 'orange_flowers':'주황 꽃', 'pink_flowers':'분홍 꽃', 'red_flowers':'빨간 꽃',
    'white_flowers':'하얀 꽃', 'yellow_flowers':'노란 꽃', 'leaves':'나뭇잎', 'coal_ores':'석탄 광석', 'diamond_ores':'다이아몬드 광석',
    'iron_ores':'철 광석', 'redstone_ores':'레드스톤 광석', 'sand':'모래', 'stone_bricks':'석재 벽돌',
    'wool':'양털', 'wool_carpets':'양털 카펫', 'convertable_to_mud':'진흙으로 바뀌는 블록',
    'trail_ruins_replaceable':'오솔길 폐허가 생성될 수 있는 블록', 'water':'물', 'lava':'용암', 'beehives':'벌집',
}
EXTRA_STRUCTURES = {
    'minecraft:desert_well':'사막 우물', 'minecraft:nether_fossil':'네더 화석', 'minecraft:woodland_mansion':'삼림 대저택',
    '#cobblemon:ruin':'유적', '#cobblemon:ruins/arch':'유적 아치', '#cobblemon:shipwreck_cove':'난파선이 있는 만',
    'cobblemon:ruins/luna_henge_ruins':'달의 돌고리 유적', 'cobblemon:ruins/sol_henge_ruins':'태양의 돌고리 유적',
    'cobblemon:ruins/stonjourner_henge_ruins':'돌헨진 유적', 'cobblemon:shipwreck_coves/lush_shipwreck_cove':'풀이 무성한 난파선 만',
    'cobblemon:shipwreck_coves/submerged_shipwreck_cove':'물에 잠긴 난파선 만',
}
REGIONS = {'alolan':'알로라의 모습', 'galarian':'가라르의 모습', 'hisuian':'히스이의 모습', 'paldean':'팔데아의 모습', 'valencian':'발렌시아의 모습'}
PROPERTY_LABELS = {'snake_pattern':'무늬', 'bull_breed':'품종', 'magikarp_jump':'무늬', 'character':'문자', 'mooshtank':'버섯 모습',
    'special_spots':'특수 반점', 'face_spots':'얼굴 반점', 'face_spots2':'추가 얼굴 반점', 'blossom_form':'꽃 모습', 'sea':'서식 모습',
    'striped':'줄무늬', 'vivillon_wings':'날개 무늬', 'flower':'꽃', 'wolf_form':'모습', 'squawkabilly_color':'깃털 색',
    'matcha_authenticity':'모습', 'tea_authenticity':'모습', 'maushold_family':'가족 수', 'landsnake_form':'마디 수',
    'cosplay':'코스튬', 'forecast':'모습', 'bagworm_cloak':'도롱', 'dance_style':'스타일', 'meteor_shield':'외형', 'core_color':'코어 색'}
VALUES = {'attack':'공격', 'classic':'기본', 'dark':'어둠', 'elusive':'특수', 'legacy':'옛 무늬', 'sound':'소리', 'speed':'속도',
    'aqua':'워터종', 'blaze':'블레이즈종', 'combat':'컴뱃종', 'brown':'갈색', 'red':'빨강', 'none':'없음', 'sunshine':'포지티브폼',
    'east':'동쪽바다', 'west':'서쪽바다', 'blue':'파랑', 'white':'하양', 'yellow':'노랑', 'orange':'주황', 'pink':'분홍',
    'green':'초록', 'indigo':'남색', 'violet':'보라', 'eternal':'영원의 꽃', 'dusk':'황혼의 모습', 'midday':'한낮의 모습', 'midnight':'한밤중의 모습',
    'artisan':'장인', 'counterfeit':'가짜', 'masterpiece':'걸작', 'unremarkable':'범작', 'antique':'진품', 'phony':'가품',
    'four':'네 식구', 'three':'세 식구', 'three-segment':'세 마디', 'two-segment':'두 마디', 'pop_star':'아이돌', 'rock_star':'하드록',
    'rainy':'빗방울', 'snowy':'설운', 'sunny':'태양', 'plant':'초목', 'sandy':'모래땅', 'trash':'슈레', 'baile':'이글이글',
    'pau':'훌라훌라', 'pom-pom':'파칙파칙', 'sensu':'하늘하늘', 'core':'코어', 'meteor':'유성', 'skelly':'해골',
    'archipelago':'군도', 'continental':'대륙', 'elegant':'우아', 'fancy':'팬시', 'garden':'정원', 'high-plains':'황야',
    'icy-snow':'빙설', 'jungle':'정글', 'marine':'마린', 'meadow':'화원', 'modern':'모던', 'monsoon':'스콜', 'ocean':'오션',
    'polar':'설국', 'river':'대하', 'sandstorm':'사진', 'savanna':'사바나', 'sun':'태양', 'tundra':'설원'}
COLORS = {'apricot':'살구', 'black':'검정', 'blue':'파랑', 'brown':'갈색', 'gray':'회색', 'orange':'주황', 'pink':'분홍', 'purple':'보라', 'violet':'보라', 'white':'하양', 'gold':'금색'}
PATTERNS = {'stripes':'줄무늬', 'tiger':'호랑이 무늬', 'zebra':'얼룩말 무늬', 'forehead':'이마 무늬', 'mask':'가면 무늬',
    'raindrops':'빗방울 무늬', 'saucy':'특수 무늬', 'bubbles':'물방울 무늬', 'diamonds':'마름모 무늬', 'patches':'얼룩 무늬',
    'dapples':'반점 무늬', 'orca':'범고래 무늬', 'two-tone':'두 가지 색'}


def plural(data, singular):
    return copy.deepcopy(data.get(singular+'s', [])) + ([copy.deepcopy(data[singular])] if singular in data else [])


def biome_registry(sources, generated=()):
    biomes=set(generated)
    tags={}
    for source,prefix in sources:
        for name,raw in resource_entries(source,prefix):
            match=re.fullmatch(r'data/([^/]+)/worldgen/biome/(.+)\.json',name)
            if match:biomes.add(match[1]+':'+match[2])
            match=re.fullmatch(r'data/([^/]+)/tags/worldgen/biome/(.+)\.json',name)
            if match:
                data=json.loads(raw);id_=match[1]+':'+match[2]
                if data.get('replace'):tags[id_]=[]
                tags.setdefault(id_,[]).extend(v['id'] if isinstance(v,dict) else v for v in data.get('values',[]))
    cache={}
    def members(ref, visiting=frozenset()):
        if not ref.startswith('#'):return {ref}&biomes
        id_=ref[1:]
        if id_ in cache:return cache[id_]
        if id_ in visiting:raise ValueError(f'Cyclic biome tag: {id_}')
        result=set()
        for value in tags.get(id_,[]):result.update(members(value,visiting|{id_}))
        cache[id_]=result
        return result
    return members


def merge_condition(base, extra, mode='INSERT'):
    result = copy.deepcopy(base)
    for k,v in extra.items():
        if mode == 'REPLACE' or k not in result or result[k] is None:
            result[k] = copy.deepcopy(v)
        elif mode == 'INSERT' and isinstance(v,list):
            result[k] = copy.deepcopy(result[k])
            for item in v:
                if item not in result[k]:result[k].append(copy.deepcopy(item))
    if mode not in ('INSERT','KEEP','REPLACE'):
        raise ValueError(f'Unsupported preset mergeMode: {mode}')
    return result


def expand_spawn(spawn, presets):
    result=copy.deepcopy(spawn)
    result['conditions']=plural(spawn,'condition')
    result['anticonditions']=plural(spawn,'anticondition')
    result['weightMultipliers']=plural(spawn,'weightMultiplier')
    result['spawnablePositionType']=spawn.get('spawnablePositionType',spawn.get('context'))
    for name in dict.fromkeys(spawn.get('presets', [])):
        ns,path=name.split(':',1) if ':' in name else ('cobblemon',name)
        preset=presets.get(f'data/{ns}/spawn_detail_presets/{path}.json')
        if preset is None:
            warnings.warn(f'Unknown preset name: {name}; skipped as Cobblemon does', stacklevel=2)
            continue
        mode=preset.get('mergeMode','INSERT').upper()
        for k in ('bucket','spawnablePositionType','weight','percentage'):
            if k in preset:result[k]=copy.deepcopy(preset[k])
        if preset.get('condition'):
            result['conditions']=[merge_condition(c,preset['condition'],mode) for c in result['conditions']] if result['conditions'] else [copy.deepcopy(preset['condition'])]
        if preset.get('anticondition'):result['anticonditions'].append(copy.deepcopy(preset['anticondition']))
        if 'weightMultipliers' in preset:
            result['weightMultipliers']=merge_condition({'v':result['weightMultipliers']},{'v':preset['weightMultipliers']},mode)['v']
    if result.get('compositeCondition'):
        raise ValueError(f"Unsupported compositeCondition in {spawn.get('id')}")
    return result


class Labels:
    def __init__(self, lang, installed, biome_members=None):
        self.lang=lang
        self.installed=set(installed)|{'minecraft','cobblemon','c','fabric'}
        self.biome_members=biome_members

    def available(self, ref):
        return ref.lstrip('#').split(':',1)[0] in self.installed

    def word(self, ref, kind='block'):
        if kind=='biome':
            if ref.startswith('#'):
                ns,path=ref[1:].split(':',1)
                if ns in ('cobblemon','minecraft') and path in TAGS:return TAGS[path]+' 계열'
                raise ValueError(f'Untranslated biome tag: {ref}')
            ns,path=ref.split(':',1)
            name=self.lang.get(f'biome.{ns}.{path}')
        elif kind=='dimension':
            name=DIMENSIONS.get(ref)
        elif kind=='structure':
            name=EXTRA_STRUCTURES.get(ref) or STRUCTURES.get(ref)
        elif ref.startswith('#'):
            name=BLOCK_TAGS.get(ref.split(':',1)[1])
        else:
            ns,path=ref.split(':',1) if ':' in ref else ('cobblemon',ref)
            name=self.lang.get(f'{kind}.{ns}.{path}') or self.lang.get(f'item.{ns}.{path}') or self.lang.get(f'block.{ns}.{path}')
            if not name and ns=='cobblemon' and path.endswith('_rod') and self.lang.get(f'item.cobblemon.{path[:-4]}_ball'):
                name=self.lang.get('item.cobblemon.poke_rod','포켓몬 낚싯대')+' ('+self.lang[f'item.cobblemon.{path[:-4]}_ball']+')'
            if not name and ref=='minecraft:double_smooth_stone_slab':name='매끄러운 돌 반 블록'
        if not name:raise ValueError(f'Untranslated {kind}: {ref}')
        return name

    def filter_condition(self, cond):
        result=copy.deepcopy(cond)
        for field in ('biomes','structures','neededNearbyBlocks','neededBaseBlocks','dimensions'):
            if field in result:
                result[field]=[r for r in result[field] if (bool(self.biome_members(r)) if field=='biomes' and self.biome_members is not None else self.available(r))]
                if not result[field]:return None
        return result

    def condition(self, cond, places=True):
        known={'biomes','dimensions','timeRange','canSeeSky','minSkyLight','maxSkyLight','isThundering','isRaining','moonPhase',
               'minLight','maxLight','minX','maxX','minY','maxY','minZ','maxZ','structures','neededNearbyBlocks','neededBaseBlocks',
               'isSlimeChunk','minLureLevel','maxLureLevel','rodType','bait','isPokeSnack','fluid'}
        unknown=set(cond)-known
        if unknown:raise ValueError(f'Unsupported spawn conditions: {sorted(unknown)}')
        out=[]
        if places:
            for k,kind,prefix in [('biomes','biome','바이옴: '),('dimensions','dimension','차원: ')]:
                if cond.get(k):out.append(prefix+' 또는 '.join(dict.fromkeys(self.word(r,kind) for r in cond[k])))
        if 'isPokeSnack' in cond:out.append('포케스낵으로만 출현' if cond['isPokeSnack'] else '자연 출현만 가능')
        if 'timeRange' in cond:out.append(TIMES.get(str(cond['timeRange']),str(cond['timeRange'])))
        if 'canSeeSky' in cond:out.append('하늘이 보이는 곳' if cond['canSeeSky'] else '하늘이 가려진 곳')
        for lo,hi,label,default in [('minSkyLight','maxSkyLight','하늘빛',(0,15)),('minLight','maxLight','밝기',(0,15)),('minLureLevel','maxLureLevel','미끼 효과',(0,None))]:
            if lo in cond or hi in cond:
                low,high=cond.get(lo,default[0]),cond.get(hi,default[1])
                if lo in cond and hi in cond or default[1] is not None:out.append(f'{label} {low}~{high}')
                elif low is not None and lo in cond:out.append(f'{label} {low} 이상')
                else:out.append(f'{label} {high} 이하')
        for axis in 'XYZ':
            lo,hi='min'+axis,'max'+axis
            if lo in cond or hi in cond:
                out.append(f'{axis} {cond[lo]}~{cond[hi]}' if lo in cond and hi in cond else f'{axis} {cond[lo]} 이상' if lo in cond else f'{axis} {cond[hi]} 이하')
        if 'isThundering' in cond:out.append('뇌우' if cond['isThundering'] else '뇌우가 아닐 때')
        if 'isRaining' in cond:out.append('비' if cond['isRaining'] else '비가 오지 않을 때')
        if 'moonPhase' in cond:
            phase=cond['moonPhase'];out.append(MOON[int(phase)] if str(phase).isdigit() and 0<=int(phase)<8 else f'달 위상 {phase}')
        for k,kind,before,after in [('structures','structure','',' 근처'),('neededNearbyBlocks','block','근처에 ',' 중 하나 이상'),('neededBaseBlocks','block','',' 중 하나 위')]:
            if cond.get(k):out.append(before+' 또는 '.join(dict.fromkeys(self.word(r,kind) for r in cond[k]))+after)
        if 'fluid' in cond:out.append(self.word(cond['fluid'])+'에서')
        if 'isSlimeChunk' in cond:out.append('슬라임 청크' if cond['isSlimeChunk'] else '슬라임 청크가 아닌 곳')
        for k,tail in [('rodType',' 사용'),('bait',' 미끼 사용')]:
            if k in cond:out.append(self.word(cond[k],'item')+tail)
        return out

    def notes(self, target):
        out=[]
        for token in target.split()[1:]:
            k,sep,v=token.partition('=')
            if not token or k=='alpha':continue
            if k in REGIONS and v!='false':out.append(REGIONS[k])
            elif k=='held_item':out.append(self.word(v,'item')+' 지님')
            elif k in ('male','female'):out.append('수컷' if k=='male' else '암컷')
            elif k=='region_bias' or k.endswith('bias'):
                region={'alola':'알로라','galar':'가라르','hisui':'히스이'}.get(v or k.removesuffix('bias'),v)
                out.append(region+' 모습이 나오기 쉬움')
            elif k in ('squawkabilly',):continue
            elif k in ('wooper_heart','whiscash_nero') and v=='true':out.append('하트 모습' if k=='wooper_heart' else '네로 모습')
            elif k=='roaming':out.append('상자폼' if v=='false' else '도보폼')
            elif k in ('crystal','red-leaf','bloodmoon'):out.append({'crystal':'크리스털 모습','red-leaf':'붉은 잎 모습','bloodmoon':'붉은 달'}[k])
            elif k in PROPERTY_LABELS:
                value=VALUES.get(v,v)
                if k=='magikarp_jump' and v not in VALUES:
                    if v.startswith('calico-'):value='얼룩 무늬 ('+'·'.join(COLORS[p] for p in v.split('-')[1:])+')'
                    else:
                        color,pattern=v.split('-',1);value=COLORS[color]+' '+PATTERNS[pattern]
                out.append(PROPERTY_LABELS[k]+': '+value)
            else:raise ValueError(f'Unsupported Pokemon spawn property: {token}')
        return list(dict.fromkeys(out))


def generate_spawns(sources, installed, lang, generated_biomes=()):
    resources=load_resources(sources,('spawn_pool_world','spawn_detail_presets','spawn_rules'))
    for name,rule in resources.items():
        if '/spawn_rules/' in name and rule.get('enabled',True) not in (False,'false'):
            raise ValueError(f'Enabled spawn rule needs explicit wiki support: {name}')
    presets={n:d for n,d in resources.items() if '/spawn_detail_presets/' in n}
    labels=Labels(lang,installed,biome_registry(sources,generated_biomes))
    listings=defaultdict(dict)
    pools=0
    for resource,pool in sorted(resources.items()):
        if '/spawn_pool_world/' not in resource or pool.get('enabled',True) in (False,'false'):continue
        if any(m not in installed for m in pool.get('neededInstalledMods',[])) or any(m in installed for m in pool.get('neededUninstalledMods',[])):continue
        pools+=1
        for raw in pool.get('spawns',[]):
            if raw.get('type') not in ('pokemon','pokemon-herd'):continue
            spawn=expand_spawn(raw,presets)
            position=spawn.get('spawnablePositionType')
            if position not in POSITIONS:raise ValueError(f'Unsupported spawn position {position}: {resource}#{raw["id"]}')
            if spawn.get('bucket') not in BUCKETS:raise ValueError(f'Unsupported bucket: {spawn.get("bucket")}')
            anti=[c for c in (labels.filter_condition(c) for c in spawn['anticonditions']) if c is not None]
            exclusions=[' · '.join(labels.condition(c)) for c in anti if c]
            boosts=[]
            for m in spawn['weightMultipliers']:
                positive=[c for c in (labels.filter_condition(c) for c in plural(m,'condition')) if c is not None]
                negative=[c for c in (labels.filter_condition(c) for c in plural(m,'anticondition')) if c is not None]
                if plural(m,'condition') and not positive:continue
                chunks=['('+' · '.join(labels.condition(c))+')' for c in positive]
                text=' 또는 '.join(chunks) if chunks else '항상'
                if negative:text+=' / 제외: '+' 또는 '.join(' · '.join(labels.condition(c)) for c in negative)
                boosts.append(text+f"일 때 {m.get('multiplier',1):g}배")
            members=spawn.get('herdablePokemon',[]) if spawn['type']=='pokemon-herd' else [{'pokemon':spawn['pokemon']}]
            for cond in spawn['conditions'] or [{}]:
                cond=labels.filter_condition(cond)
                if cond is None:continue
                base={'bucket':BUCKETS[spawn['bucket']], 'position':POSITIONS[position],
                      'dimensions':[labels.word(d,'dimension') for d in cond.get('dimensions',[])],
                      'biomes':[{'name':labels.word(b,'biome').removesuffix(' 계열'),'group':b.startswith('#')} for b in cond.get('biomes',[])],
                      'except':[], 'conditions':labels.condition(cond,places=False), 'exclusions':exclusions, 'boosts':boosts,
                      'herd':spawn['type']=='pokemon-herd'}
                for member in members:
                    target=member['pokemon'];sid=target.split()[0].split(':')[-1]
                    entry=copy.deepcopy(base)
                    entry['alpha']='alpha=true' in target.split()[1:]
                    entry['role']='리더' if member.get('isLeader') and not member.get('isFollower') else '동행' if member.get('isFollower') and not member.get('isLeader') else '리더·동행' if member.get('isLeader') and member.get('isFollower') else ''
                    entry['notes']=labels.notes(target)
                    identity=json.dumps(entry,ensure_ascii=False,sort_keys=True)
                    if identity not in listings[sid]:listings[sid][identity]=dict(entry,sources=[])
                    origin={'resource':resource,'id':raw['id'],'pokemon':target}
                    if origin not in listings[sid][identity]['sources']:listings[sid][identity]['sources'].append(origin)
    return {sid:list(rows.values()) for sid,rows in listings.items()},pools
