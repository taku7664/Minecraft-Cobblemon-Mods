import copy
import io
import json
import pathlib
import tempfile
import unittest
import warnings
import zipfile

from gen_pokedex import refresh_spawns
from pokedex_sources import mod_sources, load_resources
from pokedex_spawns import Labels, biome_registry, expand_spawn, generate_spawns


class SpawnDataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.lang = {'biome.minecraft.plains':'평원', 'biome.minecraft.forest':'숲',
                     'biome.cobblemon_dimensions.ancient_sea':'고대 바다',
                     'item.cobblemon.grass_gem':'풀주얼', 'item.cobblemon.love_ball':'러브볼',
                     'item.cobblemon.poke_rod':'포켓몬 낚싯대', 'item.cobblemon.love_sweet':'하트사탕'}
        self.installed = {'minecraft','cobblemon','cobblemon_dimensions'}

    def pack(self, name, resources):
        p=self.root/name
        with zipfile.ZipFile(p,'w') as z:
            for n,d in resources.items():z.writestr(n,json.dumps(d))
        return p

    def run_spawns(self, spawn, more=None):
        resources={'data/minecraft/worldgen/biome/plains.json':{},
                   'data/minecraft/worldgen/biome/forest.json':{},
                   'data/cobblemon/tags/worldgen/biome/is_overworld.json':{'values':['minecraft:plains','minecraft:forest']},
                   'data/cobblemon/spawn_pool_world/test.json':{'spawns':[spawn]}}
        resources.update(more or {})
        return generate_spawns([(self.pack('pack.zip',resources),'')],self.installed,self.lang)[0]

    def spawn(self, **updates):
        s={'id':'test','type':'pokemon','pokemon':'eevee','bucket':'rare','weight':1,'context':'grounded',
           'condition':{'biomes':['#cobblemon:is_overworld']}}
        s.update(updates)
        return s

    def test_enabled_resources_override_old_pool_without_loading_disabled_builtin(self):
        path='data/cobblemon/spawn_pool_world/test.json'
        base=self.pack('base.jar',{path:{'spawns':[self.spawn(pokemon='eevee')]},
            'resourcepacks/disabled/'+path:{'spawns':[self.spawn(pokemon='mew')]},
            'data/minecraft/worldgen/biome/plains.json':{}})
        override=self.pack('override.zip',{path:{'spawns':[self.spawn(pokemon='frigibax',condition={'biomes':['minecraft:plains']})]}})
        result,pools=generate_spawns([(base,''),(override,'')],self.installed,self.lang)
        self.assertEqual(set(result),{'frigibax'})
        self.assertEqual(pools,1)
        self.assertEqual(result['frigibax'][0]['position'],'땅 위')

    def test_presets_merge_arrays_keep_explicit_scalar_and_preserve_exclusion_groups(self):
        spawn=self.spawn(condition={'biomes':['minecraft:plains'],'minY':5},presets=['natural','urban'])
        presets={'data/cobblemon/spawn_detail_presets/natural.json':{'condition':{'minY':62,'neededBaseBlocks':['#cobblemon:natural']}},
            'data/cobblemon/spawn_detail_presets/urban.json':{'condition':{'biomes':['minecraft:forest']},'anticondition':{'structures':['#minecraft:village'],'maxY':13}}}
        expanded=expand_spawn(spawn,presets)
        self.assertEqual(expanded['conditions'][0]['minY'],5)
        self.assertEqual(expanded['conditions'][0]['biomes'],['minecraft:plains','minecraft:forest'])
        self.assertEqual(expanded['anticonditions'],[{'structures':['#minecraft:village'],'maxY':13}])
        result=self.run_spawns(spawn,presets)['eevee'][0]
        self.assertIn('자연 바닥 블록 중 하나 위',result['conditions'])
        self.assertEqual(result['except'],[])
        self.assertEqual(len(result['exclusions']),1)
        self.assertIn('Y 13 이하',result['exclusions'][0])
        self.assertIn('마을 근처',result['exclusions'][0])

    def test_alternative_conditions_are_separate_rows_and_presets_apply_to_both(self):
        spawn=self.spawn(presets=['natural'],conditions=[{'biomes':['minecraft:forest']}])
        result=self.run_spawns(spawn,{'data/cobblemon/spawn_detail_presets/natural.json':{'condition':{'neededBaseBlocks':['#cobblemon:natural']}}})['eevee']
        self.assertEqual(len(result),2)
        self.assertTrue(all('자연 바닥 블록 중 하나 위' in r['conditions'] for r in result))

    def test_snack_dimension_sky_range_and_fishing_requirements_are_not_dropped(self):
        s=self.spawn(context='fishing',condition={'biomes':['minecraft:plains'],'dimensions':['cobblemon_dimensions:ancient'],
            'isPokeSnack':True,'minSkyLight':4,'maxSkyLight':6,'rodType':'cobblemon:love_rod',
            'bait':'cobblemon:love_sweet','minLureLevel':1,'maxLureLevel':2,'minX':0})
        r=self.run_spawns(s)['eevee'][0]
        self.assertEqual(r['dimensions'],['고대 차원'])
        self.assertEqual(r['position'],'낚시')
        for text in ['포케스낵으로만 출현','하늘빛 4~6','미끼 효과 1~2','포켓몬 낚싯대 (러브볼) 사용','하트사탕 미끼 사용','X 0 이상']:
            self.assertIn(text,r['conditions'])

    def test_herd_follower_does_not_inherit_leader_gem_or_alpha(self):
        s=self.spawn(type='pokemon-herd',bucket='boss',herdablePokemon=[
            {'pokemon':'venusaur held_item=cobblemon:grass_gem alpha=true','isLeader':True,'isFollower':False},
            {'pokemon':'bulbasaur','isLeader':False,'isFollower':True}])
        result=self.run_spawns(s)
        self.assertEqual(result['venusaur'][0]['notes'],['풀주얼 지님'])
        self.assertTrue(result['venusaur'][0]['alpha'])
        self.assertEqual(result['bulbasaur'][0]['notes'],[])
        self.assertFalse(result['bulbasaur'][0]['alpha'])
        self.assertEqual(result['bulbasaur'][0]['role'],'동행')
        self.assertEqual(result['bulbasaur'][0]['bucket'],'우두머리 무리')

    def test_missing_biome_tag_never_becomes_an_unrestricted_spawn_or_exclusion(self):
        self.assertNotIn('eevee',self.run_spawns(self.spawn(condition={'biomes':['#minecraft:dark_forest']})))
        r=self.run_spawns(self.spawn(anticondition={'biomes':['#minecraft:woodland_mansion'],'maxY':13}))['eevee'][0]
        self.assertEqual(r['exclusions'],[])

    def test_tag_values_merge_and_replace_in_enabled_order(self):
        path='data/cobblemon/tags/worldgen/biome/is_forest.json'
        base=self.pack('base.jar',{path:{'values':['minecraft:plains']},'data/minecraft/worldgen/biome/plains.json':{},'data/minecraft/worldgen/biome/forest.json':{}})
        second=self.pack('second.zip',{path:{'values':['minecraft:forest']}})
        third=self.pack('third.zip',{path:{'replace':True,'values':['minecraft:forest']}})
        self.assertEqual(biome_registry([(base,''),(second,'')])('#cobblemon:is_forest'),{'minecraft:plains','minecraft:forest'})
        self.assertEqual(biome_registry([(base,''),(second,''),(third,'')])('#cobblemon:is_forest'),{'minecraft:forest'})

    def test_startup_generated_biomes_are_distinct_from_nonexistent_mod_biomes(self):
        pool={'spawns':[self.spawn(condition={'biomes':['cobblemon_dimensions:ancient_sea']})]}
        p=self.pack('generated.zip',{'data/cobblemon/spawn_pool_world/test.json':pool})
        self.assertEqual(generate_spawns([(p,'')],self.installed,self.lang)[0],{})
        result=generate_spawns([(p,'')],self.installed,self.lang,{'cobblemon_dimensions:ancient_sea'})[0]
        self.assertEqual(result['eevee'][0]['biomes'][0]['name'],'고대 바다')

    def test_string_false_disables_a_pool_and_string_true_enables_it(self):
        pool={'enabled':'false','spawns':[self.spawn(condition={'biomes':['minecraft:plains']})]}
        p=self.pack('false.zip',{'data/cobblemon/spawn_pool_world/test.json':pool,'data/minecraft/worldgen/biome/plains.json':{}})
        self.assertEqual(generate_spawns([(p,'')],self.installed,self.lang)[0],{})
        pool['enabled']='true'
        p=self.pack('true.zip',{'data/cobblemon/spawn_pool_world/test.json':pool,'data/minecraft/worldgen/biome/plains.json':{}})
        self.assertIn('eevee',generate_spawns([(p,'')],self.installed,self.lang)[0])

    def test_biome_and_snack_weight_bonuses_retain_their_requirements(self):
        s=self.spawn(weightMultipliers=[{'condition':{'biomes':['minecraft:plains']},'multiplier':5},
            {'condition':{'isPokeSnack':True},'anticondition':{'isRaining':True},'multiplier':2}])
        boosts=self.run_spawns(s)['eevee'][0]['boosts']
        self.assertIn('바이옴: 평원',boosts[0])
        self.assertIn('포케스낵으로만 출현',boosts[1])
        self.assertIn('제외: 비',boosts[1])

    def test_unsupported_condition_or_enabled_spawn_rule_stops_instead_of_publishing_partial_data(self):
        with self.assertRaisesRegex(ValueError,'newField'):
            self.run_spawns(self.spawn(condition={'biomes':['minecraft:plains'],'newField':True}))
        with self.assertRaisesRegex(ValueError,'spawn rule'):
            self.run_spawns(self.spawn(),{'data/cobblemon/spawn_rules/new.json':{'enabled':'true'}})

    def test_nested_mod_and_provides_alias_are_discovered(self):
        nested=io.BytesIO()
        with zipfile.ZipFile(nested,'w') as z:
            z.writestr('fabric.mod.json',json.dumps({'id':'child','provides':['child_alias']}))
            z.writestr('data/child/spawn_pool_world/pool.json','{"spawns":[]}')
        outer=self.root/'outer.jar'
        with zipfile.ZipFile(outer,'w') as z:
            z.writestr('fabric.mod.json',json.dumps({'id':'parent','jars':[{'file':'META-INF/jars/child.jar'}]}))
            z.writestr('META-INF/jars/child.jar',nested.getvalue())
        mods=mod_sources([outer])
        self.assertEqual(set(mods),{'parent','child','child_alias'})
        self.assertIn('data/child/spawn_pool_world/pool.json',load_resources([(mods['child'],'')],('spawn_pool_world',)))

    def test_spawn_refresh_preserves_all_other_fields_and_move_files(self):
        old=self.root/'old';out=self.root/'new';(old/'species').mkdir(parents=True)
        index=[{'id':'eevee','spawns':0,'dropSearch':'원본 드롭','name':'이브이'}]
        detail={'forms':[{'stats':[1,2,3],'drops':{'amount':7}}],'desc':'원문','spawns':[]}
        (old/'index.js').write_text('window.WIKI_DEX = '+json.dumps(index)+';',encoding='utf-8')
        (old/'species/eevee.js').write_text('window.WIKI_SPECIES["eevee"] = '+json.dumps(detail)+';',encoding='utf-8')
        for name in ['moves.js','abilities.js']:(old/name).write_bytes(b'original\r\n')
        refresh_spawns(old,out,{'eevee':[{'condition':'new'}]},{'eevee':({'implemented':True},'generation1')})
        saved=json.loads((out/'species/eevee.js').read_text(encoding='utf-8').rsplit(' = ',1)[-1].rstrip(';\n'))
        self.assertEqual({k:v for k,v in saved.items() if k!='spawns'},{k:v for k,v in detail.items() if k!='spawns'})
        self.assertEqual((out/'moves.js').read_bytes(),b'original\r\n')
        self.assertEqual(saved['spawns'],[{'condition':'new'}])


if __name__=='__main__':unittest.main()
