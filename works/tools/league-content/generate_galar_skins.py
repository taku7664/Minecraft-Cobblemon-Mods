"""Author the module's own 64x64 skin for Opal (Pillow).

The other Galar trainers use Planet Minecraft skins (textures/npcs/galar/CREDITS.txt); only names listed in DRAWN
are written, so a rerun never overwrites them.
"""
from pathlib import Path
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'works/mods/more-cobblemon-contents-league-challenge/src/main/resources/assets/more_cobblemon_contents_league_challenge/textures/npcs/galar'
# name, skin, hair, jersey, accent, trousers, eyes
DRAWN={'opal'}
PEOPLE=[
('milo','#e9b393','#e79591','#74ac56','#fff9df','#667554','#6e9e63'),
('nessa','#986446','#173b56','#f9f5ed','#42b8c8','#f0f1e9','#36bad2'),
('kabu','#dfac8b','#939da3','#f2eee5','#db4d35','#403a3c','#7f6d61'),
('bea','#c89b7f','#c8c9bd','#f4eee3','#f29a35','#38342f','#98947c'),
('allister','#e5e1da','#27282e','#493856','#b191c6','#3e354a','#6d537a'),
('opal','#e2b4ae','#ada6c4','#b179a6','#615c88','#8b6c92','#6b7b9b'),
('gordie','#d8ae92','#e3d5a1','#554a45','#c9916e','#2f3038','#577484'),
('melony','#ebc1b4','#e4e7ed','#f6f3ec','#7c9db5','#263b50','#759cb7'),
('piers','#dcc7c1','#282633','#252330','#eeeeeb','#33303c','#739883'),
('raihan','#aa7054','#313441','#e9a342','#424f58','#3f4e56','#9ac5c0'),
('marnie','#efd1c1','#24242c','#e5a3b4','#33313c','#35323b','#63a59d'),
('hop','#b3836d','#3b313c','#597fa6','#e9e0d1','#343d48','#c29350'),
('bede','#eed2be','#e3dfdb','#bc668a','#ece8e3','#ded8d6','#8f7195'),
('leon','#ac7957','#69467f','#342c43','#c7414e','#f4eee6','#ca9c3d')]

def shade(hexcolor,factor):
    return tuple(min(255,max(0,round(int(hexcolor[i:i+2],16)*factor))) for i in (1,3,5))+(255,)

def make(person):
    name,skin,hair,shirt,accent,pants,eye=person
    im=Image.new('RGBA',(64,64),(0,0,0,0)); d=ImageDraw.Draw(im)
    def box(x,y,w,h,c): d.rectangle((x,y,x+w-1,y+h-1),fill=c)
    def cube(x,y,w,h,depth,c):
        for px,py,pw,ph,f in [(x+depth,y,w,depth,1.08),(x+depth+w,y,w,depth,.7),(x,y+depth,depth,h,.83),(x+depth,y+depth,w,h,1),(x+depth+w,y+depth,depth,h,.88),(x+depth+w+depth,y+depth,w,h,.78)]:box(px,py,pw,ph,shade(c,f))
    cube(0,0,8,8,8,skin);cube(16,16,8,12,4,shirt)
    cube(40,16,4,12,4,shirt);cube(32,48,4,12,4,shirt)
    cube(0,16,4,12,4,pants);cube(16,48,4,12,4,pants)
    # Hair covers crown, sides and back; face remains at vanilla UV 8,8.
    box(8,0,8,8,shade(hair,1));box(16,0,8,8,shade(hair,.8));box(24,8,8,8,shade(hair,.85))
    box(0,8,8,3,shade(hair,.9));box(16,8,8,3,shade(hair,.9))
    box(8,8,8,2,shade(hair,1));box(8,10,1,3,shade(hair,.9));box(15,10,1,2,shade(hair,.9))
    box(10,11,1,1,shade(eye,1));box(13,11,1,1,shade(eye,1))
    box(11,14,2,1,shade(skin,.76));box(11,13,2,1,shade(skin,.95))
    # Shirt front/back trim and sleeves; hands and shoes.
    box(23,20,2,2,shade(skin,1));box(20,27,8,2,shade(accent,1));box(32,27,8,2,shade(accent,.85))
    box(22,24,4,1,shade(accent,1));box(23,23,2,3,shade(accent,1))
    for x,y in [(44,20),(36,52)]:box(x,y+7,4,2,shade(accent,1));box(x,y+9,4,3,shade(skin,1))
    for x,y in [(4,20),(20,52)]:box(x,y+9,4,3,shade('#34333c',1));box(x,y+11,4,1,shade(accent,1))
    # Distinguishing 8-pixel faces and outer layers, also used by the League portrait.
    if name=='milo':
        box(40,8,8,1,shade('#ede4c9',1));box(41,9,6,1,shade('#79a65d',1));box(9,13,1,1,shade('#c37b70',1));box(14,13,1,1,shade('#c37b70',1))
    if name=='nessa':
        box(40,8,2,6,shade(hair,1));box(46,8,2,6,shade(hair,1));box(40,11,1,4,shade(accent,1));box(47,11,1,4,shade(accent,1))
    if name=='kabu':
        box(8,8,8,1,shade('#b9c4c6',1));box(10,10,2,1,shade('#696c6e',1));box(13,10,2,1,shade('#696c6e',1))
    if name=='bea':box(40,10,8,1,shade('#f4ecdc',1));box(46,9,1,1,shade(accent,1))
    if name=='allister':
        box(9,10,6,5,shade('#eee9dd',1));box(10,11,2,2,shade('#24222b',1));box(13,11,2,2,shade('#24222b',1));box(12,14,1,1,shade('#302634',1))
    if name=='opal':
        box(40,8,8,2,shade('#7b638b',1));box(41,7,6,1,shade('#b18aa8',1));box(10,11,2,1,shade('#6d7788',1));box(13,11,2,1,shade('#6d7788',1));box(12,11,1,1,shade('#817d86',1))
    if name=='gordie':
        box(41,11,3,2,shade('#2b3a40',1));box(44,11,3,2,shade('#2b3a40',1));box(41,11,2,1,shade('#9cc7ca',1));box(45,11,2,1,shade('#9cc7ca',1))
    if name=='melony':
        box(40,8,8,2,shade('#263c52',1));box(40,10,1,5,shade(hair,1));box(47,10,1,5,shade(hair,1));box(20,21,8,1,shade(accent,1))
    if name=='piers':
        box(42,8,2,5,shade('#f3edea',1));box(46,8,1,6,shade('#f3edea',1));box(10,12,1,1,shade('#5e5568',1));box(13,12,1,1,shade('#5e5568',1));box(23,20,2,10,shade(accent,1))
    if name=='raihan':
        box(40,8,8,1,shade(shirt,1));box(40,9,1,6,shade(shirt,1));box(47,9,1,6,shade(shirt,1));box(20,22,8,1,shade(accent,1))
    if name=='marnie':box(40,9,1,6,shade(hair,1));box(47,9,1,6,shade(hair,1));box(40,10,1,1,shade('#e26981',1));box(47,10,1,1,shade('#e26981',1))
    if name=='hop':box(20,20,8,2,shade(accent,1));box(40,8,2,1,shade(hair,.8));box(44,8,2,1,shade(hair,.8))
    if name=='bede':
        for x,y in [(40,9),(42,8),(44,9),(46,8)]:box(x,y,2,2,shade(hair,1.06))
        box(23,21,2,10,shade(accent,1))
    if name=='leon':
        box(40,8,8,2,shade('#292632',1));box(42,8,4,1,shade('#d8b75b',1));box(43,7,2,1,shade('#d8b75b',1));box(40,10,1,6,shade(hair,1));box(47,10,1,6,shade(hair,1));box(32,20,8,12,shade(accent,1));box(20,20,1,12,shade('#dfb752',1));box(27,20,1,12,shade('#dfb752',1))
    return im

if __name__=='__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    for p in PEOPLE:
        if p[0] in DRAWN: make(p).save(OUT/(p[0]+'.png'))
    print('Wrote 14 original 64x64 Galar-inspired skins to',OUT)
